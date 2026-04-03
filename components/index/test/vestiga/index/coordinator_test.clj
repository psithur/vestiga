(ns vestiga.index.coordinator-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.interface :as db]
    [vestiga.db.interface.ops :as ops]
    [vestiga.db.interface.search :as db-search]
    [vestiga.index.coordinator :as coordinator]
    [vestiga.test-helpers :as h]))

(defn- setup-sample-project!
  "Copy sample project fixtures into a temp git repo directory.
   Returns the dir path."
  [dir]
  (let [fixture-root (io/file (io/resource "vestiga/fixtures/sample_project/src"))
        dest-root    (io/file dir "src")]
    ;; Copy fixture src/ tree into dir/src/
    (doseq [^java.io.File f (file-seq fixture-root)
            :when (.isFile f)]
      (let [rel  (.relativize (.toPath fixture-root) (.toPath f))
            dest (io/file dest-root (str rel))]
        (.mkdirs (.getParentFile dest))
        (io/copy f dest)))
    ;; Create deps.edn
    (spit (str dir "/deps.edn") "{:paths [\"src\"]}")
    ;; Git commit
    (h/git-shell dir "git" "add" ".")
    (h/git-shell dir "git" "commit" "-m" "Initial commit")))

(deftest ^:integration test-full-indexing-pipeline
  (testing "indexes a project and makes it searchable via BM25"
    (h/with-temp-git-repo
      setup-sample-project!
      (fn [dir]
        (h/with-temp-db
          (fn [db]
            ;; Index with skip-embeddings since we don't have Ollama in CI
            (coordinator/index-project! db dir :skip-embeddings true :full true)

            ;; Verify project was created
            (let [project (ops/get-project db (.getAbsolutePath (io/file dir)))]
              (is
                (some? project))
              (is
                (some? (:head_sha project)))
              (is
                (some? (:last_indexed_at project))))

            ;; Verify chunks were created
            (let [project   (ops/get-project db (.getAbsolutePath (io/file dir)))
                  pid       (:id project)
                  all-files (db/query db "SELECT DISTINCT file_path FROM chunks WHERE project_id = ?" [pid])]
              (is
                (>= (count all-files) 3)
                "Should index at least 3 source files"))

            ;; Verify BM25 search works
            (let [results (db-search/bm25-search db "greet")]
              (is
                (pos? (count results)))
              (is
                (some #(= "sample.core/greet" (:qualified_name %)) results)))

            ;; Verify symbol search works
            (let [results (db-search/find-by-qualified-name db "sample.core/greet")]
              (is
                (= 1 (count results)))
              (is
                (= "defn" (:kind (first results)))))

            ;; Verify refs were indexed
            (let [project (ops/get-project db (.getAbsolutePath (io/file dir)))
                  pid     (:id project)
                  refs    (db-search/find-refs-to-symbol db pid "sample.util/transform-item")]
              (is
                (pos? (count refs))
                "Should find references to transform-item from sample.core"))

            ;; Verify ns-deps were indexed
            (let [project (ops/get-project db (.getAbsolutePath (io/file dir)))
                  pid     (:id project)
                  deps    (db-search/find-ns-dependents db pid "sample.util")]
              (is
                (pos? (count deps))
                "sample.core should depend on sample.util"))

            ;; Verify git history was indexed
            (let [results (db-search/search-commits db "Initial commit")]
              (is
                (pos? (count results))
                "Should find the initial commit"))))))))

(deftest ^:integration test-incremental-indexing
  (testing "only reindexes changed files on incremental run"
    (h/with-temp-git-repo
      setup-sample-project!
      (fn [dir]
        (h/with-temp-db
          (fn [db]
            ;; Full initial index
            (coordinator/index-project! db dir :skip-embeddings true :full true)

            (let [project (ops/get-project db (.getAbsolutePath (io/file dir)))
                  pid     (:id project)]

              ;; Verify initial state
              (let [chunks (ops/get-chunks-for-file db pid "src/sample/util.clj")]
                (is
                  (pos? (count chunks))
                  "util.clj should have chunks"))

              ;; Modify one file
              (spit
                (str dir "/src/sample/util.clj")
                "(ns sample.util)\n\n(defn transform-item\n  \"Transform updated.\"\n  [item]\n  (assoc item :processed true :updated true))\n\n(defn valid?\n  [item]\n  (and (:name item) (not (:deleted item))))\n\n(defn format-output\n  [item]\n  (str (:name item) \" - \" (if (:processed item) \"done\" \"pending\")))\n\n(defn new-fn\n  \"A brand new function.\"\n  [x]\n  (inc x))\n")
              (h/git-shell dir "git" "add" ".")
              (h/git-shell dir "git" "commit" "-m" "Update util.clj")

              ;; Incremental re-index (not full)
              (coordinator/index-project! db dir :skip-embeddings true)

              ;; Verify the modified file was reindexed
              (let [results (db-search/bm25-search db "new fn")]
                (is
                  (pos? (count results))
                  "Should find the newly added function"))

              ;; Verify the new git commit was indexed
              (let [results (db-search/search-commits db "Update util")]
                (is
                  (pos? (count results))
                  "Should find the update commit"))

              ;; Verify project head was updated
              (let [project (ops/get-project db (.getAbsolutePath (io/file dir)))
                    head    (clojure.string/trim
                              (:out
                                (babashka.process/shell
                                  {:dir dir
                                   :out :string}
                                  "git"
                                  "rev-parse"
                                  "HEAD")))]
                (is
                  (= head (:head_sha project))
                  "Project head_sha should match current git HEAD")))))))))

(deftest ^:integration test-incremental-skips-unchanged-files
  (testing "unchanged files are not reindexed"
    (h/with-temp-git-repo
      setup-sample-project!
      (fn [dir]
        (h/with-temp-db
          (fn [db]
            ;; Full initial index
            (coordinator/index-project! db dir :skip-embeddings true :full true)

            (let [project (ops/get-project db (.getAbsolutePath (io/file dir)))
                  pid (:id project)
                  ;; Get the indexed_at timestamp for a file we won't change
                  core-chunks-before (ops/get-chunks-for-file db pid "src/sample/core.clj")
                  core-ts-before (:indexed_at (first core-chunks-before))]

              ;; Modify only util.clj
              (spit
                (str dir "/src/sample/util.clj")
                (str (slurp (str dir "/src/sample/util.clj")) "\n(defn extra [] :extra)\n"))
              (h/git-shell dir "git" "add" ".")
              (h/git-shell dir "git" "commit" "-m" "Add extra fn")

              ;; Incremental re-index
              (coordinator/index-project! db dir :skip-embeddings true)

              ;; core.clj chunks should have the same indexed_at timestamp
              (let [core-chunks-after (ops/get-chunks-for-file db pid "src/sample/core.clj")
                    core-ts-after     (:indexed_at (first core-chunks-after))]
                (is
                  (= core-ts-before core-ts-after)
                  "core.clj should not be reindexed if unchanged")))))))))
