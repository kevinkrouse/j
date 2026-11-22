(ns org.armedbear.j.mermaid.cache
  "Rendered diagrams by content hash. The key is sha256 of the body, the
  theme, and the backend name, so the same diagram in the same theme from
  the same renderer is drawn once and reused -- across edits that revert,
  across buffers, across windows. A theme or renderer change is a different
  key, so the diagram re-renders to match (the plan's dark-mode trap).

  Bounded: the eldest entry is evicted past `max-entries` so a long session
  with many diagrams does not grow without limit. The decoded BufferedImage
  is what is cached, so a hit is a plain map lookup and the painter never
  touches the disk or a process."
  (:import [java.security MessageDigest]
           [java.util LinkedHashMap Map]
           [java.awt.image BufferedImage]
           [javax.imageio ImageIO]
           [java.io File]))

(def ^:private max-entries 64)

(defn- sha256
  "Hex sha256 of a string."
  [^String s]
  (let [d (MessageDigest/getInstance "SHA-256")]
    (.update d (.getBytes s "UTF-8"))
    (apply str (map #(format "%02x" %) (.digest d)))))

(defn cache-key
  "The cache key for a body, theme, and backend name."
  [^String body ^String theme ^String backend]
  (sha256 (str theme "\0" backend "\0" body)))

;; Access-order LinkedHashMap: .get and .put move an entry to the newest
;; end, so the first entry is always the least-recently-used.
(def ^:private ^Map table (LinkedHashMap. 16 0.75 true))

(defn cached
  "The cached BufferedImage for k, or nil. Touches the entry so it is the
  most-recently-used."
  [^String k]
  (locking table (.get table k)))

(defn store!
  "Cache image under k, evicting the eldest past the cap."
  [^String k ^BufferedImage image]
  (locking table
    (.put table k image)
    (when (> (.size table) max-entries)
      (let [it (.iterator ^Map table)]
        (when (.hasNext it)
          (.next it)
          (.remove it))))))

(defn clear
  "Empty the cache. For tests and a renderer/pref change."
  []
  (locking table (.clear table)))

(defn read-png
  "Decode a PNG file to a BufferedImage, or nil if unreadable."
  [^String path]
  (try
    (ImageIO/read (File. path))
    (catch Exception _ nil)))
