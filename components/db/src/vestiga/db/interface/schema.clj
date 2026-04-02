(ns vestiga.db.interface.schema
  (:require
    [vestiga.db.schema :as impl]))

(defn ensure-schema!
  [db]
  (impl/ensure-schema! db))

(defn ensure-vec-tables!
  [db embed-dim]
  (impl/ensure-vec-tables! db embed-dim))
