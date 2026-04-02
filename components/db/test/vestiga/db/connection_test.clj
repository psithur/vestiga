(ns vestiga.db.connection-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.connection :as db]))

(deftest test-open-close-db
  (testing "can open and close an in-memory database"
    (let [conn (db/open-db ":memory:")]
      (is
        (some? (:conn conn)))
      (is
        (string? (:db-path conn)))
      (is
        (boolean? (:vec? conn)))
      (db/close-db conn))))

(deftest test-execute-and-query
  (testing "can execute statements and query results"
    (let [conn (db/open-db ":memory:")]
      (try (db/execute! conn "CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)" [])
           (db/execute! conn "INSERT INTO test (name) VALUES (?)" ["hello"])
           (db/execute! conn "INSERT INTO test (name) VALUES (?)" ["world"])
           (let [results (db/query conn "SELECT * FROM test ORDER BY id" [])]
             (is
               (= 2 (count results)))
             (is
               (= "hello" (:name (first results))))
             (is
               (= "world" (:name (second results)))))
           (finally (db/close-db conn))))))

(deftest test-execute-returning-key
  (testing "execute-returning-key! returns the generated id"
    (let [conn (db/open-db ":memory:")]
      (try (db/execute! conn "CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)" [])
           (let [id1 (db/execute-returning-key! conn "INSERT INTO test (name) VALUES (?)" ["hello"])
                 id2 (db/execute-returning-key! conn "INSERT INTO test (name) VALUES (?)" ["world"])]
             (is
               (= 1 id1))
             (is
               (= 2 id2)))
           (finally (db/close-db conn))))))

(deftest test-with-transaction
  (testing "transaction commits on success"
    (let [conn (db/open-db ":memory:")]
      (try (db/execute! conn "CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)" [])
           (db/with-transaction
             conn
             (fn [tx]
               (db/execute! tx "INSERT INTO test (name) VALUES (?)" ["in-tx"])))
           (is
             (= 1 (count (db/query conn "SELECT * FROM test" []))))
           (finally (db/close-db conn)))))

  (testing "transaction rolls back on exception"
    (let [conn (db/open-db ":memory:")]
      (try (db/execute! conn "CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT)" [])
           (is
             (thrown?
               Exception
               (db/with-transaction
                 conn
                 (fn [tx]
                   (db/execute! tx "INSERT INTO test (name) VALUES (?)" ["in-tx"])
                   (throw (ex-info "boom" {}))))))
           (is
             (= 0 (count (db/query conn "SELECT * FROM test" []))))
           (finally (db/close-db conn))))))

(deftest test-null-parameters
  (testing "can handle nil parameters"
    (let [conn (db/open-db ":memory:")]
      (try (db/execute! conn "CREATE TABLE test (id INTEGER PRIMARY KEY, name TEXT, value TEXT)" [])
           (db/execute! conn "INSERT INTO test (name, value) VALUES (?, ?)" ["hello" nil])
           (let [results (db/query conn "SELECT * FROM test" [])]
             (is
               (= 1 (count results)))
             (is
               (nil? (:value (first results)))))
           (finally (db/close-db conn))))))
