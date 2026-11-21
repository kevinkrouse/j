(ns org.armedbear.j.mermaid.painter
  "The VirtualText.Painter that draws a rendered diagram in place of its
  fence.

  The picture is drawn to fill the fence's own height -- the height the
  source lines already occupy -- not its natural pixel height. That is the
  whole point: a collapsed REPLACE makes the anchor line's height the
  painter's and the absorbed lines' zero, so if the painter were the
  picture's natural height (a tall diagram is several times the fence's
  height) the reveal/collapse swap would move hundreds of pixels of
  content at the caret boundary. Scrolling up would drag the caret into the
  range, the region would shrink, and the window would jump. Matching the
  source height makes the swap height-neutral: collapsed total == revealed
  total, so nothing moves and the scroll stays put.

  The image is scaled to fit inside the (width x source height) box with
  its aspect ratio kept, capped at its natural size so a small diagram is
  not blown up, and centered in the box. The scaled image is cached by the
  box it was fitted to, so a repaint at the same size is a plain blit and
  never resamples on the event thread. The painter holds the editor
  weakly: if every window showing the buffer closes, the editor is
  collected rather than kept alive.

  This is the EDT contract from the plan: draw the cached image only,
  never render or touch the filesystem."
  (:import [java.lang.ref WeakReference]
           [java.awt Graphics2D RenderingHints]
           [java.awt.image BufferedImage]
           [org.armedbear.j Editor Display]
           [org.armedbear.j VirtualText$Painter]))

(defn- avail-width
  "The text width available to the picture in editor, or 0 if it is gone."
  [^WeakReference ed-ref]
  (when-let [^Editor ed (.get ed-ref)]
    (let [d (.getDisplay ed)]
      (- (.getWidth d) (Display/getGutterWidth (.getBuffer ed))))))

(defn- fit
  "Scale to contain the image in a w x h box, capped at natural (1.0)."
  [^long iw ^long ih ^long w ^long h]
  (min (/ (double w) (double iw))
       (/ (double h) (double ih))
       1.0))

(defn- fitted
  "The image scaled to fit a w x h box, aspect kept, capped at natural."
  [^BufferedImage image ^long w ^long h]
  (let [iw (.getWidth image)
        ih (.getHeight image)
        s (fit iw ih w h)]
    (if (>= s 1.0)
      image
      (let [tw (max 1 (Math/round (* iw s)))
            th (max 1 (Math/round (* ih s)))
            out (BufferedImage. tw th BufferedImage/TYPE_INT_ARGB)
            g (.createGraphics out)]
        (.setRenderingHint g RenderingHints/KEY_INTERPOLATION
                           RenderingHints/VALUE_INTERPOLATION_BILINEAR)
        (.drawImage g image 0 0 tw th nil)
        (.dispose g)
        out))))

(deftype DiagramPainter [^BufferedImage image ^long line-count
                         ^WeakReference ed-ref ^objects cache]
  VirtualText$Painter
  ;; The box height is the fence's own height, recomputed from the current
  ;; char height on every call, so a font-scale change is picked up on the
  ;; next layout pass without the handler re-registering. The display asks
  ;; for this during rowHeight and paint, and a font change repaints.
  (^int getHeight [_this]
    (int (* line-count (long (Display/getCharHeight)))))
  (^void paint [_this ^Graphics2D g ^int x ^int y ^int w ^int h]
    ;; Fit to the box the display gave us (w x h, h == source height),
    ;; centered, caching the fitted image by the box so a same-size repaint
    ;; is a blit. The centering is recomputed from the fitted image's size
    ;; on every paint -- including a cache hit -- so a diagram smaller than
    ;; its box stays centered, not snapped to the top-left.
    (let [cached (aget cache 0)
          img (if (and (some? cached)
                       (= (nth cached 0) (long w))
                       (= (nth cached 1) (long h)))
                ^BufferedImage (nth cached 2)
                (let [f (fitted image w h)]
                  (aset cache 0 (object-array [(long w) (long h) f]))
                  f))
          tx (+ x (quot (- w (.getWidth img)) 2))
          ty (+ y (quot (- h (.getHeight img)) 2))]
      (.drawImage g img (int tx) (int ty) nil))))

(defn diagram-painter
  "A painter for the rendered `image`, fitted to a box `line-count` lines
  tall -- the height the fence's own lines occupy -- so collapsing and
  revealing the diagram does not change the buffer's height. The box is
  recomputed from the current char height on each call, so a font-scale
  change is tracked without re-registering."
  [^BufferedImage image ^long line-count ^Editor editor]
  (DiagramPainter. image line-count (WeakReference. editor) (object-array [nil])))

;; A one-line notice drawn where a diagram failed to render: the reason, in
;; a muted color, so the fence is never a blank gap. The plan's "renderer
;; not found" and "bad syntax" both land here. It too reports the fence's
;; own height, recomputed from the current char height, so a failed render
;; neither jumps nor collapses -- and tracks a font-scale change.
(deftype PlaceholderPainter [^String message ^long line-count]
  VirtualText$Painter
  (^int getHeight [_this]
    (int (* line-count (long (Display/getCharHeight)))))
  (^void paint [_this ^Graphics2D g ^int x ^int y ^int w ^int h]
    (.setColor g java.awt.Color/GRAY)
    (.drawString g message x (+ y (.getAscent (.getFontMetrics g)))
                 (min w (.length message)))))

(defn placeholder-painter
  "A painter that draws `message` in place of a diagram that did not
  render, over a box `line-count` lines tall."
  [^String message ^long line-count]
  (PlaceholderPainter. message line-count))
