(ns vestiga.db.schema-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.connection :as db]
    [vestiga.db.schema :as schema]))

(deftest test-ensure-schema
  (testing "applies schema to fresh database"
    (let [conn (db/open-db ":memory:")]
      (try (schema/ensure-schema! conn)
           ;; Verify tables exist
           (let [tables (db/query conn "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name" [])]
             (is
               (some #(= "chunks" (:name %)) tables))
             (is
               (some #(= "projects" (:name %)) tables))
             (is
               (some #(= "refs" (:name %)) tables))
             (is
               (some #(= "ns_deps" (:name %)) tables))
             (is
               (some #(= "commits" (:name %)) tables))
             (is
               (some #(= "commit_files" (:name %)) tables))
             (is
               (some #(= "schema_version" (:name %)) tables)))
           ;; Verify schema version was set
           (let [versions (db/query conn "SELECT version FROM schema_version" [])]
             (is
               (= 3 (:version (first versions)))))
           (finally (db/close-db conn))))))

(deftest test-ensure-schema-idempotent
  (testing "applying schema twice is safe"
    (let [conn (db/open-db ":memory:")]
      (try (schema/ensure-schema! conn)
           (schema/ensure-schema! conn)
           (let [versions (db/query conn "SELECT version FROM schema_version" [])]
             (is
               (= 1 (count versions))))
           (finally (db/close-db conn))))))

(deftest test-schema-fts-triggers
  (testing "FTS5 triggers sync data on insert"
    (let [conn (db/open-db ":memory:")]
      (try
        (schema/ensure-schema! conn)
        ;; Insert a project
        (db/execute! conn "INSERT INTO projects (root_path, name) VALUES (?, ?)" ["/tmp" "test"])
        ;; Insert a chunk
        (db/execute!
          conn
          "INSERT INTO chunks (project_id, file_path, namespace, qualified_name, symbol_name,
                               kind, content, start_line, end_line, file_hash)
           VALUES (1, 'src/core.clj', 'my.core', 'my.core/greet', 'greet',
                   'defn', '(defn greet [name] name)', 1, 1, 'abc')"
          [])
        ;; FTS5 should have the data
        (let [results (db/query conn "SELECT * FROM chunks_fts WHERE chunks_fts MATCH 'greet'" [])]
          (is
            (= 1 (count results))))
        (finally (db/close-db conn))))))

(deftest test-schema-on-file-db
  (testing "schema applies correctly to a file-based database (not just :memory:)"
    (let [dir  (str (java.io.File/createTempFile "vestiga-test" ".db"))
          _ (.delete (java.io.File. ^String dir))
          conn (db/open-db dir)]
      (try (schema/ensure-schema! conn)
           ;; Insert and query to verify full round-trip
           (db/execute! conn "INSERT INTO projects (root_path, name) VALUES (?, ?)" ["/tmp" "test"])
           (let [projects (db/query conn "SELECT * FROM projects" [])]
             (is
               (= 1 (count projects))))
           (finally (db/close-db conn) (.delete (java.io.File. ^String dir)))))))
