(ns vestiga.index.interface
  (:require
    [vestiga.index.coordinator :as coordinator]))

(defn index-project!
  [db project-root & opts]
  (apply coordinator/index-project! db project-root opts))
