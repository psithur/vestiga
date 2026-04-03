(ns vestiga.mcp.tools-integration-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.interface :as db]
    [vestiga.db.interface.ops :as ops]
    [vestiga.db.interface.search :as db-search]
    [vestiga.mcp.tools :as tools]
    [vestiga.test-helpers :as h]))

(defn- setup-sample-project!
  "Copy sample project fixtures into a temp git repo directory."
  [dir]
  (let [fixture-root (io/file (io/resource "vestiga/fixtures/sample_project/src"))
        dest-root    (io/file dir "src")]
    (doseq [^java.io.File f (file-seq fixture-root)
            :when (.isFile f)]
      (let [rel  (.relativize (.toPath fixture-root) (.toPath f))
            dest (io/file dest-root (str rel))]
        (.mkdirs (.getParentFile dest))
        (io/copy f dest)))
    (spit (str dir "/deps.edn") "{:paths [\"src\"]}")
    (h/git-shell dir "git" "add" ".")
    (h/git-shell dir "git" "commit" "-m" "Initial commit")))

(deftest ^:integration test-index-project-tool-uses-server-db
  (testing "index_project MCP tool indexes into the bound *db*, not a separate DB"
    (h/with-temp-git-repo
      setup-sample-project!
      (fn [dir]
        (h/with-temp-db
          (fn [db]
            ;; Bind the test DB as the MCP server's DB
            (binding [tools/*db* db]
              ;; Call the index_project tool
              (let [result (tools/call-tool
                             "index_project"
                             {:project_root    dir
                              :full            true
                              :skip_embeddings true})]
                (is
                  (not (:isError result)))
                (is
                  (str/includes? (get-in result [:content 0 :text]) "Indexing complete")))

              ;; Now verify search works on the same DB
              (let [result (tools/call-tool "search_code" {:query "greet"})]
                (is
                  (not (:isError result)))
                (is
                  (str/includes? (get-in result [:content 0 :text]) "greet"))
                (is
                  (not (str/includes? (get-in result [:content 0 :text]) "No results"))))

              ;; Verify find_references works
              (let [result (tools/call-tool "find_references" {:qualified_name "sample.util/transform-item"})]
                (is
                  (not (:isError result)))
                (is
                  (str/includes? (get-in result [:content 0 :text]) "sample.core")))

              ;; Verify find_dependents works
              (let [result (tools/call-tool "find_dependents" {:namespace "sample.util"})]
                (is
                  (not (:isError result)))
                (is
                  (str/includes? (get-in result [:content 0 :text]) "sample.core")))

              ;; Verify impact_analysis works
              (let [result (tools/call-tool "impact_analysis" {:qualified_name "sample.core/greet"})]
                (is
                  (not (:isError result)))
                (let [text (get-in result [:content 0 :text])]
                  (is
                    (str/includes? text "Callers"))
                  (is
                    (str/includes? text "Namespace Dependents"))))

              ;; Verify search_history works
              (let [result (tools/call-tool "search_history" {:query "Initial commit"})]
                (is
                  (not (:isError result)))
                (is
                  (str/includes? (get-in result [:content 0 :text]) "Initial commit"))))))))))

(deftest ^:integration test-index-project-tool-incremental
  (testing "index_project MCP tool supports incremental indexing"
    (h/with-temp-git-repo
      setup-sample-project!
      (fn [dir]
        (h/with-temp-db
          (fn [db]
            (binding [tools/*db* db]
              ;; Full initial index
              (tools/call-tool
                "index_project"
                {:project_root    dir
                 :full            true
                 :skip_embeddings true})

              ;; Add a new file
              (spit
                (str dir "/src/sample/extra.clj")
                "(ns sample.extra)\n\n(defn bonus-fn\n  \"An extra function.\"\n  [x]\n  (* x 2))\n")
              (h/git-shell dir "git" "add" ".")
              (h/git-shell dir "git" "commit" "-m" "Add extra module")

              ;; Incremental re-index
              (tools/call-tool
                "index_project"
                {:project_root    dir
                 :skip_embeddings true})

              ;; Should find the new function
              (let [result (tools/call-tool "search_code" {:query "bonus fn"})]
                (is
                  (str/includes? (get-in result [:content 0 :text]) "bonus-fn"))))))))))
