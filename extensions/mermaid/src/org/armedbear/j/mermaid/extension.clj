(ns org.armedbear.j.mermaid.extension
  "The mermaid extension: claims the ```mermaid fence in Markdown buffers.

  gen-class, not deftype: its static initializer loads this namespace, so
  the command's public static method and the handler's top-level defs are
  bound before anything calls them. The handler itself (handler.clj) runs
  the render -> REPLACE pipeline; the backends are in renderer.clj."
  (:gen-class
   :name org.armedbear.j.mermaid.MermaidExtension
   :implements [org.armedbear.j.extension.Extension]
   :methods [^{:static true} [mermaidPing [] void]])
  (:require org.armedbear.j.mermaid.handler)
  (:import [org.armedbear.j Log]))

(defn -getName [_]
  "mermaid")

(defn -getVersion [_]
  "0.1.0")

(defn -initialize [this context]
  ;; The owner class is this very class; core reflects mermaidPing on it.
  (.registerFenceHandler context (org.armedbear.j.mermaid.MermaidHandler.))
  (.registerCommand context "mermaidPing" (.getClass this) "mermaidPing")
  ;; toggleMermaid lives on the handler, which owns the per-buffer flag.
  (.registerCommand context "toggleMermaid" org.armedbear.j.mermaid.MermaidHandler "toggleMermaid"))

(defn ^:static -mermaidPing []
  (Log/info "mermaid pong"))
