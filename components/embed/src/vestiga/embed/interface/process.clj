(ns vestiga.embed.interface.process
  (:require
    [vestiga.embed.process :as process]))

(defn ollama-running?
  [base-url]
  (process/ollama-running? base-url))

(defn ensure-ollama!
  [& opts]
  (apply process/ensure-ollama! opts))

(defn stop-ollama!
  [m]
  (process/stop-ollama! m))
