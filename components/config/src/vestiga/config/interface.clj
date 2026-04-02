(ns vestiga.config.interface
  (:require
    [vestiga.config.core :as impl]))

(def defaults impl/defaults)

(defn load-config
  ([] (impl/load-config))
  ([path] (impl/load-config path)))
