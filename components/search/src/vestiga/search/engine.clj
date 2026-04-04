(ns vestiga.search.engine
  (:require
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vestiga.db.interface :as db]
    [vestiga.db.interface.ops :as ops]
    [vestiga.db.interface.search :as db-search]
    [vestiga.search.ranker :as ranker])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn- looks-like-qualified-name?
  "Check if query looks like a qualified Clojure name (ns/symbol)."
  [query]
  (and
    (string? query)
    (re-matches #"[a-zA-Z][a-zA-Z0-9._-]*/[a-zA-Z][a-zA-Z0-9._*!?<>-]*" query)))

(defn- query-embedding
  "Get an embedding for a search query via Ollama.
   Returns a float vector or nil on failure."
  [query base-url model]
  (try (let [client  (-> (HttpClient/newBuilder)
                         (.connectTimeout (Duration/ofSeconds 10))
                         (.build))
             body    (json/write-str
                       {:model model
                        :input [query]})
             request (-> (HttpRequest/newBuilder)
                         (.uri (URI/create (str base-url "/api/embed")))
                         (.header "Content-Type" "application/json")
                         (.timeout (Duration/ofSeconds 30))
                         (.POST (HttpRequest$BodyPublishers/ofString body))
                         (.build))
             resp    (.send client request (HttpResponse$BodyHandlers/ofString))]
         (when (= 200 (.statusCode resp))
           (let [parsed (json/read-str (.body resp) :key-fn keyword)]
             (first (:embeddings parsed)))))
       (catch Exception e (log/debug "Failed to get query embedding:" (.getMessage e)) nil)))

(defn search
  "Hybrid search across code and optionally git history.
   When vec0 is available and Ollama is running, includes vector similarity results
   fused with BM25 via Reciprocal Rank Fusion."
  [db query &
   {:keys [limit kinds namespace file-path include-history project-id embed-model embed-base-url]
    :or   {limit           20
           include-history false
           embed-model     "nomic-embed-text"
           embed-base-url  "http://localhost:11434"}}]
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

        ;; 3. Vector search (if vec0 available)
        vector-results     (when (:vec? db)
                             (when-let [embedding (query-embedding query embed-base-url embed-model)]
                               (try (db-search/vector-search
                                      db
                                      embedding
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
                                    (catch Exception e (log/debug "Vector search failed:" (.getMessage e)) nil))))

        ;; Combine results - use RRF if we have multiple sources
        bm25-with-id       (mapv #(assoc % :id (:id %)) bm25-results)
        structural-with-id (mapv #(assoc % :id (:id %)) (or structural-results []))
        vector-with-id     (mapv #(assoc % :id (:id %)) (or vector-results []))

        result-lists       (cond-> []
                             (seq bm25-with-id)       (conj bm25-with-id)
                             (seq structural-with-id) (conj structural-with-id)
                             (seq vector-with-id)     (conj vector-with-id))

        fused              (if (> (count result-lists) 1)
                             (take limit (ranker/reciprocal-rank-fusion result-lists))
                             (take limit (or (first result-lists) [])))]

    (vec fused)))

(defn search-conversations
  "Hybrid search across conversation messages.
   Fuses BM25 text search with vector similarity via RRF when vec0 is available."
  [db query &
   {:keys [limit role tool-name embed-model embed-base-url]
    :or   {limit          20
           embed-model    "nomic-embed-text"
           embed-base-url "http://localhost:11434"}}]
  (let [bm25-results   (try (db-search/search-conversations db query :limit limit :role role :tool-name tool-name)
                            (catch Exception _ []))
        vector-results (when (:vec? db)
                         (when-let [embedding (query-embedding query embed-base-url embed-model)]
                           (try (db-search/vector-search-conversations
                                  db
                                  embedding
                                  :limit
                                  limit
                                  :role
                                  role
                                  :tool-name
                                  tool-name)
                                (catch Exception _ nil))))
        bm25-with-id   (mapv #(assoc % :id (:id %)) bm25-results)
        vector-with-id (mapv #(assoc % :id (:id %)) (or vector-results []))
        result-lists   (cond-> []
                         (seq bm25-with-id)   (conj bm25-with-id)
                         (seq vector-with-id) (conj vector-with-id))
        fused          (if (> (count result-lists) 1)
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
