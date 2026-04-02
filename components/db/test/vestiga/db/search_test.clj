(ns vestiga.db.search-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.ops :as ops]
    [vestiga.db.search :as search]
    [vestiga.test-helpers :as h]))

(defn- setup-test-data!
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
       :arity          nil
       :docstring      "Greet someone"
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
       :arity          nil
       :docstring      "Transform an item"
       :file-hash      "h2"})
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
    (ops/insert-commit!
      db
      {:project-id pid
       :sha        "abc123"
       :author     "Test"
       :timestamp  1700000000
       :message    "Add greet function"})
    pid))

(deftest test-bm25-search
  (h/with-temp-db
    (fn [db]
      (setup-test-data! db)
      (testing "finds chunks by content"
        (let [results (search/bm25-search db "greet")]
          (is
            (pos? (count results)))
          (is
            (= "my.core/greet" (:qualified_name (first results))))))

      (testing "finds chunks by symbol name"
        (let [results (search/bm25-search db "transform")]
          (is
            (pos? (count results)))
          (is
            (= "my.util/transform" (:qualified_name (first results)))))))))

(deftest test-bm25-search-with-hyphens
  (h/with-temp-db
    (fn [db]
      ;; Insert a chunk with a hyphenated name
      (let [pid (ops/upsert-project!
                  db
                  {:root-path "/tmp/test"
                   :name      "test"})]
        (ops/insert-chunk!
          db
          {:project-id     pid
           :file-path      "src/schema.clj"
           :namespace      "my.db"
           :qualified-name "my.db/ensure-schema!"
           :symbol-name    "ensure-schema!"
           :kind           "defn"
           :content        "(defn ensure-schema! [db] ...)"
           :start-line     1
           :end-line       3
           :arity          nil
           :docstring      nil
           :file-hash      "h1"}))
      (testing "finds hyphenated symbols (hyphen replaced with space)"
        (let [results (search/bm25-search db "ensure-schema")]
          (is
            (pos? (count results)))
          (is
            (= "my.db/ensure-schema!" (:qualified_name (first results))))))
      (testing "also finds with space-separated terms"
        (let [results (search/bm25-search db "ensure schema")]
          (is
            (pos? (count results))))))))

(deftest test-find-by-qualified-name
  (h/with-temp-db
    (fn [db]
      (setup-test-data! db)
      (testing "exact lookup by qualified name"
        (let [results (search/find-by-qualified-name db "my.core/greet")]
          (is
            (= 1 (count results)))
          (is
            (= "defn" (:kind (first results)))))))))

(deftest test-find-refs-to-symbol
  (h/with-temp-db
    (fn [db]
      (let [pid (setup-test-data! db)]
        (testing "finds references to a symbol"
          (let [refs (search/find-refs-to-symbol db pid "my.util/transform")]
            (is
              (= 1 (count refs)))
            (is
              (= "my.core" (:from_ns (first refs))))))))))

(deftest test-find-ns-dependents
  (h/with-temp-db
    (fn [db]
      (let [pid (setup-test-data! db)]
        (testing "finds namespace dependents"
          (let [deps (search/find-ns-dependents db pid "my.util")]
            (is
              (= 1 (count deps)))
            (is
              (= "my.core" (:from_ns (first deps))))))))))

(deftest test-search-commits
  (h/with-temp-db
    (fn [db]
      (setup-test-data! db)
      (testing "finds commits by message"
        (let [results (search/search-commits db "greet")]
          (is
            (pos? (count results)))
          (is
            (= "abc123" (:sha (first results)))))))))
