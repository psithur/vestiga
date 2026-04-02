(ns vestiga.index.coordinator
  (:require
    [clojure.tools.logging :as log]
    [vestiga.db.interface :as db]
    [vestiga.db.interface.ops :as ops]
    [vestiga.index.chunker :as chunker]
    [vestiga.index.clj-kondo :as kondo]
    [vestiga.index.file-tracker :as tracker]
    [vestiga.index.git :as git]))

(defn- index-file!
  "Index a single source file: chunk it, correlate with symbols, store in DB."
  [db project-id {:keys [absolute-path relative-path]} symbols file-hash]
  (let [source       (slurp absolute-path)
        chunks       (chunker/split-top-level-forms source)
        file-symbols (filter #(= (:file-path %) relative-path) symbols)
        correlated   (chunker/correlate-chunks-with-symbols chunks file-symbols)]
    ;; Delete existing chunks for this file
    (ops/delete-chunks-for-file! db project-id relative-path)
    ;; Insert new chunks
    (doseq [chunk correlated]
      (ops/insert-chunk!
        db
        {:project-id     project-id
         :file-path      relative-path
         :namespace      (:namespace chunk)
         :qualified-name (:qualified-name chunk)
         :symbol-name    (:symbol-name chunk)
         :kind           (:kind chunk)
         :content        (:content chunk)
         :start-line     (:start-line chunk)
         :end-line       (:end-line chunk)
         :arity          (:arity chunk)
         :docstring      (:docstring chunk)
         :file-hash      file-hash}))))

(defn- index-refs!
  "Store symbol references from kondo analysis."
  [db project-id refs]
  (doseq [ref refs]
    (ops/insert-ref! db (assoc ref :project-id project-id))))

(defn- index-ns-deps!
  "Store namespace dependencies from kondo analysis."
  [db project-id ns-deps]
  (doseq [dep ns-deps]
    (ops/insert-ns-dep! db (assoc dep :project-id project-id))))

(defn- index-git-history!
  "Index git commit history."
  [db project-id project-root & {:keys [since-sha max-count]}]
  (let [commits (git/git-log project-root :since-sha since-sha :max-count max-count)]
    (when commits
      (log/info "Indexing" (count commits) "git commits")
      (doseq [commit commits]
        (when-let [commit-id
                   (ops/insert-commit!
                     db
                     {:project-id project-id
                      :sha        (:sha commit)
                      :author     (:author commit)
                      :timestamp  (:timestamp commit)
                      :message    (:message commit)})]
          (doseq [file (:files commit)]
            (ops/insert-commit-file!
              db
              {:commit-id     commit-id
               :file-path     (:path file)
               :change-type   (:change-type file)
               :lines-added   (:lines-added file)
               :lines-removed (:lines-removed file)})))))))

(defn index-project!
  "Index a project: run kondo analysis, chunk files, store in DB.
   Options:
     :skip-embeddings - skip vector embedding generation
     :full            - force full re-index (ignore file hashes)
     :config          - configuration map"
  [db project-root &
   {:keys [skip-embeddings full config]
    :or   {config {}}}]
  (let [root-file    (java.io.File. ^String project-root)
        root-path    (.getAbsolutePath root-file)
        project-name (.getName root-file)
        project-id   (ops/upsert-project!
                       db
                       {:root-path root-path
                        :name      project-name})
        project      (ops/get-project db root-path)
        index-paths  (or (:index-paths config) ["src" "test"])
        extensions   (or (:file-extensions config) #{".clj" ".cljs" ".cljc" ".bb"})
        max-commits  (or (:git-max-commits config) 10000)]

    (log/info "Indexing project" project-name "at" root-path)

    ;; Run clj-kondo analysis
    (log/info "Running clj-kondo analysis on" index-paths)
    (let [kondo-result (kondo/run-analysis root-path index-paths)]
      (if (:error kondo-result)
        (log/error "clj-kondo analysis failed:" (:error kondo-result))
        (let [analysis       (:analysis kondo-result)
              symbols        (kondo/analysis->symbols analysis)
              refs           (kondo/analysis->refs analysis)
              ns-deps        (kondo/analysis->ns-deps analysis)
              files          (tracker/find-source-files project-root index-paths extensions)
              files-to-index (if full files (tracker/files-needing-reindex files db project-id))]

          (log/info
            "Found"
            (count files)
            "source files,"
            (count files-to-index)
            "need indexing,"
            (count symbols)
            "symbols,"
            (count refs)
            "references")

          ;; Index files within a transaction
          (db/with-transaction
            db
            (fn [tx-db]
              ;; Clear refs and ns-deps for full reindex
              (when full
                (ops/delete-refs-for-project! tx-db project-id)
                (ops/delete-ns-deps-for-project! tx-db project-id))

              ;; Index each file
              (doseq [file files-to-index]
                (let [hash (tracker/file-hash (:absolute-path file))]
                  (index-file! tx-db project-id file symbols hash)))

              ;; Index refs and ns-deps
              (index-refs! tx-db project-id refs)
              (index-ns-deps! tx-db project-id ns-deps))))))

    ;; Index git history
    (let [head-sha  (git/git-head-sha root-path)
          since-sha (when-not full (:head_sha project))]
      (when head-sha
        (index-git-history! db project-id root-path :since-sha since-sha :max-count max-commits)
        (ops/update-project-head! db project-id head-sha)))

    (log/info "Indexing complete for" project-name)))
