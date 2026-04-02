(ns vestiga.index.interface.kondo
  (:require
    [vestiga.index.clj-kondo :as clj-kondo]))

(defn run-analysis
  [project-root paths]
  (clj-kondo/run-analysis project-root paths))

(defn var-def->symbol-kind
  [defined-by]
  (clj-kondo/var-def->symbol-kind defined-by))

(defn analysis->symbols
  [analysis]
  (clj-kondo/analysis->symbols analysis))

(defn analysis->refs
  [analysis]
  (clj-kondo/analysis->refs analysis))

(defn analysis->ns-deps
  [analysis]
  (clj-kondo/analysis->ns-deps analysis))
