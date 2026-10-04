(ns build
  "Build program for the Armed Bear J Editor, driven by `bb`.

  `bb tasks` lists the targets."
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

(defn- javac-against!
  "javac! with cp ahead of the basis libs. b/javac builds its classpath from
  the basis and :class-dir only; a -classpath in :javac-opts comes last and
  wins."
  [params cp]
  (javac! (assoc params :javac-opts
                 ["-classpath" (join-paths cp (lib-jars (:basis params)))])))

(defn- check-javac!
  "b/javac always runs the javac on the PATH, so check that one can target the
  release we need, rather than parsing its version string."
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

(defn- junit!
  "Run test classes on the JUnit Platform, headless: a unit test must not
  depend on a display, or open a window on a machine that has one."
  [basis cp classes what]
  (when-not (zero? (:exit (java! {:basis     basis
                                  :cp        cp
                                  :java-opts ["-Djava.awt.headless=true"]
                                  :main      'org.junit.platform.console.ConsoleLauncher
                                  ;; On the command line, not in a properties file, so
                                  ;; an extension's own junit-platform.properties is
                                  ;; the only one.
                                  :main-args (into ["execute" "--disable-banner"
                                                    "--details=summary"
                                                    "--config=junit.jupiter.extensions.autodetection.enabled=true"]
                                                   (mapcat #(vector "--select-class" %))
                                                   classes)})))
    (throw (ex-info (str what " failed") {}))))


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

(def resource-glob
  "Non-Java files under src/ that belong on the class path beside the classes:
  syntax keyword lists, icons, and the modal editing key map table."
  "**/*.{keywords,png,svg,conf,properties}")

(defn build "Compile the J sources to build/classes."
  [opts]
  (check-javac!)
  (jpty opts)
  (javac! {:src-dirs [src-dir] :class-dir classes-dir :basis (basis)})
  ;; Copied here rather than only when packaging, so that running or testing
  ;; from build/classes finds the same resources the jar would ship.
  (b/copy-dir {:src-dirs [src-dir] :target-dir classes-dir
               :include resource-glob})
  opts)

;; command summaries

(def ^:private command-summaries-file "src/org/armedbear/j/command-summaries.properties")

(defn- html->text [s]
  (-> s
      (str/replace #"<[^>]*>" "")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&nbsp;" " ")
      (str/replace #"&#x([0-9A-Fa-f]+);" #(str (char (Integer/parseInt (second %) 16))))
      (str/replace #"&#(\d+);" #(str (char (Integer/parseInt (second %)))))
      (str/replace "&amp;" "&")
      (str/replace #"\s+" " ")
      str/trim))

(defn- first-sentence [s]
  (let [[_ sentence] (re-find #"^(.*?[.!?])(?:\s|$)" s)]
    (or sentence s)))

(defn command-summaries
  "Write each command's first sentence from doc/commands.html, for the action finder."
  [opts]
  (let [html    (slurp "doc/commands.html")
        ;; An entry may name several commands: <a name="x">x</a>, <a name="y">y</a><dl><dd>
        entries (re-seq #"(?s)((?:<a name=\"\w+\">\w+</a>[,\s]*)+)<dl><dd>(.*?)(?:<b>Default key mapping|<br><br>|</dl>)" html)
        lines   (sort (for [[_ anchors body] entries
                            :let [text (first-sentence (html->text body))]
                            :when (seq text)
                            [_ name] (re-seq #"<a name=\"(\w+)\">" anchors)]
                        (str name "=" (str/replace text "\\" "\\\\"))))]
    (spit command-summaries-file
          (str "# Generated by `bb command-summaries` from doc/commands.html; do not edit.\n"
               (str/join "\n" lines) "\n"))
    (println "wrote" (count lines) "summaries to" command-summaries-file))
  opts)


(defn stamp "Write the version/build resources read by org.armedbear.j.Version."
  [opts]
  (let [fmt  (DateTimeFormatter/ofPattern "EEE MMM dd yyyy HH:mm:ss zzz" Locale/US)
        time (.format (ZonedDateTime/now) fmt)
        host (try (str/trim (:out (shell {:out :string} "hostname")))
                  (catch Exception _ ""))]
    (b/write-file {:path version-path :string (str j-version "\n")})
    ;; Version reads build time, host name, then an optional revision.
    (b/write-file {:path build-path :string (str time "\n" host "\n")})
    (assoc opts :buildtime time)))

(defn jar "Build and package build/j.jar."
  [opts]
  (let [{:keys [buildtime] :as opts} (-> opts build stamp)]
    (b/jar {:class-dir classes-dir
            :jar-file  jar-file
            :main      'Main
            ;; Nothing reads these: Version reads the files stamp wrote.
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

(defn- copy-test-resources!
  "JUnit's configuration and the test extensions it autodetects."
  [src target]
  (b/copy-dir {:src-dirs [src] :target-dir target
               :include "{META-INF/**,junit-platform.properties}"}))

(defn test "Build and run the unit tests."
  [opts]
  (build opts)
  (if-let [classes (test-classes test-src-dir)]
    (let [basis (basis :test)
          cp    [(abs-path classes-dir) (abs-path test-dir)]]
      (javac-against! {:src-dirs [test-src-dir] :class-dir test-dir :basis basis} cp)
      (copy-test-resources! test-src-dir test-dir)
      (junit! basis cp classes "unit tests"))
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
        (javac-against! {:src-dirs [src] :class-dir classes :basis basis}
                        [(abs-path classes-dir) (abs-path classes)])
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
        ;; Core's tests too, for their harness and golden-file checks.
        (let [basis (extension-basis name :test)
              cp    [(abs-path classes-dir) (abs-path classes) (abs-path test) (abs-path test-dir)]]
          (javac-against! {:src-dirs [test-src] :class-dir test :basis basis} cp)
          (copy-test-resources! test-src test)
          (junit! basis cp tests (str name " extension tests")))
        (println "no tests found under" test-src))))
  opts)

(def ^:private extension-packages
  "What lives in an extension, so no core class may name it: as a type
  (slashes) or, for j's own, as a Class.forName string (dots). Dotted
  org.armedbear.lisp is allowed; LispShellBuffer names an external Lisp."
  (let [j ["mail" "vcs/cvs" "vcs/p4" "vcs/darcs" "mode/asm" "mode/autoconf"
           "mode/verilog" "mode/vhdl" "mode/objc" "mode/tcl" "mode/scheme"]]
    (concat ["org/armedbear/lisp"]
            (for [p j] (str "org/armedbear/j/" p "/"))
            (for [p j] (str "org.armedbear.j." (str/replace p "/" ".") ".")))))

(defn check-core
  "Assert that no core class references what an extension provides, and that
  core carries no Lisp resource. A byte search, so it sees string constants
  as well as type references."
  [opts]
  (build opts)
  (let [root    (b/resolve-path classes-dir)
        tainted (->> (fs/glob root "**.class")
                     (keep (fn [f]
                             (let [bytes (slurp (fs/file f) :encoding "ISO-8859-1")]
                               (when-let [pkgs (seq (filter #(str/includes? bytes %)
                                                            extension-packages))]
                                 (str (fs/relativize (fs/path root) f) " -> "
                                      (str/join ", " pkgs))))))
                     sort)
        lisp    (->> (fs/glob root "**.lisp")
                     (map #(str (fs/relativize (fs/path root) %)))
                     sort)]
    (when (seq tainted)
      (println "classes referencing extensions:")
      (doseq [c tainted] (println " " c)))
    (when (seq lisp)
      (println "Lisp resources in core:")
      (doseq [l lisp] (println " " l)))
    (when (or (seq tainted) (seq lisp))
      (throw (ex-info "core references its extensions"
                      {:classes tainted :resources lisp})))
    (println "core is clean:" (count (fs/glob root "**.class")) "classes, no extension code"))
  opts)


;; lint

(def ^:private errorprone-promoted
  "Warning-level checks the code is clean of, made errors so it stays so."
  ["BadInstanceof" "ClassCanBeStatic" "DefaultCharset" "FallThrough" "IntLongMath"
   "JavaTimeDefaultTimeZone" "MissingOverride" "NarrowCalculation" "NarrowingCompoundAssignment"
   "PatternMatchingInstanceof" "StaticAssignmentInConstructor" "StaticQualifiedUsingExpression"
   "StringCaseLocaleUsage" "SynchronizeOnNonFinalField" "ToStringReturnsNull"
   "UnsafeReflectiveConstructionCast" "UnsynchronizedOverridesSynchronized"])

(def ^:private errorprone-opts
  (concat
   ["-XDcompilePolicy=simple" "--should-stop=ifError=FLOW"
    (str/join " " (into ["-Xplugin:ErrorProne" "-XepDisableAllWarnings"]
                        (map #(str "-Xep:" % ":ERROR") errorprone-promoted)))]
   ;; Error Prone runs inside javac and uses its internals.
   (for [p ["api" "file" "main" "model" "parser" "processing" "tree" "util"]]
     (str "-J--add-exports=jdk.compiler/com.sun.tools.javac." p "=ALL-UNNAMED"))
   (for [p ["code" "comp"]]
     (str "-J--add-opens=jdk.compiler/com.sun.tools.javac." p "=ALL-UNNAMED"))))

(defn lint
  "Compile core with -Xlint and Error Prone. Any warning or error fails.
  this-escape is off: Swing components register themselves as listeners in
  their constructors."
  [opts]
  (check-javac!)
  (let [out     (str build-dir "/lint")
        sources (str build-dir "/lint-sources.txt")]
    (b/delete {:path out})
    (fs/create-dirs (b/resolve-path out))
    (spit (b/resolve-path sources)
          (str/join "\n" (map str (fs/glob (b/resolve-path src-dir) "**.java"))))
    (let [{:keys [exit err]}
          (apply shell {:continue true :err :string}
                 "javac" "--release" java-version-min "-d" (abs-path out)
                 "-Xlint:all,-serial,-this-escape" "-Werror" "-Xmaxwarns" "100000"
                 "-processorpath" (join-paths (lib-jars (basis :errorprone)))
                 (concat errorprone-opts [(str "@" (abs-path sources))]))]
      (binding [*out* *err*] (print err) (flush))
      (when-not (zero? exit)
        (throw (ex-info "lint failed" {})))))
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

(def ^:private fmt-base
  "Java files changed since this ref are the ones jfmt formats; the rest keep
  their old style until edited."
  (or (System/getenv "FMT_BASE") "master"))

(defn- git-paths
  "The NUL-separated paths a git command prints; -z keeps them unquoted."
  [& args]
  (remove str/blank? (str/split (:out (apply shell {:out :string} "git" args)) #"\x00")))

(defn- edited-java-files
  "Java files added or changed since fmt-base, committed or not. The golden
  test samples are data, not code."
  []
  (let [{:keys [exit out err]} (shell {:out :string :err :string :continue true}
                                      "git" "merge-base" fmt-base "HEAD")
        base (str/trim out)]
    (when-not (zero? exit)
      (throw (ex-info (str "no merge base with " fmt-base " (set FMT_BASE to another ref): "
                           (str/trim err))
                      {})))
    (->> (concat (git-paths "diff" "--no-ext-diff" "-z" "--name-only" "--diff-filter=AMR" base)
                 (git-paths "ls-files" "-z" "--others" "--exclude-standard"))
         (filter #(str/ends-with? % ".java"))
         (remove #(str/starts-with? % "test/golden/"))
         distinct sort)))

(defn- jfmt
  "Run jfmt's op (list or write) on the edited Java files. Returns the exit
  code. jfmt comes from the nix flake; CI must have it."
  [op]
  (let [files (edited-java-files)]
    (cond
      (empty? files) 0
      (fs/which "jfmt") (:exit (apply shell {:continue true} "jfmt" op
                                      "--config-file=jfmt.xml" "--no-color" files))
      (System/getenv "CI") (throw (ex-info "jfmt is not on the PATH" {}))
      :else (do (println "jfmt is not on the PATH; Java not checked") 0))))

(defn fmt "Reformat clj/edn files and edited Java files in place."
  [opts]
  (when-not (zero? (jfmt "write"))
    (throw (ex-info "jfmt failed" {})))
  (cljfmt :fix)
  opts)

(defn fmt-check "Check clj/edn files and edited Java files are formatted."
  [opts]
  (when-not (zero? (jfmt "list"))
    (throw (ex-info "Java files above need `bb fmt`" {})))
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
