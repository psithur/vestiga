(ns vestiga.db.interface
  (:require
    [vestiga.db.connection :as impl]))

(defn open-db
  [db-path & args]
  (apply impl/open-db db-path args))

(defn close-db
  [db]
  (impl/close-db db))

(defn with-db
  [db-path f]
  (impl/with-db db-path f))

(defn execute-raw!
  [db sql]
  (impl/execute-raw! db sql))

(defn execute!
  [db sql params]
  (impl/execute! db sql params))

(defn execute-returning-key!
  [db sql params]
  (impl/execute-returning-key! db sql params))

(defn query
  [db sql params]
  (impl/query db sql params))

(defn with-transaction
  [db f]
  (impl/with-transaction db f))
