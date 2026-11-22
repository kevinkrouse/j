(ns org.armedbear.j.mermaid.painter
  "The VirtualText.Painter that draws a rendered diagram in place of its
  fence.

  Size modes, chosen per diagram from its hover controls:

  - \"fit\" (default) fills the fence's own height, not the picture's
    natural height. This is the height-neutral choice: a collapsed REPLACE
    gives the anchor the painter's height and the absorbed lines zero, so
    a natural-height picture would move hundreds of pixels of content at
    the caret boundary on every reveal/collapse.
  - \"width\" scales to the window's text width, aspect kept.
  - \"original\" draws at natural pixel size.

  The two non-neutral modes change the buffer's height, so the handler's
  signature carries the mode and choosing one re-registers and re-frames.
  Heights are recomputed from the current char height on every call, so a
  font-scale change is tracked without re-registering.

  The scaled image is cached by the size it was scaled to, so a repaint
  at the same size is a plain blit. The editor is held weakly so closing
  every window showing the buffer lets it be collected. The EDT contract:
  draw the cached image only -- never render or touch the filesystem."
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

(defn- scale
  "The image scaled to exactly tw x th, or itself when already that size."
  [^BufferedImage image ^long tw ^long th]
  (if (and (= tw (long (.getWidth image))) (= th (long (.getHeight image))))
    image
    (let [out (BufferedImage. (max 1 (int tw)) (max 1 (int th)) BufferedImage/TYPE_INT_ARGB)
          g (.createGraphics out)]
      (.setRenderingHint g RenderingHints/KEY_INTERPOLATION
                         RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (.drawImage g image 0 0 (int tw) (int th) nil)
      (.dispose g)
      out)))

(deftype DiagramPainter [^BufferedImage image ^long line-count
                         ^WeakReference ed-ref ^clojure.lang.Atom cache
                         ^String size-mode ^javax.swing.JComponent controls
                         ^boolean pinned]
  VirtualText$Painter
  (^int getHeight [_this]
    (let [ch (long (Display/getCharHeight))
          iw (.getWidth image)
          ih (.getHeight image)]
      (case size-mode
        "original" (int ih)
        "width" (if (pos? iw)
                  (int (Math/round (* (double (max 0 (or (avail-width ed-ref) 0)))
                                      (/ (double ih) (double iw)))))
                  (int (* line-count ch)))
        (int (* line-count ch)))))
  (^void paint [_this ^Graphics2D g ^int x ^int y ^int w ^int h]
    ;; The box height h IS getHeight(), the height the layout reserved. In
    ;; width mode the target height is that h, not one recomputed from the
    ;; box width -- recomputing it would let a box wider than getHeight()'s
    ;; avail-width draw taller than its row and overflow onto the lines
    ;; below. The width follows the aspect ratio, clamped to the box.
    (let [iw (.getWidth image)
          ih (.getHeight image)
          tw (long (case size-mode
                     "original" (long iw)
                     "width" (min (long w)
                                  (max 1 (Math/round (* (double h) (/ (double iw) (double ih))))))
                     (max 1 (Math/round (* (double iw) (fit iw ih w h))))))
          th (long (case size-mode
                     "original" (long ih)
                     "width" (max 1 (Math/round (* (double tw) (/ (double ih) (double iw)))))
                     (max 1 (Math/round (* (double ih) (fit iw ih w h))))))
          cached @cache
          img (if (and (some? cached)
                       (= (nth cached 0) tw)
                       (= (nth cached 1) th))
                ^BufferedImage (nth cached 2)
                (let [s (scale image tw th)]
                  (reset! cache [tw th s])
                  s))
          tx (+ x (quot (- w (.getWidth img)) 2))
          ty (+ y (quot (- h (.getHeight img)) 2))]
      (.drawImage g img (int tx) (int ty) nil)))
  (^javax.swing.JComponent getControls [_this ^org.armedbear.j.Editor editor] controls)
  (^boolean isPinned [_this] pinned))

(defn diagram-painter
  "A painter for the rendered `image` in `size-mode` (\"fit\", \"width\",
  or \"original\"), with `controls` the component shown on hover (or nil)
  and `pinned` keeping the source shown even when the caret leaves the
  fence."
  [^BufferedImage image line-count ^Editor editor ^String size-mode
   ^javax.swing.JComponent controls pinned]
  (DiagramPainter. image (long line-count) (WeakReference. editor) (atom nil)
                   size-mode controls (boolean pinned)))

;; A one-line notice where a diagram failed to render, so the fence is
;; never a blank gap. Reports the fence's own height, so a failed render
;; neither jumps nor collapses.
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
