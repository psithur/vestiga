(ns user
  (:require
    [vestiga.db.interface :as db]
    [vestiga.db.interface.schema :as schema]))

(defn start-dev-db
  "Open an in-memory DB for REPL use."
  []
  (let [conn (db/open-db ":memory:")]
    (schema/ensure-schema! conn)
    conn))

(comment
  (def db (start-dev-db))
  (db/close-db db))
