(ns vestiga.config.interface
  (:require
    [vestiga.config.core :as impl]))

(def defaults impl/defaults)

(defn load-config
  ([] (impl/load-config))
  ([path] (impl/load-config path)))

(defn discover-index-paths
  "Discover source paths for a project. Detects Polylith layout automatically."
  [project-root & {:keys [config-paths]}]
  (impl/discover-index-paths project-root :config-paths config-paths))
