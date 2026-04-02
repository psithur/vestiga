(ns sample.protocols)

(defprotocol Indexable
  (index-entity [this]
    "Index this entity.")
  (deindex-entity [this]
    "Remove this entity from the index."))

(defrecord Document [id title content]
  Indexable
  (index-entity [this]
    {:id      (:id this)
     :indexed true})
  (deindex-entity [this]
    {:id      (:id this)
     :indexed false}))
