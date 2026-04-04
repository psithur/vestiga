(ns vestiga.db.search
  (:require
    [clojure.string :as str]
    [vestiga.db.connection :as db]))

(defn- sanitize-fts-query
  "Sanitize a query string for FTS5 MATCH.
   Replaces hyphens with spaces (hyphens mean NOT in FTS5)
   and wraps terms in double quotes to avoid syntax errors."
  [query-text]
  (let [cleaned (-> query-text
                    (str/replace #"-" " ")
                    (str/replace #"[\"()]" "")
                    str/trim)]
    (if (str/blank? cleaned)
      cleaned
      (->> (str/split cleaned #"\s+")
           (map #(str "\"" % "\""))
           (str/join " ")))))

(defn bm25-search
  "Search chunks using FTS5 BM25 ranking.
   Returns chunks ordered by relevance."
  [db query-text &
   {:keys [project-id limit kinds namespace file-path]
    :or   {limit 20}}]
  (let
    [base-sql
     "SELECT c.*, bm25(chunks_fts, 1.0, 2.0, 3.0, 2.0, 1.5) as rank
                   FROM chunks_fts fts
                   JOIN chunks c ON c.id = fts.rowid
                   WHERE chunks_fts MATCH ?"
     conditions (cond-> []
                  project-id (conj "c.project_id = ?")
                  kinds      (conj (str "c.kind IN (" (str/join "," (repeat (count kinds) "?")) ")"))
                  namespace  (conj "c.namespace LIKE ?")
                  file-path  (conj "c.file_path LIKE ?"))
     where (if (seq conditions) (str " AND " (str/join " AND " conditions)) "")
     sql (str base-sql where " ORDER BY rank LIMIT ?")
     fts-query (sanitize-fts-query query-text)
     params (cond-> [fts-query]
              project-id (conj project-id)
              kinds      (into (vec kinds))
              namespace  (conj (str/replace namespace "*" "%"))
              file-path  (conj (str/replace file-path "*" "%"))
              true       (conj limit))]
    (db/query db sql params)))

(defn search-commits
  "Search commit messages using FTS5."
  [db query-text &
   {:keys [project-id limit file-path]
    :or   {limit 20}}]
  (let
    [base-sql
     (if file-path
       "SELECT c.*, bm25(commits_fts) as rank
                    FROM commits_fts fts
                    JOIN commits c ON c.id = fts.rowid
                    JOIN commit_files cf ON c.id = cf.commit_id
                    WHERE commits_fts MATCH ? AND cf.file_path LIKE ?"
       "SELECT c.*, bm25(commits_fts) as rank
                    FROM commits_fts fts
                    JOIN commits c ON c.id = fts.rowid
                    WHERE commits_fts MATCH ?")
     conditions (when project-id
                  ["c.project_id = ?"])
     where (if (seq conditions) (str " AND " (str/join " AND " conditions)) "")
     sql (str base-sql where " ORDER BY rank LIMIT ?")
     fts-query (sanitize-fts-query query-text)
     params (cond-> [fts-query]
              file-path  (conj (str/replace file-path "*" "%"))
              project-id (conj project-id)
              true       (conj limit))]
    (db/query db sql params)))

(defn search-patches
  "Search diff content in commit file patches using FTS5.
   Returns commit info joined with the matching file path and patch snippet."
  [db query-text &
   {:keys [project-id limit file-path]
    :or   {limit 20}}]
  (let
    [base-sql
     "SELECT c.sha, c.author, c.timestamp, c.message,
                         cf.file_path, cf.change_type, cf.lines_added, cf.lines_removed,
                         snippet(commit_patches_fts, 1, '>>>', '<<<', '...', 40) as patch_snippet,
                         bm25(commit_patches_fts) as rank
                  FROM commit_patches_fts pfts
                  JOIN commit_files cf ON cf.id = pfts.rowid
                  JOIN commits c ON c.id = cf.commit_id
                  WHERE commit_patches_fts MATCH ?"
     conditions (cond-> []
                  project-id (conj "c.project_id = ?")
                  file-path  (conj "cf.file_path LIKE ?"))
     where (if (seq conditions) (str " AND " (str/join " AND " conditions)) "")
     sql (str base-sql where " ORDER BY rank LIMIT ?")
     fts-query (sanitize-fts-query query-text)
     params (cond-> [fts-query]
              project-id (conj project-id)
              file-path  (conj (str/replace file-path "*" "%"))
              true       (conj limit))]
    (try (db/query db sql params) (catch Exception _ []))))

(defn find-by-qualified-name
  "Exact lookup by qualified name."
  [db qualified-name]
  (db/query db "SELECT * FROM chunks WHERE qualified_name = ?" [qualified-name]))

(defn find-by-namespace
  "Find all chunks in a namespace."
  [db project-id namespace-name]
  (db/query
    db
    "SELECT * FROM chunks WHERE project_id = ? AND namespace = ? ORDER BY start_line"
    [project-id namespace-name]))

(defn find-refs-to-symbol
  "Find all references to a qualified symbol."
  [db project-id qualified-name]
  (let [[ns-part name-part] (str/split qualified-name #"/" 2)]
    (when (and
            ns-part
            name-part)
      (db/query
        db
        "SELECT r.*, c.content, c.file_path as caller_file, c.qualified_name as caller_qname
                 FROM refs r
                 LEFT JOIN chunks c ON c.project_id = r.project_id
                   AND c.file_path = r.file_path
                   AND c.start_line <= r.row AND c.end_line >= r.row
                 WHERE r.project_id = ? AND r.to_ns = ? AND r.to_name = ?"
        [project-id ns-part name-part]))))

(defn vector-search
  "Search chunks using KNN vector similarity via sqlite-vec.
   query-embedding: a float array or vector of floats.
   Returns chunks ordered by distance (ascending = most similar)."
  [db query-embedding &
   {:keys [project-id limit kinds namespace file-path]
    :or   {limit 20}}]
  (when (:vec? db)
    (let
      [;; Convert embedding to JSON array string for vec0
       embed-json (str "[" (str/join "," (map str query-embedding)) "]")
       base-sql
       "SELECT c.*, ce.distance as vec_distance
                      FROM chunk_embeddings ce
                      JOIN chunks c ON c.id = ce.id
                      WHERE ce.embedding MATCH ?"
       conditions (cond-> []
                    project-id (conj "c.project_id = ?")
                    kinds      (conj (str "c.kind IN (" (str/join "," (repeat (count kinds) "?")) ")"))
                    namespace  (conj "c.namespace LIKE ?")
                    file-path  (conj "c.file_path LIKE ?"))
       where (if (seq conditions) (str " AND " (str/join " AND " conditions)) "")
       sql (str base-sql where " ORDER BY ce.distance LIMIT ?")
       params (cond-> [embed-json]
                project-id (conj project-id)
                kinds      (into (vec kinds))
                namespace  (conj (str/replace namespace "*" "%"))
                file-path  (conj (str/replace file-path "*" "%"))
                true       (conj limit))]
      (try (db/query db sql params) (catch Exception _ [])))))

;; -- Conversation Search -----------------------------------------------------

(defn search-conversations
  "Search conversation messages using FTS5.
   Returns matching messages with session context."
  [db query-text &
   {:keys [limit role tool-name]
    :or   {limit 20}}]
  (let
    [base-sql
     "SELECT cm.*, cs.session_id, cs.title, cs.project_path, cs.provider,
                snippet(conversation_messages_fts, 0, '>>>', '<<<', '...', 40) as content_snippet,
                bm25(conversation_messages_fts) as rank
         FROM conversation_messages_fts fts
         JOIN conversation_messages cm ON cm.id = fts.rowid
         JOIN conversation_sessions cs ON cs.id = cm.session_row_id
         WHERE conversation_messages_fts MATCH ?"
     conditions (cond-> []
                  role      (conj "cm.role = ?")
                  tool-name (conj "cm.tool_names LIKE ?"))
     where (if (seq conditions) (str " AND " (str/join " AND " conditions)) "")
     sql (str base-sql where " ORDER BY rank LIMIT ?")
     fts-query (sanitize-fts-query query-text)
     params (cond-> [fts-query]
              role      (conj role)
              tool-name (conj (str "%" tool-name "%"))
              true      (conj limit))]
    (try (db/query db sql params) (catch Exception _ []))))

(defn vector-search-conversations
  "Search conversation messages using KNN vector similarity.
   Returns messages ordered by distance (ascending = most similar)."
  [db query-embedding &
   {:keys [limit role tool-name]
    :or   {limit 20}}]
  (when (:vec? db)
    (let
      [embed-json (str "[" (str/join "," (map str query-embedding)) "]")
       base-sql
       "SELECT cm.*, cs.session_id, cs.title, cs.project_path, cs.provider,
                  cme.distance as vec_distance
           FROM conversation_message_embeddings cme
           JOIN conversation_messages cm ON cm.id = cme.id
           JOIN conversation_sessions cs ON cs.id = cm.session_row_id
           WHERE cme.embedding MATCH ?"
       conditions (cond-> []
                    role      (conj "cm.role = ?")
                    tool-name (conj "cm.tool_names LIKE ?"))
       where (if (seq conditions) (str " AND " (str/join " AND " conditions)) "")
       sql (str base-sql where " ORDER BY cme.distance LIMIT ?")
       params (cond-> [embed-json]
                role      (conj role)
                tool-name (conj (str "%" tool-name "%"))
                true      (conj limit))]
      (try (db/query db sql params) (catch Exception _ [])))))

(defn list-conversation-sessions
  "List conversation sessions, newest first."
  [db &
   {:keys [limit provider]
    :or   {limit 20}}]
  (let [base-sql "SELECT * FROM conversation_sessions"
        where    (if provider " WHERE provider = ?" "")
        sql      (str base-sql where " ORDER BY started_at DESC LIMIT ?")
        params   (if provider [provider limit] [limit])]
    (db/query db sql params)))

(defn find-ns-dependents
  "Find all namespaces that depend on the given namespace."
  [db project-id namespace-name]
  (db/query db "SELECT DISTINCT from_ns FROM ns_deps WHERE project_id = ? AND to_ns = ?" [project-id namespace-name]))

(defn hotspots
  "Find the most frequently changed files.
   Returns [{:file_path, :edit_count, :total_added, :total_removed} ...]
   ordered by edit count descending."
  [db &
   {:keys [project-id limit since namespace]
    :or   {limit 20}}]
  (let
    [base-sql
     "SELECT cf.file_path,
                         COUNT(*) as edit_count,
                         SUM(cf.lines_added) as total_added,
                         SUM(cf.lines_removed) as total_removed
                  FROM commit_files cf
                  JOIN commits c ON c.id = cf.commit_id"
     conditions (cond-> []
                  project-id (conj "c.project_id = ?")
                  since      (conj "c.timestamp >= ?")
                  namespace  (conj "cf.file_path LIKE ?"))
     where (if (seq conditions) (str " WHERE " (str/join " AND " conditions)) "")
     sql (str base-sql where " GROUP BY cf.file_path ORDER BY edit_count DESC LIMIT ?")
     params (cond-> []
              project-id (conj project-id)
              since      (conj since)
              namespace  (conj (str "%" (str/replace namespace "." "/") "%"))
              true       (conj limit))]
    (db/query db sql params)))
