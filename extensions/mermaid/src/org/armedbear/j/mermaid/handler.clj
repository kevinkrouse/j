(ns org.armedbear.j.mermaid.handler
  "The FenceHandler that claims the ```mermaid fence and draws it as a
  picture in place of the source.

  The pipeline, all off the event thread except where it must not be:

  - render (EDT): record the fence, and if its picture is not already
    known, kick a background render. Return promptly.
  - the background task: resolve the backend, render the body to a PNG,
    decode it, cache it by content hash.
  - back on the EDT: if the fence is still live with the same body, stash
    the image and re-register the buffer's REPLACE decorations.
  - renderDone (EDT): the dispatch round is over; drop any fence that was
    not seen this round -- renamed to another language, say, so its line
    survives but no render came for it -- then re-register.

  While a picture is rendering the fence stays revealed (its source shows);
  when the picture lands it collapses in place. A failed render leaves the
  source visible and logs why -- never a blank gap or a crash. The caret
  reveal rule is core's; this handler only decides what is registered.

  State is per buffer, held weakly so a closed buffer and its pictures are
  forgotten. All of it is guarded by one lock; the long render runs outside
  it, and only the short update on landing takes it.

  The static accessors (getLastBody, getRenderCount, reset) are for the
  bridge test, which checks the seam without a display: the handler runs on
  the event thread, but the last body and count are plain statics to poll."
  (:gen-class
   :name org.armedbear.j.mermaid.MermaidHandler
   :implements [org.armedbear.j.extension.FenceHandler]
   :methods [^{:static true} [getLastBody [] String]
             ^{:static true} [getRenderCount [] int]
             ^{:static true} [reset [] void]
             ^{:static true} [toggleMermaid [] void]])
  (:require [org.armedbear.j.mermaid.renderer :as renderer]
            [org.armedbear.j.mermaid.cache :as cache]
            [org.armedbear.j.mermaid.painter :as painter])
  (:import [org.armedbear.j Buffer Display Editor Log DefaultTheme Line VirtualText]
           [org.armedbear.j.mode.markdown MarkdownFences MarkdownMode]
           [org.armedbear.j.util Background]
           [java.awt.image BufferedImage]
           [java.io File]
           [java.util HashSet IdentityHashMap WeakHashMap]
           [java.util.concurrent.atomic AtomicBoolean AtomicInteger AtomicReference]))

(def ^:private OWNER "mermaid")

;; Recorded for the bridge test: the last body the seam delivered, and how
;; many fences it has been asked to render. Plain statics the test polls.
(def ^:private last-body (AtomicReference. nil))
(def ^:private render-count (AtomicInteger. 0))

;; Guard for all the per-buffer state below. The render runs off this lock;
;; only the short record and the short update-on-landing hold it.
(def ^:private lock (Object.))

;; Per-buffer live fences, keyed weakly by buffer. Each value is a State.
(def ^:private ^WeakHashMap states (WeakHashMap.))

(defrecord State [^IdentityHashMap live      ;; anchor Line -> {:first :last :body :editor :image :rendering}
                  ^HashSet seen              ;; anchors seen this dispatch round
                  ^AtomicReference last-sig  ;; signature of the last registered set
                  ^AtomicBoolean enabled])   ;; whether this buffer shows diagrams (toggleMermaid)

(defn- new-state []
  (State. (IdentityHashMap.) (HashSet.) (AtomicReference. "") (AtomicBoolean. true)))

(defn- state-for ^State [^Buffer buffer]
  (or (.get states buffer)
      (let [s (new-state)]
        (.put states buffer s)
        s)))

(defn- theme-for
  "The diagram theme matching the editor's background: \"dark\" or \"default\"."
  [^Editor editor]
  (try
    (let [bg (.getBackgroundColor (.getFormatter editor))]
      (if (DefaultTheme/isDark bg) "dark" "default"))
    (catch Exception _ "default")))

(defn- line-count
  "How many lines the fence spans, first through last inclusive. The painter
  multiplies this by the current char height to get its box height, so the
  box tracks a font-scale change without the handler re-registering: the
  display asks the painter for its height on every layout pass, and a font
  change repaints. At the current scale this equals the sum of the lines'
  own heights, so the reveal/collapse swap stays height-neutral."
  [^Line first ^Line last]
  (loop [l first n 0]
    (if (nil? l)
      n
      (let [n (inc n)]
        (if (identical? l last) n (recur (.next l) n))))))

(defn- flush!
  "Register the buffer's REPLACE decorations for every live fence that has
  a picture or a failure to show. When the buffer's diagrams are toggled
  off, register none, so the source shows. Idempotent: an unchanged set
  does not repaint. Caller holds the lock."
  [^Buffer buffer]
  (let [s (state-for buffer)
        ^IdentityHashMap live (:live s)
        out (java.util.ArrayList.)
        sig (StringBuilder.)]
    (when (.get ^AtomicBoolean (:enabled s))
      (doseq [^java.util.Map$Entry e (.entrySet live)]
        (let [entry (.getValue e)
              ^BufferedImage img (:image entry)]
          (when (or (some? img) (:error entry))
            (let [n (long (line-count (:first entry) (:last entry)))]
              (.add out
                    (VirtualText/replace
                     (:first entry) (:last entry)
                     (if (some? img)
                       (painter/diagram-painter img n (:editor entry))
                       (painter/placeholder-painter ^String (:error entry) n))))
              (.append sig (.lineNumber ^Line (.getKey e)))
              (.append sig ":")
              (.append sig (if (some? img) (System/identityHashCode img) (:error entry)))
              (.append sig ";"))))))
    (let [s2 (.toString sig)]
      (when-not (= s2 (.get (:last-sig s)))
        (.set (:last-sig s) s2)
        (.set (.getVirtualText buffer) OWNER out)))))

(defn- kick!
  "Render body off the EDT, then land the result on the EDT if the fence is
  still live with the same body. The work never throws: a failure comes back
  as {:error msg} and is drawn as a placeholder, so a missing renderer or
  bad syntax is visible, never a blank gap or a crash. The content-hash
  cache is consulted first, so a body already drawn (here or in another
  buffer) skips the render entirely; the cache check is off the EDT so the
  PATH probe never blocks a keystroke."
  [^Buffer buffer first body theme]
  (Background/then
   "mermaid render"
   (fn []
     (try
       (let [backend-name (or (renderer/backend-name) "none")
             k (cache/cache-key body theme backend-name)]
         (if-let [hit (cache/cached k)]
           {:ok hit}
           (let [png (File/createTempFile "mermaid-" ".png")]
             (try
               (renderer/render body theme (.getAbsolutePath png))
               (let [img (cache/read-png (.getAbsolutePath png))]
                 (if img
                   (do (cache/store! k img) {:ok img})
                   {:error "could not read the rendered image"}))
               (finally
                 (.delete png))))))
       (catch Throwable t
         {:error (or (.getMessage t) "render failed")})))
   (fn [result]
     (locking lock
       (let [s (state-for buffer)
             ^IdentityHashMap live (:live s)
             entry (.get live first)]
          ;; The fence may have been pruned or edited while we rendered; only
          ;; land if it is still the same body we were asked to draw.
         (when (and entry (= ^String (:body entry) body))
           (.put live first
                 (assoc entry
                        :image (:ok result)
                        :error (:error result)
                        :rendering false))
           (flush! buffer)))))))

(defn -handles [_ fence-name]
  (= "mermaid" fence-name))

(defn -render [_this ^Editor editor ^Buffer buffer first last body]
  ;; Record for the bridge test that the seam delivered this body.
  (.set last-body body)
  (.incrementAndGet render-count)
  (locking lock
    (let [s (state-for buffer)
          ^IdentityHashMap live (:live s)
          ^HashSet seen (:seen s)
          existing (.get live first)
          same (and existing (= ^String (:body existing) body))]
      (.add seen first)
      ;; Toggled off: the source shows, so do no render work. The fence is
      ;; still recorded as seen (renderDone must not mistake the silence for
      ;; a rename), but nothing is kicked. Toggling back on re-dispatches.
      (when (.get ^AtomicBoolean (:enabled s))
        (cond
          ;; Already drawn this exact body: nothing to do.
          (and same (:image existing)) :done
          ;; A render for this body is already in flight: don't spawn another.
          (and same (:rendering existing)) :inflight
          ;; Tried this body and it failed: leave it revealed, don't retry
          ;; until the body changes.
          (and same (nil? (:image existing)) (not (:rendering existing))) :failed
          ;; New or changed body: record it and render.
          :else
          (let [theme (theme-for editor)]
            (.put live first {:first first :last last :body body
                              :editor editor :image nil :rendering true})
            (kick! buffer first body theme)))))))

(defn -renderDone [_this ^Editor _editor ^Buffer buffer]
  (locking lock
    (let [s (state-for buffer)
          ^IdentityHashMap live (:live s)
          ^HashSet seen (:seen s)]
      ;; Drop fences not claimed this round: renamed away, or deleted.
      (doseq [anchor (into-array Object (.toArray (.keySet live)))]
        (when-not (.contains seen anchor)
          (.remove live anchor)))
      (.clear seen)
      (flush! buffer))))

(defn ^:static -toggleMermaid
  "Flip whether the current buffer shows its mermaid diagrams as pictures.
  On (the default) a rendered fence collapses to its picture; off, the
  source stays visible and no render work is done. Re-dispatches the
  fences so the decorations follow the new flag; the content-hash cache
  makes toggling back on cheap -- a body already drawn re-registers
  without re-rendering."
  []
  (let [editor (Editor/currentEditor)
        buffer (when editor (.getBuffer editor))]
    (when (and buffer (instance? MarkdownMode (.getMode buffer)))
      (locking lock
        (let [s (state-for buffer)
              ^AtomicBoolean en (:enabled s)]
          (.set en (not (.get en)))))
      (MarkdownFences/onActivate editor))))

;; --- bridge-test statics (last-body and render-count are defined above) ---
(defn ^:static -getLastBody []
  (.get last-body))

(defn ^:static -getRenderCount []
  (.get render-count))

(defn ^:static -reset []
  (locking lock
    (.set last-body nil)
    (.set ^AtomicInteger render-count 0)
    (.clear states)
    (cache/clear)))
