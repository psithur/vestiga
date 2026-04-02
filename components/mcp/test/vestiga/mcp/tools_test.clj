(ns vestiga.mcp.tools-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.interface.ops :as ops]
    [vestiga.mcp.tools :as tools]
    [vestiga.test-helpers :as h]))

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

(deftest test-search-code-tool
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
           :qualified-name "my.core/greet"
           :symbol-name    "greet"
           :kind           "defn"
           :content        "(defn greet [name] name)"
           :start-line     1
           :end-line       1
           :arity          nil
           :docstring      nil
           :file-hash      "h1"})
        (binding [tools/*db* db]
          (testing "search_code returns content"
            (let [result (tools/call-tool "search_code" {:query "greet"})]
              (is
                (vector? (:content result)))
              (is
                (= "text" (:type (first (:content result))))))))))))

(deftest test-unknown-tool
  (testing "returns error for unknown tool"
    (let [result (tools/call-tool "nonexistent_tool" {})]
      (is
        (:isError result)))))
