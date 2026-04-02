(ns sample.core
  (:require
    [sample.protocols :as proto]
    [sample.util :as util]))

(defn greet
  "Greet someone by name."
  [name]
  (str "Hello, " name))

(defn process-items
  "Process a sequence of items."
  [items]
  (->> items
       (map util/transform-item)
       (filter util/valid?)
       vec))

(defprotocol Searchable
  (search [this query]
    "Search within this entity.")
  (index! [this]
    "Index this entity for searching."))

(defn run-pipeline
  "Run the full processing pipeline."
  [config items]
  (let [processed (process-items items)]
    {:count (count processed)
     :items processed}))
