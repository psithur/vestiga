(ns vestiga.db.ops-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.ops :as ops]
    [vestiga.test-helpers :as h]))

(deftest test-project-crud
  (h/with-temp-db
    (fn [db]
      (testing "can create and retrieve a project"
        (let [id (ops/upsert-project!
                   db
                   {:root-path "/tmp/test"
                    :name      "test-project"})]
          (is
            (pos? id))
          (let [project (ops/get-project db "/tmp/test")]
            (is
              (some? project))
            (is
              (= "test-project" (:name project))))))

      (testing "upsert returns existing project id"
        (let [id1 (ops/upsert-project!
                    db
                    {:root-path "/tmp/test"
                     :name      "test-project"})
              id2 (ops/upsert-project!
                    db
                    {:root-path "/tmp/test"
                     :name      "test-project"})]
          (is
            (= id1 id2))))

      (testing "can update project head"
        (let [id (ops/upsert-project!
                   db
                   {:root-path "/tmp/test2"
                    :name      "test2"})]
          (ops/update-project-head! db id "abc123")
          (let [project (ops/get-project db "/tmp/test2")]
            (is
              (= "abc123" (:head_sha project)))))))))

(deftest test-chunk-crud
  (h/with-temp-db
    (fn [db]
      (let [pid (ops/upsert-project!
                  db
                  {:root-path "/tmp/test"
                   :name      "test"})]
        (testing "can insert and query chunks"
          (let [chunk-id (ops/insert-chunk!
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
                            :docstring      "Greet someone"
                            :file-hash      "abc123"})]
            (is
              (pos? chunk-id))
            (let [chunks (ops/get-chunks-for-file db pid "src/core.clj")]
              (is
                (= 1 (count chunks)))
              (is
                (= "my.core/greet" (:qualified_name (first chunks)))))))

        (testing "can get chunk by qualified name"
          (let [chunk (ops/get-chunk-by-qname db "my.core/greet")]
            (is
              (some? chunk))
            (is
              (= "defn" (:kind chunk)))))

        (testing "can delete chunks for file"
          (ops/delete-chunks-for-file! db pid "src/core.clj")
          (is
            (empty? (ops/get-chunks-for-file db pid "src/core.clj"))))))))

(deftest test-ref-crud
  (h/with-temp-db
    (fn [db]
      (let [pid (ops/upsert-project!
                  db
                  {:root-path "/tmp/test"
                   :name      "test"})]
        (testing "can insert and query refs"
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
          (let [refs (ops/get-refs-to db pid "my.util" "transform")]
            (is
              (= 1 (count refs)))
            (is
              (= "my.core" (:from_ns (first refs))))))))))

(deftest test-ns-dep-crud
  (h/with-temp-db
    (fn [db]
      (let [pid (ops/upsert-project!
                  db
                  {:root-path "/tmp/test"
                   :name      "test"})]
        (testing "can insert and query ns deps"
          (ops/insert-ns-dep!
            db
            {:project-id pid
             :from-ns    "my.core"
             :to-ns      "my.util"})
          (let [deps (ops/get-ns-dependents db pid "my.util")]
            (is
              (= 1 (count deps)))
            (is
              (= "my.core" (:from_ns (first deps))))))))))

(deftest test-commit-crud
  (h/with-temp-db
    (fn [db]
      (let [pid (ops/upsert-project!
                  db
                  {:root-path "/tmp/test"
                   :name      "test"})]
        (testing "can insert and query commits"
          (let [cid (ops/insert-commit!
                      db
                      {:project-id pid
                       :sha        "abc123"
                       :author     "Test"
                       :timestamp  1700000000
                       :message    "Initial commit"})]
            (is
              (pos? cid))
            (ops/insert-commit-file!
              db
              {:commit-id     cid
               :file-path     "src/core.clj"
               :change-type   "A"
               :lines-added   50
               :lines-removed 0})
            (let [commits (ops/get-commits-for-file db pid "src/core.clj")]
              (is
                (= 1 (count commits)))
              (is
                (= "abc123" (:sha (first commits)))))))))))
