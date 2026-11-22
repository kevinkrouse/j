(ns org.armedbear.j.mermaid.mmdc
  "The mmdc backend: the official @mermaid-js/mermaid-cli. Highest fidelity --
  it is the same renderer GitHub uses -- but needs Node and a Chromium under
  Puppeteer, and a cold render is ~2-3s. It is the fallback for users who have
  it and want exact fidelity; the native backend is the fast default.

  The CLI contract (mermaid-cli 11/12):

    mmdc -i IN -o OUT.png -t THEME -b TRANSPARENT -c CONFIG.json

  reads a .mmd file, writes a PNG. Theme is \"default\"/\"dark\"/etc.
  -b transparent keeps the editor's background showing through. A config
  file forces htmlLabels:false so labels are SVG text, matching every other
  non-browser renderer and the plan's trap note. Errors exit non-zero with a
  message on stderr."
  (:import [java.io File]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(defn- run
  "Run argv, capturing stdout+stderr to a temp file. Returns [exit-code text]."
  [^"[Ljava.lang.String;" argv]
  (let [err-file (File/createTempFile "mmdc-err" ".txt")]
    (try
      (let [p (.start (doto (ProcessBuilder. argv)
                        (.redirectError err-file)
                        (.redirectOutput ProcessBuilder$Redirect/DISCARD)))]
        (.waitFor p)
        [(.exitValue p) (slurp err-file)])
      (finally
        (.delete err-file)))))

(defn render-png
  "Render the .mmd at in-file to out-file (a PNG) with mmdc at binary.
  theme is \"dark\" or \"default\". Returns out-file on success; throws
  RuntimeException carrying the renderer's message otherwise."
  [^String in-file ^String out-file ^String theme ^String binary]
  ;; A config file that turns off HTML labels, so the PNG's text is real
  ;; SVG text and matches the native backend and GitHub.
  (let [cfg (File/createTempFile "mmdc-cfg" ".json")]
    (try
      (spit cfg "{\"htmlLabels\": false}")
      (let [[code err] (run (into-array String
                                        [(or binary "mmdc")
                                         "-i" in-file "-o" out-file
                                         "-t" theme
                                         "-b" "transparent"
                                         "-c" (.getAbsolutePath cfg)]))]
        (if (zero? code)
          out-file
          (throw (RuntimeException. (str "mmdc: " (or err "no output"))))))
      (finally
        (.delete cfg)))))

(defn available?
  "Whether the mmdc binary at binary (or \"mmdc\" on PATH) can run. Note this
  only checks the launcher; a missing Chromium surfaces later as a render
  error, which the handler shows as a placeholder."
  [^String binary]
  (try
    (let [p (.start (doto (ProcessBuilder. (into-array String [(or binary "mmdc") "--version"]))
                      (.redirectErrorStream true)))]
      (.waitFor p)
      (zero? (.exitValue p)))
    (catch java.io.IOException _ false)))
