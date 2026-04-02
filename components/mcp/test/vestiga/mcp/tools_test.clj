(ns vestiga.mcp.tools-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.interface.ops :as ops]
    [vestiga.mcp.tools :as tools]
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

;; -- Schema tests -----------------------------------------------------------

(deftest test-list-tools
  (testing "returns tool definitions"
    (let [tool-list (tools/list-tools)]
      (is
        (vector? tool-list))
      (is
        (pos? (count tool-list)))
      (is
        (every? :name tool-list))
      (is
        (every? :inputSchema tool-list)))))

(deftest test-tool-schema-validation
  (testing "all tools have required fields"
    (doseq [tool (tools/list-tools)]
      (is
        (string? (:name tool)))
      (is
        (string? (:description tool)))
      (is
        (map? (:inputSchema tool)))
      (is
        (= "object" (get-in tool [:inputSchema :type]))))))

;; -- Tool call tests --------------------------------------------------------

(deftest test-search-code-tool
  (h/with-temp-db
    (fn [db]
      (setup-test-data! db)
      (binding [tools/*db* db]
        (testing "returns matching results"
          (let [result (tools/call-tool "search_code" {:query "greet"})]
            (is
              (vector? (:content result)))
            (is
              (= "text" (:type (first (:content result)))))
            (is
              (str/includes? (get-in result [:content 0 :text]) "greet"))))

        (testing "returns no-results message for non-matching query"
          (let [result (tools/call-tool "search_code" {:query "zzz_nonexistent_zzz"})]
            (is
              (vector? (:content result)))
            (is
              (str/includes? (get-in result [:content 0 :text]) "No results"))))))))

(deftest test-find-references-tool
  (h/with-temp-db
    (fn [db]
      (setup-test-data! db)
      (binding [tools/*db* db]
        (testing "finds references to a symbol"
          (let [result (tools/call-tool "find_references" {:qualified_name "my.util/transform"})]
            (is
              (vector? (:content result)))
            (is
              (= "text" (:type (first (:content result)))))
            (is
              (str/includes? (get-in result [:content 0 :text]) "my.core"))))

        (testing "returns no-references for unknown symbol"
          (let [result (tools/call-tool "find_references" {:qualified_name "nonexistent/fn"})]
            (is
              (vector? (:content result)))))))))

(deftest test-find-dependents-tool
  (h/with-temp-db
    (fn [db]
      (setup-test-data! db)
      (binding [tools/*db* db]
        (testing "finds namespace dependents"
          (let [result (tools/call-tool "find_dependents" {:namespace "my.util"})]
            (is
              (vector? (:content result)))
            (is
              (str/includes? (get-in result [:content 0 :text]) "my.core"))))

        (testing "returns empty for unknown namespace"
          (let [result (tools/call-tool "find_dependents" {:namespace "nonexistent.ns"})]
            (is
              (vector? (:content result)))
            (is
              (str/includes? (get-in result [:content 0 :text]) "No dependents"))))))))

(deftest test-impact-analysis-tool
  (h/with-temp-db
    (fn [db]
      (setup-test-data! db)
      (binding [tools/*db* db]
        (testing "returns impact analysis with callers and dependents"
          (let [result (tools/call-tool "impact_analysis" {:qualified_name "my.core/greet"})]
            (is
              (vector? (:content result)))
            (is
              (= "text" (:type (first (:content result)))))
            ;; Should contain section headers
            (let [text (get-in result [:content 0 :text])]
              (is
                (str/includes? text "Callers"))
              (is
                (str/includes? text "Namespace Dependents"))
              (is
                (str/includes? text "Recent Commits")))))))))

(deftest test-search-history-tool
  (h/with-temp-db
    (fn [db]
      (setup-test-data! db)
      (binding [tools/*db* db]
        (testing "finds commits by message"
          (let [result (tools/call-tool "search_history" {:query "greet"})]
            (is
              (vector? (:content result)))
            (is
              (str/includes? (get-in result [:content 0 :text]) "abc123"))))

        (testing "returns no-matches for unknown query"
          (let [result (tools/call-tool "search_history" {:query "zzz_nonexistent_zzz"})]
            (is
              (vector? (:content result)))
            (is
              (str/includes? (get-in result [:content 0 :text]) "No matching"))))))))

(deftest test-unknown-tool
  (testing "returns error for unknown tool"
    (let [result (tools/call-tool "nonexistent_tool" {})]
      (is
        (:isError result)))))
