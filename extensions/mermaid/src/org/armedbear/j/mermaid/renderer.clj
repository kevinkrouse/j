(ns org.armedbear.j.mermaid.renderer
  "Backend selection for the mermaid extension. The user picks a kind with the
  `mermaid.renderer` preference -- `auto` (default), `native` (mmdr), or
  `mmdc` -- and can point at a specific binary with `mermaid.rendererPath`.
  Neither is bundled: the renderer is found on PATH or named outright. If
  nothing usable is found, `resolve` returns nil and the handler shows a
  \"renderer not found\" placeholder rather than a blank or a crash.

  Preferences are read by string key off the global preferences, because an
  extension cannot register a `Property` object (that is core-only). The keys
  are `mermaid.renderer` and `mermaid.rendererPath`.

  Resolution is cached per (kind, path) so a render does not spawn a
  `--version` probe every time; the cache is invalidated when the prefs
  change, by keying on the pref values themselves."
  (:require [org.armedbear.j.mermaid.native :as native]
            [org.armedbear.j.mermaid.mmdc :as mmdc])
  (:import [org.armedbear.j Editor]))

(defn- pref
  "A global string preference, or nil."
  [^String key]
  (.getStringProperty (Editor/preferences) key))

(defn- probe
  "Whether binary is a runnable renderer of the given kind."
  [kind binary]
  (case kind
    :native (native/available? binary)
    :mmdc (mmdc/available? binary)
    false))

(defn- resolve-1
  "Resolve kind+path to a concrete {:kind :binary} or nil, probing PATH."
  [^String kind-s ^String path]
  (let [kind (keyword kind-s)]
    (cond
      ;; A named binary: trust the kind, or treat a bare path as native.
      (some? path)
      (let [k (if (= :auto kind) :native kind)]
        (when (probe k path) {:kind k :binary path}))

      ;; auto: prefer the fast native renderer, fall back to mmdc.
      (= :auto kind)
      (cond
        (probe :native nil) {:kind :native :binary "mmdr"}
        (probe :mmdc nil) {:kind :mmdc :binary "mmdc"}
        :else nil)

      ;; An explicit kind with no path: probe the default name.
      (contains? #{:native :mmdc} kind)
      (when (probe kind nil)
        {:kind kind :binary (case kind :native "mmdr" "mmdc")})

      :else nil)))

(def ^:private cache
  "Resolved backends by [kind path], so a render does not re-probe. The key
  is the pref values, so changing a pref is a different key and re-probes."
  (atom {}))

(defn resolve-backend
  "The backend to render with, as {:kind :native|:mmdc, :binary \"...\"}, or
  nil when none is available. Cached on the current preference values."
  []
  (let [kind-s (or (pref "mermaid.renderer") "auto")
        path (pref "mermaid.rendererPath")
        k [kind-s path]]
    ;; contains?, not if-let: a cached nil (nothing available) must not
    ;; look like a cache miss and re-probe.
    (if (contains? @cache k)
      (get @cache k)
      (let [r (resolve-1 kind-s path)]
        (swap! cache assoc k r)
        r))))

(defn render
  "Render body to out-file (a PNG) with the resolved backend. theme is
  \"dark\" or \"default\". Returns out-file on success; throws with the
  renderer's message on failure, or IllegalStateException when no backend
  is available. Runs off the event thread."
  [^String body ^String theme ^String out-file]
  (let [{:keys [kind binary]} (resolve-backend)]
    (when-not kind
      (throw (IllegalStateException.
              "no mermaid renderer found (install mmdr or mmdc, or set mermaid.rendererPath)")))
    (case kind
      :native (native/render-png body theme out-file binary)
      :mmdc (let [in-file (doto (java.io.File/createTempFile "mermaid-" ".mmd")
                            (spit body))]
              (try
                (mmdc/render-png (.getAbsolutePath in-file) out-file theme binary)
                (finally (.delete in-file)))))))

(defn backend-name
  "A short name for the resolved backend, for the placeholder text."
  []
  (when-let [{:keys [kind]} (resolve-backend)]
    (name kind)))
