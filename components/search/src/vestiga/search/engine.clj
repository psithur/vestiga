(ns vestiga.search.engine
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vestiga.db.interface :as db]
    [vestiga.db.interface.ops :as ops]
    [vestiga.db.interface.search :as db-search]
    [vestiga.search.ranker :as ranker]))

(defn- looks-like-qualified-name?
  "Check if query looks like a qualified Clojure name (ns/symbol)."
  [query]
  (and
    (string? query)
    (re-matches #"[a-zA-Z][a-zA-Z0-9._-]*/[a-zA-Z][a-zA-Z0-9._*!?<>-]*" query)))

(defn search
  "Hybrid search across code and optionally git history."
  [db query &
   {:keys [limit kinds namespace file-path include-history project-id]
    :or   {limit           20
           include-history false}}]
  (let [;; 1. BM25 text search
        bm25-results       (try (db-search/bm25-search
                                  db
                                  query
                                  :project-id
                                  project-id
                                  :limit
                                  limit
                                  :kinds
                                  kinds
                                  :namespace
                                  namespace
                                  :file-path
                                  file-path)
                                (catch Exception e (log/warn "BM25 search failed:" (.getMessage e)) []))

        ;; 2. Structural match for qualified names
        structural-results (when (looks-like-qualified-name? query)
                             (db-search/find-by-qualified-name db query))

        ;; Combine results - use RRF if we have multiple sources
        bm25-with-id       (mapv #(assoc % :id (:id %)) bm25-results)
        structural-with-id (mapv #(assoc % :id (:id %)) (or structural-results []))

        result-lists       (cond-> []
                             (seq bm25-with-id)       (conj bm25-with-id)
                             (seq structural-with-id) (conj structural-with-id))

        fused              (if (> (count result-lists) 1)
                             (take limit (ranker/reciprocal-rank-fusion result-lists))
                             (take limit (or (first result-lists) [])))]

    (vec fused)))

(defn find-references
  "Find all references to a symbol. Structural query via refs table."
  [db qualified-name & {:keys [project-id]}]
  (if project-id
    (db-search/find-refs-to-symbol db project-id qualified-name)
    ;; If no project-id, search across all projects
    (let [projects (db/query db "SELECT id FROM projects" [])]
      (mapcat #(db-search/find-refs-to-symbol db (:id %) qualified-name) projects))))

(defn find-dependents
  "Find all namespaces that depend on the given namespace."
  [db namespace-name & {:keys [project-id]}]
  (if project-id
    (db-search/find-ns-dependents db project-id namespace-name)
    (let [projects (db/query db "SELECT id FROM projects" [])]
      (mapcat #(db-search/find-ns-dependents db (:id %) namespace-name) projects))))

(defn impact-analysis
  "Given a qualified name, find:
   1. Direct callers (from refs)
   2. Transitive namespace dependents
   3. Recent git commits touching the file"
  [db qualified-name & {:keys [project-id]}]
  (let [chunk          (first (db-search/find-by-qualified-name db qualified-name))
        pid            (or project-id (:project_id chunk))
        ns-name        (when qualified-name
                         (first (str/split qualified-name #"/")))
        callers        (when pid
                         (db-search/find-refs-to-symbol db pid qualified-name))
        ns-dependents  (when (and
                               pid
                               ns-name)
                         (db-search/find-ns-dependents db pid ns-name))
        recent-commits (when (and
                               pid
                               chunk)
                         (ops/get-commits-for-file db pid (:file_path chunk) :limit 10))]
    {:callers        (vec (or callers []))
     :ns-dependents  (vec (or ns-dependents []))
     :recent-commits (vec (or recent-commits []))}))
