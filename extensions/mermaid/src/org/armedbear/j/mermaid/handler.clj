(ns org.armedbear.j.mermaid.handler
  "The FenceHandler that claims the ```mermaid fence and draws it as a
  picture in place of the source.

  The pipeline keeps the event thread free: render records the fence and
  kicks a background render; the background task renders and caches by
  content hash; only the short landing -- stash the image, re-register the
  REPLACE decorations -- runs back on the EDT. A fence not seen by the end
  of a dispatch round was renamed away from mermaid, so renderDone drops it
  rather than leaving a stale picture.

  The caret reveal rule is core's; this handler only decides what is
  registered. State is per buffer, held weakly so a closed buffer and its
  pictures are forgotten, and guarded by one lock -- the long render runs
  outside it. The static accessors are for the bridge test, which checks
  the seam without a display."
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
           [org.armedbear.j.mode.image ImageBuffer]
           [org.armedbear.j.mode.markdown MarkdownFences MarkdownMode]
           [org.armedbear.j.util Background]
           [java.awt.event ActionListener]
           [java.awt.image BufferedImage]
           [java.io File]
           [java.util HashSet IdentityHashMap WeakHashMap]
           [javax.swing JButton JFileChooser JMenu JMenuItem JPopupMenu JRadioButtonMenuItem
            SwingConstants]))

(def ^:private OWNER "mermaid")

(def ^:private SIZE-MODES
  [["scaled to fit" "fit"]
   ["scaled to width" "width"]
   ["original size" "original"]])

;; Plain statics the bridge test polls without a display.
(def ^:private last-body (atom nil))
(def ^:private render-count (atom 0))

;; One lock for all per-buffer state; the long render runs outside it.
(def ^:private lock (Object.))

(def ^:private ^WeakHashMap states (WeakHashMap.))

(defrecord State [^IdentityHashMap live      ;; anchor Line -> {:first :last :body :editor :image :rendering :size-mode}
                  ^IdentityHashMap controls  ;; anchor Line -> the hover controls JButton
                  ^HashSet seen              ;; anchors seen this dispatch round
                  last-sig                   ;; atom: signature of the last registered set
                  enabled])                  ;; atom: whether this buffer shows diagrams (toggleMermaid)

(defn- new-state []
  (State. (IdentityHashMap.) (IdentityHashMap.) (HashSet.) (atom "") (atom true)))

(defn- state-for ^State [^Buffer buffer]
  (.computeIfAbsent states buffer (fn [_] (new-state))))

(defn- theme-for
  "The diagram theme matching the editor's background: \"dark\" or \"default\"."
  [^Editor editor]
  (try
    (let [bg (.getBackgroundColor (.getFormatter editor))]
      (if (DefaultTheme/isDark bg) "dark" "default"))
    (catch Exception _ "default")))

(defn- line-count
  "Lines the fence spans, first through last inclusive. The painter derives
  its box height from this times the current char height, so the box tracks
  a font-scale change without re-registering, and the reveal/collapse swap
  stays height-neutral."
  [^Line first ^Line last]
  (loop [l first n 0]
    (if (nil? l)
      n
      (let [n (inc n)]
        (if (identical? l last) n (recur (.next l) n))))))

(defn- editor-for ^Editor [^Buffer buffer]
  "An editor showing buffer, or null."
  (some (fn [^Editor e] (when (= (.getBuffer e) buffer) e))
        (iterator-seq (.iterator (Editor/getEditorList)))))

(defn- live-entry [^Buffer buffer ^Line anchor]
  "The live fence entry anchored at `anchor` in buffer, or nil. Caller
  holds the lock."
  (.get ^IdentityHashMap (:live (state-for buffer)) anchor))

;; Defined below; the controls' actions call it to re-register after a
;; size-mode change.
(declare flush!)

(defn- update-entry!
  "Apply f to the live entry anchored at `anchor` and re-register, under
  the lock. The menu actions go through this rather than a captured entry so
  the change is atomic with respect to any other mutation."
  [^Buffer buffer ^Line anchor f]
  (locking lock
    (let [^IdentityHashMap live (:live (state-for buffer))
          entry (.get live anchor)]
      (when entry
        (.put live anchor (f entry))
        (flush! buffer)))))

(defn- set-size-mode!
  "Set the fence's size mode, so the painter's new height re-frames the
  display."
  [^Buffer buffer ^Line anchor ^String mode]
  (update-entry! buffer anchor #(assoc % :size-mode mode)))

(defn- flip-pinned!
  "Toggle the fence between pinned (source always shown) and shown."
  [^Buffer buffer ^Line anchor]
  (update-entry! buffer anchor #(update % :pinned not)))

(defn- image-for
  "The rendered image of the fence anchored at `anchor` in buffer, or nil.
  Takes the lock only to read the entry, so the caller can do the heavy
  work (writing a file, opening a frame) outside it."
  [^Buffer buffer ^Line anchor]
  (locking lock
    (when-let [entry (live-entry buffer anchor)]
      (:image entry))))

(defn- open-in-split-window!
  "Open the fence's rendered image in a split window -- j's own image
  viewer, resizable and zoomable, left open while the source stays put.
  The ImageBuffer is made directly from the temp PNG, not through
  Buffer/createBuffer: that path sniffs the file type by magic bytes, and a
  PNG is neither JPEG nor zip, so it falls into the gzip probe and throws."
  [^Buffer buffer ^Line anchor]
  (when-let [^BufferedImage img (image-for buffer anchor)]
    (let [png (File/createTempFile "mermaid-" ".png")]
      (javax.imageio.ImageIO/write img "png" png)
      (when-let [jfile (org.armedbear.j.File/getInstance (.getAbsolutePath png))]
        (when-let [ib (ImageBuffer/createImageBuffer jfile jfile nil)]
          (when-let [ed (editor-for buffer)]
            (.activateInOtherWindow ed ib)))))))

(defn- save-as!
  "Write the fence's rendered image to a file the user picks. `parent` is
  the component the chooser opens against."
  [^Buffer buffer ^Line anchor ^java.awt.Component parent]
  (when-let [^BufferedImage img (image-for buffer anchor)]
    (let [chooser (JFileChooser.)
          result (.showSaveDialog chooser parent)]
      (when (== result JFileChooser/APPROVE_OPTION)
        (javax.imageio.ImageIO/write img "png" (.getSelectedFile chooser))))))

(defn- wire-item
  "Enable `item` per `enabled?` and run `action` when clicked."
  [^JMenuItem item enabled? action]
  (doto item
    (.setEnabled enabled?)
    (.addActionListener (reify ActionListener
                          (actionPerformed [_ _] (action))))))

(defn- show-controls-menu
  "Pop up the diagram's controls off `button`. State-aware: unpinned, the
  first item pins to the source (\"show code\") and the size submenu is
  live; pinned, the first item brings the picture back and both the size
  submenu and save-as are disabled -- neither applies to shown source.
  open-in-split stays live in both: it opens the rendered image regardless."
  [^JButton button ^Buffer buffer ^Line anchor]
  (let [menu (JPopupMenu.)
        entry (live-entry buffer anchor)
        pinned (boolean (:pinned entry))
        current (or (:size-mode entry) "fit")
        size-menu (JMenu. (if pinned "show" "size"))]
    (.add menu (wire-item (JMenuItem. (if pinned "show diagram" "show code"))
                          true #(flip-pinned! buffer anchor)))
    (.setEnabled size-menu (not pinned))
    (doseq [[label mode] SIZE-MODES]
      (.add size-menu (wire-item (JRadioButtonMenuItem. label (= mode current))
                                 (not pinned)
                                 #(set-size-mode! buffer anchor mode))))
    (.add menu size-menu)
    (.addSeparator menu)
    (.add menu (wire-item (JMenuItem. "open in split window")
                          true #(open-in-split-window! buffer anchor)))
    (.add menu (wire-item (JMenuItem. "save as...")
                          (not pinned) #(save-as! buffer anchor button)))
    (.show menu button 0 (.getHeight button))))

(defn- controls-for
  "The hover controls for the fence anchored at `anchor` in `buffer`: a
  small button that pops the controls menu. Built once per anchor and kept
  in the state's controls map, so the same instance is handed to the
  painter on every flush and the hover does not flicker. Non-focusable:
  clicking it must not steal the editor's focus."
  [^Buffer buffer ^Line anchor]
  (let [s (state-for buffer)
        ^IdentityHashMap ctrls (:controls s)]
    (or (.get ctrls anchor)
        (let [button (JButton. "\u2699")]
          (.setFocusable button false)
          (.setToolTipText button "diagram controls")
          (.addActionListener button
                              (reify ActionListener
                                (actionPerformed [_ _] (show-controls-menu button buffer anchor))))
          (.put ctrls anchor button)
          button))))

(defn- flush!
  "Register the buffer's REPLACE decorations for every live fence that has
  a picture or a failure to show (when diagrams are on). A picture's
  painter carries its size mode, its pin, and its hover controls; the mode
  and pin are in the signature so choosing a new one re-registers and
  re-frames. When toggled off there are no REPLACEs, so the source shows.
  Idempotent: an unchanged set does not repaint. Caller holds the lock."
  [^Buffer buffer]
  (let [s (state-for buffer)
        ^IdentityHashMap live (:live s)
        enabled @(:enabled s)
        shown (filterv (fn [^java.util.Map$Entry e]
                         (let [v (.getValue e)]
                           (or (some? (:image v)) (:error v))))
                       (.entrySet live))
        out (java.util.ArrayList.)
        sig (StringBuilder.)]
    (when enabled
      (doseq [^java.util.Map$Entry e shown]
        (let [^Line anchor (.getKey e)
              entry (.getValue e)
              ^BufferedImage img (:image entry)
              n (long (line-count (:first entry) (:last entry)))
              mode (or (:size-mode entry) "fit")
              pinned (boolean (:pinned entry))]
          (.add out
                (VirtualText/replace
                 (:first entry) (:last entry)
                 (if (some? img)
                   (painter/diagram-painter img n (:editor entry) mode
                                            (controls-for buffer anchor) pinned)
                   (painter/placeholder-painter ^String (:error entry) n))))
          (.append sig (str (.lineNumber anchor) ":"
                            (if (some? img) (System/identityHashCode img) (:error entry))
                            ":" mode ":" (if pinned "pinned" "shown") ";")))))
    (let [s2 (.toString sig)]
      (when-not (= s2 @(:last-sig s))
        (reset! (:last-sig s) s2)
        (.set (.getVirtualText buffer) OWNER out)))))

(defn- kick!
  "Render body off the EDT, then land the result on the EDT if the fence is
  still live with the same body. The work never throws: a failure comes back
  as {:error msg} and is drawn as a placeholder, so a missing renderer or
  bad syntax is visible, never a blank gap or a crash. The content-hash
  cache is consulted first, off the EDT, so a body already drawn skips the
  render and the PATH probe never blocks a keystroke."
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
  (reset! last-body body)
  (swap! render-count inc)
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
      (when @(:enabled s)
        (cond
          (and same (:image existing)) :done
          (and same (:rendering existing)) :inflight
          ;; Tried this body and it failed: leave it revealed until the body
          ;; changes, rather than retrying on every keystroke.
          (and same (nil? (:image existing)) (not (:rendering existing))) :failed
          ;; New or changed body: render it, carrying the per-anchor pin and
          ;; size mode forward so an edit doesn't revert the chosen view.
          :else
          (let [theme (theme-for editor)]
            (.put live first
                  (merge (select-keys existing [:pinned :size-mode])
                         {:first first :last last :body body
                          :editor editor :image nil :rendering true}))
            (kick! buffer first body theme)))))))

(defn -renderDone [_this ^Editor _editor ^Buffer buffer]
  (locking lock
    (let [s (state-for buffer)
          ^IdentityHashMap live (:live s)
          ^IdentityHashMap ctrls (:controls s)
          ^HashSet seen (:seen s)]
      ;; Drop fences not claimed this round: renamed away, or deleted.
      (doseq [anchor (into-array Object (.toArray (.keySet live)))]
        (when-not (.contains seen anchor)
          (.remove live anchor)
          (.remove ctrls anchor)))
      (.clear seen)
      (flush! buffer))))

(defn ^:static -toggleMermaid
  "Flip whether the current buffer shows its mermaid diagrams as pictures.
  On (the default) a rendered fence collapses to its picture; off, the
  source stays visible and no render work is done. The content-hash cache
  makes toggling back on cheap -- a body already drawn re-registers
  without re-rendering."
  []
  (let [editor (Editor/currentEditor)
        buffer (when editor (.getBuffer editor))]
    (when (and buffer (instance? MarkdownMode (.getMode buffer)))
      (locking lock
        (swap! (:enabled (state-for buffer)) not))
      (MarkdownFences/onActivate editor))))

(defn ^:static -getLastBody []
  @last-body)

(defn ^:static -getRenderCount []
  @render-count)

(defn ^:static -reset []
  (locking lock
    (reset! last-body nil)
    (reset! render-count 0)
    (.clear states)
    (cache/clear)))
