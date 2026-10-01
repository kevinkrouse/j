#!/usr/bin/env bb

(ns vim-conformance
  "Generate j's modal-editing conformance corpus from CodeMirror's vim_test.js.

  Reads the upstream test file and writes the cases it can translate to
  test/conformance/vim/codemirror.conf, which VimConformanceTest replays
  through EditorHarness.

  Only the mechanically translatable shape is handled: a linear body of
  setCursor / doKeys / getValue / assertCursorAt. Anything else -- ex commands,
  register inspection, options, DOM measurement -- is written to skipped.txt
  along with the statement that stopped it, so the gap stays visible instead of
  quietly shrinking the corpus.

  Upstream is MIT licensed (CodeMirror, Marijn Haverbeke and others). It is not
  vendored here; fetch it when you want to regenerate:

    https://github.com/replit/codemirror-vim
      packages/codemirror-vim-core/test/vim_test.js

  The generated corpus IS checked in, so an ordinary build and test run needs
  neither the network nor this script.

  Usage: bb vim-conformance <path-to-vim_test.js> [out-dir]"
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))


;; defaults

(def ^:private default-input "vim_test.js")
(def ^:private default-out-dir "test/conformance/vim")


;; JavaScript string literals

(defn- unescape-js
  "Decodes the body of a JS string literal."
  [s]
  (let [n (count s)]
    (loop [i 0
           sb (StringBuilder.)]
      (if (>= i n)
        (str sb)
        (let [c (.charAt s i)]
          (if-not (and (= c \\) (< (inc i) n))
            (recur (inc i) (.append sb c))
            ;; An escape contributes one character and consumes `width` of them.
            (let [d (.charAt s (inc i))
                  [ch width] (case d
                               \n [\newline 2]
                               \t [\tab 2]
                               \r [\return 2]
                               \0 [(char 0) 2]
                               \u [(char (Integer/parseInt (subs s (+ i 2) (+ i 6)) 16)) 6]
                               \x [(char (Integer/parseInt (subs s (+ i 2) (+ i 4)) 16)) 4]
                               [d 2])]
              (recur (+ i width) (.append sb ch)))))))))

(def ^:private string-literal
  "A single-quoted JS string, capturing its body."
  #"'((?:[^'\\]|\\.)*)'")

(defn- js-strings
  "Every single-quoted string literal in s, decoded, in order."
  [s]
  (map (comp unescape-js second) (re-seq string-literal s)))


(def ^:private key-names
  "CodeMirror key names in vim notation. Upstream's typeKey presses a doKeys
  argument that is exactly one of these as that key, and types any other as
  text."
  {"Backspace" "<BS>", "Delete" "<Del>", "Tab" "<Tab>",
   "Return" "<CR>", "Enter" "<CR>", "Escape" "<Esc>",
   "Up" "<Up>", "Down" "<Down>", "Left" "<Left>", "Right" "<Right>",
   "ArrowUp" "<Up>", "ArrowDown" "<Down>",
   "ArrowLeft" "<Left>", "ArrowRight" "<Right>",
   "Home" "<Home>", "End" "<End>", "PageUp" "<PageUp>", "PageDown" "<PageDown>",
   "Insert" "<Insert>"})


;; .conf output

(defn- quoted
  "Encodes a string so it fits on one line of a .conf file."
  [s]
  ;; Backslash first: it is the escape character, so escaping it after
  ;; introducing the others would double their backslashes.
  (str \" (-> s
              (str/replace "\\" "\\\\")
              (str/replace "\"" "\\\"")
              (str/replace "\n" "\\n")
              (str/replace "\t" "\\t")
              (str/replace "\r" "\\r"))
       \"))

(defn- conf-line
  "One directive line, e.g. (conf-line \"cursor\" 0 1) => \"cursor 0 1\"."
  [& parts]
  (str/join " " parts))


;; reading vim_test.js

(defn- blocks
  "Splits the file into top-level testVim(...) blocks.

  Top-level tests start in column 1, so a block runs from one such line to the
  next. That is cruder than matching parens but immune to the regex literals
  and nested functions in the bodies."
  [src]
  (let [lines (vec (str/split-lines src))
        starts (keep-indexed (fn [i line]
                               (when (str/starts-with? line "testVim(") i))
                             lines)]
    (map (fn [[from to]]
           (str/join "\n" (subvec lines from (or to (count lines)))))
         (partition 2 1 (concat starts [nil])))))

(defn- block-name
  [block]
  (let [[_ single double] (re-find #"^testVim\(\s*(?:'((?:[^'\\]|\\.)*)'|\"((?:[^\"\\]|\\.)*)\")" block)]
    (or single double)))

(defn- block-value
  "The test's own initial document, from its trailing options object: a string,
  {:var name} when it names a variable, or nil when it relies on the file's
  shared fixture."
  [block]
  (when-let [[_ value var-name]
             (re-find #"\}\s*,\s*\{[^}]*?value\s*:\s*(?:'((?:[^'\\]|\\.)*)'|([A-Za-z_$][\w$]*))" block)]
    (if value (unescape-js value) {:var var-name})))

(defn- join-continuations
  "Rejoins a call that upstream split over several lines into one statement."
  [lines]
  (reduce (fn [acc line]
            (if (and (seq acc) (not (str/ends-with? (peek acc) ";")))
              (conj (pop acc) (str (peek acc) " " line))
              (conj acc line)))
          []
          lines))

(defn- body
  "The statement lines of the test function, comments and blanks dropped."
  [block]
  (let [lines (str/split-lines (subs block (inc (str/index-of block "{"))))
        ;; the body ends at the line that closes the function
        end (or (first (keep-indexed (fn [i line]
                                       (when (re-find #"^\}\s*(,|\))" line) i))
                                     lines))
                (count lines))]
    (->> (take end lines)
         (map str/trim)
         (remove str/blank?)
         (remove #(str/starts-with? % "//"))
         (join-continuations))))

(defn- string-var
  "The value of a top-level `var name = '' + '...';`, or nil. `code` is the
  shared fixture, used by tests that declare no value: of their own. Assumes
  the value holds no semicolon, which is true of the upstream file and would
  show up as a truncated document if it stopped being true."
  [src var-name]
  (when-let [[_ literals] (re-find (re-pattern (str "(?m)^var "
                                                     (java.util.regex.Pattern/quote var-name)
                                                     "\\s*=([^;]*);"))
                                   src)]
    (apply str (js-strings literals))))


;; translation

(defn- directive
  "A translation result carrying one .conf directive."
  [& parts]
  {:steps [(apply conf-line parts)]})

(def ^:private rules
  "Ordered [pattern handler] pairs, one per statement shape understood here.

  The handler receives the match vector and the case's variable bindings, and
  returns a map with :steps (directives to emit) and/or :vars (new bindings) --
  or nil to say \"this shape is recognised but this instance cannot be
  translated\", which skips the whole case.

  The first matching pattern wins. No two patterns match the same statement, so
  the order is for reading rather than for precedence."
  [;; var curStart = makeCursor(0, 1);
   [#"^var\s+(\w+)\s*=\s*(?:makeCursor|Pos)\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*;$"
    (fn [[_ v line ch] vars] {:vars (assoc vars v [line ch])})]

   ;; cm.setCursor(0, 1);
   [#"^cm\.setCursor\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*;$"
    (fn [[_ line ch] _] (directive "cursor" line ch))]

   ;; cm.setCursor(makeCursor(0, 1));
   [#"^cm\.setCursor\(\s*(?:makeCursor|Pos)\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*\)\s*;$"
    (fn [[_ line ch] _] (directive "cursor" line ch))]

   ;; cm.setCursor(curStart);
   [#"^cm\.setCursor\((\w+)\)\s*;$"
    (fn [[_ v] vars]
      (when-let [[line ch] (vars v)] (directive "cursor" line ch)))]

   ;; helpers.doKeys('d', 'w');
   [#"^helpers\.doKeys\((.*)\)\s*;$"
    (fn [[_ args] _]
      (let [keys (apply str (map #(key-names % %) (js-strings args)))]
        (when-not (str/blank? keys) (directive "keys" (quoted keys)))))]

   ;; helpers.doEx('s/a/b');
   [#"^helpers\.doEx\((.*)\)\s*;$"
    (fn [[_ args] _]
      (let [line (apply str (js-strings args))]
        (when-not (str/blank? line) (directive "ex" (quoted line)))))]

   ;; helpers.assertCursorAt(0, 1);
   [#"^helpers\.assertCursorAt\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*;$"
    (fn [[_ line ch] _] (directive "expect-cursor" line ch))]

   ;; eq('expected', cm.getValue());  -- and the same with the arguments swapped
   [#"^eq\('((?:[^'\\]|\\.)*)'\s*,\s*cm\.getValue\(\)\)\s*;$"
    (fn [[_ value] _] (directive "expect-value" (quoted (unescape-js value))))]

   [#"^eq\(cm\.getValue\(\)\s*,\s*'((?:[^'\\]|\\.)*)'\)\s*;$"
    (fn [[_ value] _] (directive "expect-value" (quoted (unescape-js value))))]

   ;; eqCursorPos(curStart, cm.getCursor());
   [#"^eqCursorPos\((\w+)\s*,\s*cm\.getCursor\(\)\)\s*;$"
    (fn [[_ v] vars]
      (when-let [[line ch] (vars v)] (directive "expect-cursor" line ch)))]

   ;; eqCursorPos(new Pos(0, 1), cm.getCursor());
   [#"^eqCursorPos\((?:new\s+)?(?:makeCursor|Pos)\(\s*(\d+)\s*,\s*(\d+)\s*\)\s*,\s*cm\.getCursor\(\)\)\s*;$"
    (fn [[_ line ch] _] (directive "expect-cursor" line ch))]

   ;; eq(3, cm.getCursor().ch);  /  eq(3, cm.getCursor().line);
   [#"^eq\(\s*(\d+)\s*,\s*cm\.getCursor\(\)\.ch\)\s*;$"
    (fn [[_ ch] _] (directive "expect-offset" ch))]

   [#"^eq\(\s*(\d+)\s*,\s*cm\.getCursor\(\)\.line\)\s*;$"
    (fn [[_ line] _] (directive "expect-line" line))]])

(def ^:private droppable
  "Assertions this corpus cannot express yet, but whose presence does not stop
  the rest of a test from being meaningful.

  They are read-only: dropping one changes nothing about what the keys do, it
  only checks less than upstream does. Each dropped statement is counted and
  noted in the generated case, so the shortfall is visible rather than silent."
  [#"^var\s+register\s*=\s*helpers\.getRegisterController\(\)"
   #"^is\("
   #"register"
   #"^eq\(.*\bvim\."])

(defn- droppable?
  [line]
  (boolean (some #(re-find % line) droppable)))

(defn- step
  "Translates one statement, or returns nil if it is outside the shape this
  generator handles."
  [line vars]
  (when-let [[pattern handler] (first (filter (fn [[re _]] (re-find re line))
                                              rules))]
    (when-let [result (handler (re-find pattern line) vars)]
      (merge {:vars vars :steps []} result))))

(defn- note-dropped
  "Records in the case itself how much less it checks than upstream does."
  [steps dropped]
  (if (pos? dropped)
    (into [(str "# " dropped
                " upstream assertion(s) not ported: register or mode state")]
          steps)
    steps))

(defn- translate
  "Turns one block into {:name .. :steps [..]} or {:name .. :skip <statement>}."
  [block src]
  (let [test-name (block-name block)
        own (block-value block)
        value (cond (string? own) own
                    own (string-var src (:var own))
                    :else (string-var src "code"))]
    (if (nil? value)
      {:name test-name :skip (if own
                               (str "value: " (:var own) " is not a string variable")
                               "no initial document")}
      (let [start {:vars {} :steps [(conf-line "value" (quoted value))] :dropped 0}
            result (reduce (fn [acc line]
                             (if-let [{:keys [vars steps]} (step line (:vars acc))]
                               (-> acc
                                   (assoc :vars vars)
                                   (update :steps into steps))
                               (if (droppable? line)
                                 (update acc :dropped inc)
                                 (reduced {:skip line}))))
                           start
                           (body block))]
        (if-let [skip (:skip result)]
          {:name test-name :skip skip}
          {:name test-name
           :steps (note-dropped (:steps result) (:dropped result))})))))


;; output files

(def ^:private conf-header
  "# Generated by tools/vim-conformance.clj from CodeMirror's vim_test.js.
# Do not edit: regenerate instead.
#
# CodeMirror is MIT licensed, copyright Marijn Haverbeke and others.
# Cursor coordinates are zero based. Strings use JSON-style escapes.
# A document is read the way CodeMirror reads it: '\\n' separates two
# lines, so a trailing one leaves an empty last line.

")

(def ^:private skipped-header
  "# Cases in vim_test.js that tools/vim-conformance.clj cannot translate.
# Each line is the test name and the statement that stopped it.

")

(defn- render-cases
  [cases]
  (str/join "\n" (for [{:keys [name steps]} cases]
                   (str "[" name "]\n" (str/join "\n" steps) "\n"))))

(defn- render-skipped
  [cases]
  (str (str/join "\n" (for [{:keys [name skip]} cases]
                        (format "%-52s %s" name skip)))
       "\n"))


;; entry point

(defn- die
  [& lines]
  (binding [*out* *err*]
    (run! println lines))
  (System/exit 1))

(defn- asserts-something?
  "A case that presses keys but checks nothing proves nothing."
  [{:keys [steps]}]
  (boolean (some #(str/starts-with? % "expect-") steps)))

(defn -main
  [& args]
  (let [input (or (first args) default-input)
        out-dir (or (second args) default-out-dir)]
    (when-not (fs/regular-file? input)
      (die (str "vim-conformance: no such file: " input)
           "Usage: bb vim-conformance <path-to-vim_test.js> [out-dir]"))
    (let [src (slurp input)
          results (doall (map #(let [r (translate % src)]
                                 ;; Nothing to check is not a translation: say so
                                 ;; in skipped.txt rather than drop the case.
                                 (if (and (:steps r) (not (asserts-something? r)))
                                   (assoc r :skip "no translatable assertion")
                                   r))
                              (blocks src)))
          translated (filter (every-pred :steps asserts-something?) results)
          skipped (filter :skip results)]
      (when (empty? results)
        (die (str "vim-conformance: no top-level testVim(...) blocks in " input)))
      (fs/create-dirs out-dir)
      (spit (fs/file out-dir "codemirror.conf")
            (str conf-header (render-cases translated)))
      (spit (fs/file out-dir "skipped.txt")
            (str skipped-header (render-skipped skipped)))
      (println (format "%d cases written, %d skipped, %d total"
                       (count translated) (count skipped) (count results)))
      (println (str "  " (fs/path out-dir "codemirror.conf")))
      (println (str "  " (fs/path out-dir "skipped.txt"))))))

(apply -main *command-line-args*)
