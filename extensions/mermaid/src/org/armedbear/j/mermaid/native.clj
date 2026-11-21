(ns org.armedbear.j.mermaid.native
  "The native backend: mmdr (https://github.com/1jehuang/mermaid-rs-renderer),
  a pure-Rust Mermaid renderer. No browser, no Node. Renders PNG directly in
  a few milliseconds, so it is the fast path.

  The CLI contract (verified against mmdr 0.3.1):

    mmdr -i - -o OUT -e png -t THEME

  reads the diagram on stdin, writes a PNG to OUT, and exits 1 with a message
  on stderr for bad syntax. Theme is a built-in preset: \"default\" or \"dark\".
  Errors are captured to a temp file rather than a pipe, so a large stderr
  cannot deadlock against the stdin we are still writing."
  (:import [java.io File]
           [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(defn- run
  "Run argv, feeding body to stdin, capturing stdout+stderr to a temp file.
  Returns [exit-code error-text]. The error text is nil on success."
  [^"[Ljava.lang.String;" argv body]
  (let [err-file (File/createTempFile "mmdr-err" ".txt")]
    (try
      (let [pb (ProcessBuilder. argv)
            _ (.redirectError pb err-file)
            _ (.redirectOutput pb ProcessBuilder$Redirect/DISCARD)
            p (.start pb)]
        ;; Write the body and close stdin so the renderer stops waiting.
        (with-open [stdin (.getOutputStream p)]
          (.write stdin (.getBytes ^String body "UTF-8")))
        (.waitFor p)
        [(.exitValue p) (slurp err-file)])
      (finally
        (.delete err-file)))))

(defn render-png
  "Render body to out-file (a PNG) with mmdr at binary (a name on PATH or an
  absolute path). theme is \"dark\" or \"default\". Returns out-file on
  success; throws RuntimeException carrying the renderer's message otherwise."
  [^String body ^String theme ^String out-file ^String binary]
  (let [[code err] (run (into-array String
                                    [(or binary "mmdr")
                                     "-i" "-" "-o" out-file "-e" "png" "-t" theme])
                        body)]
    (if (zero? code)
      out-file
      (throw (RuntimeException. (str "mmdr: " (or err "no output")))))))

(defn available?
  "Whether the mmdr binary at binary (or \"mmdr\" on PATH) can run."
  [^String binary]
  (try
    (let [pb (ProcessBuilder. (into-array String [(or binary "mmdr") "--version"]))
          _ (.redirectErrorStream pb true)
          p (.start pb)]
      (.waitFor p)
      (zero? (.exitValue p)))
    (catch java.io.IOException _ false)))
