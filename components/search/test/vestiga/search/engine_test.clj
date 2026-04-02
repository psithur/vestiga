(ns vestiga.search.engine-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.interface.ops :as ops]
    [vestiga.search.engine :as engine]
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

(deftest test-search
  (h/with-temp-db
    (fn [db]
      (setup-test-data! db)
      (testing "hybrid search returns results"
        (let [results (engine/search db "greet")]
          (is
            (pos? (count results))))))))

(deftest test-find-references
  (h/with-temp-db
    (fn [db]
      (let [pid (setup-test-data! db)]
        (testing "finds references to a symbol"
          (let [refs (engine/find-references db "my.util/transform" :project-id pid)]
            (is
              (pos? (count refs)))))))))

(deftest test-find-dependents
  (h/with-temp-db
    (fn [db]
      (let [pid (setup-test-data! db)]
        (testing "finds namespace dependents"
          (let [deps (engine/find-dependents db "my.util" :project-id pid)]
            (is
              (pos? (count deps)))))))))

(deftest test-impact-analysis
  (h/with-temp-db
    (fn [db]
      (let [pid (setup-test-data! db)]
        (testing "returns impact analysis results"
          (let [impact (engine/impact-analysis db "my.core/greet" :project-id pid)]
            (is
              (map? impact))
            (is
              (vector? (:callers impact)))
            (is
              (vector? (:ns-dependents impact)))
            (is
              (vector? (:recent-commits impact)))))))))
