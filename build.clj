(ns build
  "Build program for the Armed Bear J Editor, driven by `bb`.

  `bb tasks` lists the targets. This is a port of build.xml, which is still
  here: the two are meant to produce the same tree under build/ so the results
  can be diffed. The `install` targets were deliberately not ported."
  (:refer-clojure :exclude [test])
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [clojure.tools.build.api :as b])
  (:import
   [java.io File]
   [java.time ZonedDateTime]
   [java.time.format DateTimeFormatter]
   [java.util Locale]))


;; properties

(def j-version "1.0.0")
(def java-version-min "25")

(def build-dir   "build")
(def classes-dir (str build-dir "/classes"))
(def test-dir    (str build-dir "/test"))
(def lib-dir     (str build-dir "/lib"))
(def bin-dir     (str build-dir "/bin"))
(def jar-file    (str build-dir "/j.jar"))

(def src-dir      "src")
(def test-src-dir "test/src")
(def dist-dir     "dist")
(def stage-dir    (str dist-dir "/j-" j-version))

;; Extensions. Each is its own project under extensions/<name>, with its own
;; deps.edn and its own class loader at run time, and is packaged into
;; build/lib/extensions/<name>/ -- which is where org.armedbear.j.extension
;; .Extensions looks, both for build/classes and for an installed j.jar, and
;; which dist-stage copies along with the rest of lib/.
(def extensions-dir      "extensions")
(def extensions-lib-dir  (str lib-dir "/extensions"))
(def extensions-test-dir (str build-dir "/extensions-test"))

;; the resources org.armedbear.j.Version reads
(def version-path (str classes-dir "/org/armedbear/j/version"))
(def build-path   (str classes-dir "/org/armedbear/j/build"))

(def windows? (str/starts-with? (System/getProperty "os.name") "Windows"))
(def mac?     (str/starts-with? (System/getProperty "os.name") "Mac"))
(def unix?    (not windows?))


;; helpers

(defn- abs-path ^String [path]
  (.getAbsolutePath (b/resolve-path path)))

(defn- join-paths
  "Join entries with the platform separator, for a classpath or a PATH."
  [& paths]
  (str/join File/pathSeparator (flatten paths)))

(defn- stale?
  "True unless target exists and is newer than every one of sources."
  [target & sources]
  (boolean (seq (fs/modified-since target sources))))

(def basis
  "Memoised: several targets want the same basis and resolving is slow.
  :root nil keeps org.clojure/clojure off J's classpath."
  (memoize
   (fn [& aliases]
     (b/create-basis (cond-> {:root nil :user nil :project "deps.edn"}
                       (seq aliases) (assoc :aliases (vec aliases)))))))

(defn- lib-jars
  "Every jar the basis resolved to."
  [basis]
  (mapcat :paths (vals (:libs basis))))

(defn- lib-jar
  "The single jar one library resolved to."
  [basis lib]
  (or (first (get-in basis [:libs lib :paths]))
      (throw (ex-info (str "dependency not on the classpath: " lib) {:lib lib}))))

(defn- javac!
  "b/javac shells out to javac but ignores its exit code; make a failed
  compile fail the build."
  [params]
  (let [res (b/javac (update params :javac-opts
                             #(into ["--release" java-version-min "-g"] %)))]
    (when-not (zero? (:exit res 0))
      (throw (ex-info "javac failed" res)))))

(defn- check-javac!
  "b/javac always runs the javac on the PATH, so check that one can target the
  release we need. build.xml probed javac the same way rather than parsing its
  version string."
  []
  (let [{:keys [out err]} (shell {:out :string :err :string} "javac" "-version")]
    (println "javac.version:" (str/trim (str out err))))
  (when-not (zero? (:exit (shell {:out :string :err :string :continue true}
                                 "javac" "--release" java-version-min "-version")))
    (throw (ex-info (format "Java %s or later is required to build J: the javac on the PATH cannot target release %s."
                            java-version-min java-version-min)
                    {}))))

(defn- java!
  "Run a java command built from the basis, with build/bin on the PATH so that
  J can find jpty. Returns the process result rather than throwing."
  [params]
  (apply shell
         {:continue  true
          :extra-env {"PATH" (join-paths (abs-path bin-dir) (System/getenv "PATH"))}}
         (:command-args (b/java-command params))))


;; targets. Each takes and returns the opts map, so they compose with ->

(defn clean "Remove the build and dist trees."
  [opts]
  (b/delete {:path build-dir})
  (b/delete {:path dist-dir})
  opts)

(defn deps "Resolve and show the project dependencies."
  [opts]
  (doseq [[lib {:keys [paths]}] (sort-by key (:libs (basis)))]
    (println (format "%-30s %s" lib (str/join " " paths))))
  opts)

(defn jpty "Compile the jpty helper to build/bin/jpty (unix only, needs gcc)."
  [opts]
  (let [dir    (str src-dir "/jpty")
        target (str bin-dir "/jpty")]
    (when (and unix? (stale? (b/resolve-path target)
                             (b/resolve-path (str dir "/jpty.c"))))
      (if-not (fs/which "gcc")
        (println "gcc not found on the PATH; skipping jpty")
        (do (println "Compiling jpty...")
            (fs/create-dirs (b/resolve-path bin-dir))
            ;; gcc runs in dir, so the output path has to be absolute
            (shell {:dir dir} "gcc" "-Wall" "-O2" "jpty.c" "-o" (abs-path target))))))
  opts)

(defn build "Compile the J sources to build/classes."
  [opts]
  (check-javac!)
  (jpty opts)
  (javac! {:src-dirs [src-dir] :class-dir classes-dir :basis (basis)})
  opts)

(defn stamp "Write the version/build resources read by org.armedbear.j.Version."
  [opts]
  (let [fmt  (DateTimeFormatter/ofPattern "EEE MMM dd yyyy HH:mm:ss zzz" Locale/US)
        time (.format (ZonedDateTime/now) fmt)
        host (try (str/trim (:out (shell {:out :string} "hostname")))
                  (catch Exception _ ""))]
    (b/write-file {:path version-path :string (str j-version "\n")})
    ;; Version reads build time, host name then revision. build.xml leaves the
    ;; revision line off, so do the same and the two files match.
    (b/write-file {:path build-path :string (str time "\n" host "\n")})
    (assoc opts :buildtime time)))

(defn jar "Build and package build/j.jar."
  [opts]
  (let [{:keys [buildtime] :as opts} (-> opts build stamp)]
    ;; the resources that ship in the jar alongside the classes
    (b/copy-dir {:src-dirs [src-dir] :target-dir classes-dir
                 :include "**/*.{keywords,png,svg}"})
    (b/jar {:class-dir classes-dir
            :jar-file  jar-file
            :main      'Main
            ;; build.xml scoped the Implementation-* attributes to an
            ;; org/armedbear/j manifest section, which tools.build cannot
            ;; write. Nothing reads them: Version reads the files stamp wrote.
            :manifest  {"Implementation-Title"   "ArmedBear J"
                        "Implementation-Version" j-version
                        "Implementation-Build"   buildtime}})
    (println "wrote" (abs-path jar-file))
    opts))


;; running

(def ^:private debug-opts
  ["-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005"])

(def ^:private j-args
  ["--debug" "--force-new-instance" "--no-session" "--no-server" "--no-restore"])

(declare extensions)

(defn run "Run J from build/classes, with a debugger listening on port 5005."
  [opts]
  ;; Extensions are found relative to the code source, so build/classes finds
  ;; build/lib/extensions with no -D override needed.
  (extensions opts)
  (java! {:basis     (basis)
          :cp        [(abs-path classes-dir)]
          :java-opts debug-opts
          :main      'Main
          :main-args j-args})
  opts)

(defn run-core "Run J from build/classes with no extension loaded."
  [opts]
  (build opts)
  (java! {:basis     (basis)
          :cp        [(abs-path classes-dir)]
          :java-opts debug-opts
          :main      'Main
          :main-args (cons "--no-extensions" j-args)})
  opts)

(defn run-swingexplorer "Run J under SwingExplorer."
  [opts]
  (build opts)
  (let [basis (basis :swingexplorer)
        agent (lib-jar basis 'org.swingexplorer/swingexplorer-agent)]
    (java! {:basis     basis
            :cp        [(abs-path classes-dir)]
            :java-opts (conj debug-opts (str "-javaagent:" agent))
            :main      'org.swingexplorer.Launcher
            :main-args (cons "Main" j-args)}))
  opts)


;; tests

(defn- test-classes
  "The class names of every test under a source root. Only *Test, so that
  fixtures and helpers can live beside the tests that use them."
  [dir]
  (let [root (fs/path (b/resolve-path dir))]
    (when (fs/exists? root)
      (->> (fs/glob root "**Test.java")
           (map #(-> (str (fs/relativize root %))
                     (str/replace #"\.java$" "")
                     (str/replace File/separator ".")))
           sort
           seq))))

(defn test "Build and run the unit tests."
  [opts]
  (build opts)
  (if-let [classes (test-classes test-src-dir)]
    (let [basis (basis :test)]
      ;; b/javac derives its classpath from the basis libs and :class-dir
      ;; only, leaving out the J classes the tests compile against; a
      ;; -classpath in :javac-opts comes last and wins.
      (javac! {:src-dirs   [test-src-dir]
               :class-dir  test-dir
               :basis      basis
               :javac-opts ["-classpath" (join-paths (abs-path classes-dir)
                                                     (abs-path test-dir)
                                                     (lib-jars basis))]})
      ;; Headless on purpose: a unit test must not depend on a display, and
      ;; must not open a window on a machine that has one.
      (when-not (zero? (:exit (java! {:basis     basis
                                      :cp        [(abs-path classes-dir)
                                                  (abs-path test-dir)]
                                      :java-opts ["-Djava.awt.headless=true"]
                                      :main      'org.junit.runner.JUnitCore
                                      :main-args classes})))
        (throw (ex-info "unit tests failed" {}))))
    (println "no tests found under" test-src-dir))
  opts)


;; extensions

(defn- extension-names
  "Every directory under extensions/ that carries a deps.edn."
  []
  (let [root (fs/path (b/resolve-path extensions-dir))]
    (when (fs/exists? root)
      (->> (fs/list-dir root)
           (filter #(fs/exists? (fs/path % "deps.edn")))
           (map #(str (fs/file-name %)))
           sort
           seq))))

(defn- extension-basis
  "An extension's own dependencies, resolved from its own deps.edn. Nothing of
  core's is in here: an extension may carry a library core has never heard of,
  or a different version of one it has."
  [name & aliases]
  (b/create-basis (cond-> {:root nil :user nil
                           :project (str extensions-dir "/" name "/deps.edn")}
                    (seq aliases) (assoc :aliases (vec aliases)))))

(defn- extension-paths [name]
  {:src      (str extensions-dir "/" name "/src")
   :test-src (str extensions-dir "/" name "/test/src")
   :classes  (str build-dir "/extensions/" name)
   :test     (str extensions-test-dir "/" name)
   :lib      (str extensions-lib-dir "/" name)
   :jar      (str extensions-lib-dir "/" name "/j-" name ".jar")})

(defn extensions "Compile and package the extensions under extensions/."
  [opts]
  (build opts)
  (if-let [names (extension-names)]
    (doseq [name names]
      (let [{:keys [src classes lib jar]} (extension-paths name)
            basis (extension-basis name)]
        (println "Compiling extension" name "...")
        ;; As in the test target: b/javac builds its classpath from the basis
        ;; alone, so core's classes have to come in through :javac-opts.
        (javac! {:src-dirs   [src]
                 :class-dir  classes
                 :basis      basis
                 :javac-opts ["-classpath" (join-paths (abs-path classes-dir)
                                                       (abs-path classes)
                                                       (lib-jars basis))]})
        ;; META-INF/services is how ServiceLoader finds the extension at all.
        (b/copy-dir {:src-dirs [src] :target-dir classes
                     :include "META-INF/**"})
        (b/copy-dir {:src-dirs [src] :target-dir classes
                     :include "**/*.{lisp,keywords,png,svg}"})
        (b/delete {:path lib})
        (b/jar {:class-dir classes :jar-file jar})
        ;; The libraries it brings with it, beside its own jar: one loader per
        ;; extension directory picks up every jar in it.
        (doseq [dep (lib-jars basis)]
          (b/copy-file {:src dep :target (str lib "/" (fs/file-name dep))}))
        (println "wrote" (abs-path jar))))
    (println "no extensions found under" extensions-dir))
  opts)

(declare test)

(defn test-extensions "Build and run the extensions' unit tests."
  [opts]
  (extensions opts)
  (test opts)
  (doseq [name (extension-names)]
    (let [{:keys [test-src classes test]} (extension-paths name)]
      (if-let [tests (test-classes test-src)]
        (let [basis (extension-basis name :test)
              cp    [(abs-path classes-dir) (abs-path classes) (abs-path test)]]
          (javac! {:src-dirs   [test-src]
                   :class-dir  test
                   :basis      basis
                   :javac-opts ["-classpath" (join-paths cp (lib-jars basis))]})
          (when-not (zero? (:exit (java! {:basis     basis
                                          :cp        cp
                                          :main      'org.junit.runner.JUnitCore
                                          :main-args tests})))
            (throw (ex-info (str name " extension tests failed") {:extension name}))))
        (println "no tests found under" test-src))))
  opts)

(defn check-core
  "Assert that core carries no ABCL.

  The executable statement of the goal: a raw byte search catches constant-pool
  type references and reflective Class.forName strings alike, which a classpath
  check would not."
  [opts]
  (build opts)
  (let [root    (b/resolve-path classes-dir)
        tainted (->> (fs/glob root "**.class")
                     (filter #(str/includes? (slurp (fs/file %) :encoding "ISO-8859-1")
                                             "org/armedbear/lisp"))
                     (map #(str (fs/relativize (fs/path root) %)))
                     sort)
        lisp    (->> (fs/glob root "**.lisp")
                     (map #(str (fs/relativize (fs/path root) %)))
                     sort)]
    (when (seq tainted)
      (println "classes referencing ABCL:")
      (doseq [c tainted] (println " " c)))
    (when (seq lisp)
      (println "Lisp resources in core:")
      (doseq [l lisp] (println " " l)))
    (when (or (seq tainted) (seq lisp))
      (throw (ex-info "core is not free of ABCL"
                      {:classes tainted :resources lisp})))
    (println "core is clean:" (count (fs/glob root "**.class")) "classes, no ABCL"))
  opts)


;; formatting

(def ^:private fmt-paths
  "The clj/edn files in the project: the build program and its manifests.
  :hidden so that .cljfmt.edn is formatted like everything else."
  (delay (->> (fs/glob "." "*.{clj,cljc,cljs,edn}" {:hidden true})
              (map str) sort vec)))

(defn- cljfmt
  "cljfmt's console reporter exits non-zero when files are unformatted (1) or
  fail to parse (2), which is what we want from a build target."
  [op]
  ((requiring-resolve (symbol "cljfmt.tool" (name op))) {:paths @fmt-paths}))

(defn fmt "Reformat the clj/edn files in place with cljfmt."
  [opts]
  (cljfmt :fix)
  opts)

(defn fmt-check "Check the clj/edn files are formatted, changing nothing."
  [opts]
  (cljfmt :check)
  opts)


;; distributions

(defn dist-stage "Lay out the distribution tree under dist/j-<version>."
  [opts]
  ;; An ordinary install still has Lisp: the extensions are built into lib/,
  ;; which is copied below.
  (let [opts (-> opts jar extensions)]
    (b/delete {:path stage-dir})
    (b/copy-file {:src jar-file :target (str stage-dir "/j.jar")})
    (b/copy-file {:src "COPYING" :target (str stage-dir "/COPYING")})
    (b/copy-dir {:src-dirs ["doc"] :target-dir (str stage-dir "/doc")
                 :include "*.{html,css}"})
    (doseq [[src dst] [["themes" "themes"] ["examples" "examples"] [lib-dir "lib"]]]
      (b/copy-dir {:src-dirs [src] :target-dir (str stage-dir "/" dst)}))
    ;; An extension's examples are about the extension, so they ship under a
    ;; directory of its own rather than mixed in with core's.
    (doseq [name (extension-names)
            :let [src (str extensions-dir "/" name "/examples")]
            :when (fs/exists? (b/resolve-path src))]
      (b/copy-dir {:src-dirs [src]
                   :target-dir (str stage-dir "/examples/" name)}))
    (when (fs/exists? (b/resolve-path bin-dir))
      (b/copy-dir {:src-dirs [bin-dir] :target-dir (str stage-dir "/bin")})
      (doseq [f (fs/list-dir (b/resolve-path (str stage-dir "/bin")))]
        (fs/set-posix-file-permissions f "rwxr-xr-x")))
    opts))

(defn dist-bin "Create the binary distribution archives in dist/."
  [opts]
  (let [opts (dist-stage opts)
        base (str "j-" j-version "-bin")]
    (doseq [ext ["zip" "tar.gz"]]
      (b/delete {:path (str dist-dir "/" base "." ext)}))
    (b/zip {:src-dirs [stage-dir] :zip-file (str dist-dir "/" base ".zip")})
    (shell {:dir dist-dir}
           "tar" "-czf" (str base ".tar.gz") "-C" (str "j-" j-version) ".")
    (println "wrote" (abs-path (str dist-dir "/" base)) "{.zip,.tar.gz}")
    opts))

(defn dist-src "Create the source distribution archives in dist/ (needs git)."
  [opts]
  (let [base (str "j-" j-version "-src")]
    (fs/create-dirs (b/resolve-path dist-dir))
    (doseq [ext ["zip" "tar.gz"]]
      (shell "git" "archive"
             (str "--output=" (abs-path (str dist-dir "/" base "." ext)))
             (str "--prefix=" base "/")
             "HEAD")))
  opts)

(defn- jpackage
  "Build a native installer of the staged tree."
  [type icon opts]
  (let [opts (dist-stage opts)
        tmp  (str dist-dir "/" type "-tmp")]
    (b/delete {:path tmp})
    (fs/create-dirs (b/resolve-path tmp))
    (shell "jpackage" "--verbose"
           "--temp"         (abs-path tmp)
           "--name"         "J"
           "--input"        (abs-path stage-dir)
           "--main-jar"     "j.jar"
           "--dest"         (abs-path dist-dir)
           "--type"         type
           "--app-version"  j-version
           "--description"  "ArmedBear J Editor"
           "--icon"         (str src-dir "/org/armedbear/j/images/icons/" icon)
           "--java-options" "-Xms512m"
           "--java-options" "-Xmx2048m")
    opts))

(defn dist-mac "Build the macOS .dmg." [opts] (jpackage "dmg" "j.icns" opts))

(defn dist-win "Build the Windows .exe." [opts] (jpackage "exe" "j.ico" opts))

(defn dist "Create the binary and source distributions in dist/."
  [opts]
  (-> opts
      dist-bin
      (cond-> mac? dist-mac
              windows? dist-win)
      dist-src))
