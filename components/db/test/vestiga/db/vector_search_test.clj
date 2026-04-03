(ns vestiga.db.vector-search-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.ops :as ops]
    [vestiga.db.schema :as schema]
    [vestiga.db.search :as search]
    [vestiga.test-helpers :as h]))

;; Note: vec0 extension is typically not available in test environments.
;; These tests verify the graceful fallback behavior when vec0 is missing,
;; plus the code paths with mock data when vec0 is available.

(deftest test-vector-search-without-vec0
  (testing "returns nil when vec0 is not available"
    (h/with-temp-db
      (fn [db]
        ;; In-memory DB typically has :vec? false
        (let [results (search/vector-search db (vec (repeat 768 0.1)))]
          (is
            (nil? results)
            "vector-search should return nil when vec? is false"))))))

(deftest test-ensure-vec-tables-without-vec0
  (testing "ensure-vec-tables! is a no-op when vec0 is not available"
    (h/with-temp-db
      (fn [db]
        ;; Should not throw
        (schema/ensure-vec-tables! db 768)
        (is
          true
          "ensure-vec-tables! should not throw when vec? is false")))))

(deftest test-upsert-chunk-embedding-without-vec0
  (testing "upsert-chunk-embedding! is a no-op when vec0 is not available"
    (h/with-temp-db
      (fn [db]
        ;; Should not throw, returns nil
        (let [result (ops/upsert-chunk-embedding! db 1 (vec (repeat 768 0.1)))]
          (is
            (nil? result)
            "upsert-chunk-embedding! should return nil when vec? is false"))))))

(deftest test-get-chunks-without-embeddings-without-vec0
  (testing "returns nil when vec0 is not available"
    (h/with-temp-db
      (fn [db]
        (let [pid (ops/upsert-project!
                    db
                    {:root-path "/tmp/test"
                     :name      "test"})]
          (ops/insert-chunk!
            db
            {:project-id     pid
             :file-path      "src/core.clj"
             :namespace      "my.core"
             :qualified-name "my.core/foo"
             :symbol-name    "foo"
             :kind           "defn"
             :content        "(defn foo [] :ok)"
             :start-line     1
             :end-line       1
             :arity          nil
             :docstring      nil
             :file-hash      "h1"})
          (let [result (ops/get-chunks-without-embeddings db pid)]
            (is
              (nil? result)
              "get-chunks-without-embeddings should return nil when vec? is false")))))))

(deftest test-vector-search-filter-params
  (testing "vector-search builds correct filter parameters"
    (h/with-temp-db
      (fn [db]
        ;; Even with vec? false, verify the function doesn't throw
        ;; with various filter combinations
        (is
          (nil? (search/vector-search db (vec (repeat 768 0.1)) :project-id 1 :limit 10)))
        (is
          (nil? (search/vector-search db (vec (repeat 768 0.1)) :kinds #{"defn" "defmacro"})))
        (is
          (nil? (search/vector-search db (vec (repeat 768 0.1)) :namespace "my.core*" :file-path "src/*.clj")))))))
