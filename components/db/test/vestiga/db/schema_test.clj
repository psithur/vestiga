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
               (= 1 (:version (first versions)))))
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
