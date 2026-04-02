(ns vestiga.search.interface
  (:require
    [vestiga.search.engine :as engine]))

(defn search
  [db query & opts]
  (apply engine/search db query opts))

(defn find-references
  [db qualified-name & opts]
  (apply engine/find-references db qualified-name opts))

(defn find-dependents
  [db namespace-name & opts]
  (apply engine/find-dependents db namespace-name opts))

(defn impact-analysis
  [db qualified-name & opts]
  (apply engine/impact-analysis db qualified-name opts))
