(ns vestiga.search.hybrid-search-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.interface.ops :as ops]
    [vestiga.search.engine :as engine]
    [vestiga.test-helpers :as h]))

(defn- setup-test-data!
  "Insert test data for search tests."
  [db]
  (let [pid (ops/upsert-project!
              db
              {:root-path "/tmp/test"
               :name      "test"})]
    (ops/insert-chunk!
      db
      {:project-id     pid
       :file-path      "src/core.clj"
       :namespace      "my.core"
       :qualified-name "my.core/greet"
       :symbol-name    "greet"
       :kind           "defn"
       :content        "(defn greet [name] (str \"Hello\" name))"
       :start-line     1
       :end-line       3
       :arity          "[\"[name]\"]"
       :docstring      "Greet someone by name"
       :file-hash      "h1"})
    (ops/insert-chunk!
      db
      {:project-id     pid
       :file-path      "src/util.clj"
       :namespace      "my.util"
       :qualified-name "my.util/transform"
       :symbol-name    "transform"
       :kind           "defn"
       :content        "(defn transform [item] (assoc item :done true))"
       :start-line     1
       :end-line       2
       :arity          "[\"[item]\"]"
       :docstring      "Transform an item for processing"
       :file-hash      "h2"})
    (ops/insert-chunk!
      db
      {:project-id     pid
       :file-path      "src/db.clj"
       :namespace      "my.db"
       :qualified-name "my.db/ensure-schema!"
       :symbol-name    "ensure-schema!"
       :kind           "defn"
       :content        "(defn ensure-schema! [db] (execute! db schema-sql))"
       :start-line     1
       :end-line       2
       :arity          "[\"[db]\"]"
       :docstring      "Ensure database schema is applied"
       :file-hash      "h3"})
    (ops/insert-ref!
      db
      {:project-id pid
       :from-ns    "my.core"
       :from-name  "process"
       :to-ns      "my.util"
       :to-name    "transform"
       :file-path  "src/core.clj"
       :row        10
       :col        5})
    (ops/insert-ns-dep!
      db
      {:project-id pid
       :from-ns    "my.core"
       :to-ns      "my.util"})
    pid))

(deftest test-hybrid-search-bm25-only
  (testing "search returns BM25 results when vec0 is not available"
    (h/with-temp-db
      (fn [db]
        (setup-test-data! db)
        ;; db has :vec? false, so vector search is skipped
        (let [results (engine/search db "greet")]
          (is
            (pos? (count results)))
          (is
            (some #(= "my.core/greet" (:qualified_name %)) results)))))))

(deftest test-hybrid-search-with-kinds-filter
  (testing "search respects kind filter"
    (h/with-temp-db
      (fn [db]
        (setup-test-data! db)
        (let [results (engine/search db "greet" :kinds #{"defn"})]
          (is
            (pos? (count results)))
          (is
            (every? #(= "defn" (:kind %)) results)))))))

(deftest test-hybrid-search-with-namespace-filter
  (testing "search respects namespace filter"
    (h/with-temp-db
      (fn [db]
        (setup-test-data! db)
        (let [results (engine/search db "transform" :namespace "my.util")]
          (is
            (pos? (count results)))
          (is
            (every? #(= "my.util" (:namespace %)) results)))))))

(deftest test-hybrid-search-qualified-name-structural
  (testing "search uses structural match for qualified names"
    (h/with-temp-db
      (fn [db]
        (setup-test-data! db)
        (let [results (engine/search db "my.core/greet")]
          (is
            (pos? (count results)))
          (is
            (some #(= "my.core/greet" (:qualified_name %)) results)))))))

(deftest test-hybrid-search-hyphenated
  (testing "search handles hyphenated symbol names"
    (h/with-temp-db
      (fn [db]
        (setup-test-data! db)
        (let [results (engine/search db "ensure-schema")]
          (is
            (pos? (count results)))
          (is
            (some #(= "my.db/ensure-schema!" (:qualified_name %)) results)))))))

(deftest test-hybrid-search-limit
  (testing "search respects limit parameter"
    (h/with-temp-db
      (fn [db]
        (setup-test-data! db)
        (let [results (engine/search db "defn" :limit 2)]
          (is
            (<= (count results) 2)))))))

(deftest test-hybrid-search-no-results
  (testing "search returns empty vec for no matches"
    (h/with-temp-db
      (fn [db]
        (setup-test-data! db)
        (let [results (engine/search db "zzz_nonexistent_zzz")]
          (is
            (vector? results))
          (is
            (zero? (count results))))))))
