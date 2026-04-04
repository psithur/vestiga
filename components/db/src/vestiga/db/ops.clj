(ns vestiga.db.ops
  (:require
    [clojure.data.json :as json]
    [vestiga.db.connection :as db]))

;; -- Projects ---------------------------------------------------------------

(defn upsert-project!
  "Insert or update a project. Returns the project id."
  [db {:keys [root-path name]}]
  (let [existing (db/query db "SELECT id FROM projects WHERE root_path = ?" [root-path])]
    (if (seq existing)
      (:id (first existing))
      (db/execute-returning-key! db "INSERT INTO projects (root_path, name) VALUES (?, ?)" [root-path name]))))

(defn update-project-head!
  "Update the HEAD SHA and last-indexed timestamp for a project."
  [db project-id head-sha]
  (db/execute!
    db
    "UPDATE projects SET head_sha = ?, last_indexed_at = datetime('now') WHERE id = ?"
    [head-sha project-id]))

(defn get-project
  "Get a project by root path."
  [db root-path]
  (first (db/query db "SELECT * FROM projects WHERE root_path = ?" [root-path])))

;; -- Chunks -----------------------------------------------------------------

(defn insert-chunk!
  "Insert a source chunk. Returns the chunk id."
  [db
   {:keys [project-id file-path namespace qualified-name symbol-name kind content start-line end-line arity docstring
           file-hash]}]
  (db/execute-returning-key!
    db
    "INSERT INTO chunks (project_id, file_path, namespace, qualified_name, symbol_name,
                         kind, content, start_line, end_line, arity, docstring, file_hash)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    [project-id file-path namespace qualified-name symbol-name kind content start-line end-line arity docstring
     file-hash]))

(defn delete-chunks-for-file!
  "Delete all chunks for a given file in a project."
  [db project-id file-path]
  (db/execute! db "DELETE FROM chunks WHERE project_id = ? AND file_path = ?" [project-id file-path]))

(defn delete-chunks-for-project!
  "Delete all chunks for a project."
  [db project-id]
  (db/execute! db "DELETE FROM chunks WHERE project_id = ?" [project-id]))

(defn get-chunks-for-file
  "Get all chunks for a file."
  [db project-id file-path]
  (db/query
    db
    "SELECT * FROM chunks WHERE project_id = ? AND file_path = ? ORDER BY start_line"
    [project-id file-path]))

(defn get-chunk-by-qname
  "Get a chunk by qualified name."
  [db qualified-name]
  (first (db/query db "SELECT * FROM chunks WHERE qualified_name = ?" [qualified-name])))

(defn get-file-hash
  "Get the stored file hash for a file in a project."
  [db project-id file-path]
  (:file_hash
    (first
      (db/query
        db
        "SELECT file_hash FROM chunks WHERE project_id = ? AND file_path = ? LIMIT 1"
        [project-id file-path]))))

;; -- Refs -------------------------------------------------------------------

(defn insert-ref!
  "Insert a symbol reference."
  [db {:keys [project-id from-ns from-name to-ns to-name file-path row col]}]
  (db/execute!
    db
    "INSERT OR IGNORE INTO refs (project_id, from_ns, from_name, to_ns, to_name, file_path, row, col)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
    [project-id from-ns from-name to-ns to-name file-path row col]))

(defn delete-refs-for-file!
  "Delete all refs from a given file."
  [db project-id file-path]
  (db/execute! db "DELETE FROM refs WHERE project_id = ? AND file_path = ?" [project-id file-path]))

(defn delete-refs-for-project!
  "Delete all refs for a project."
  [db project-id]
  (db/execute! db "DELETE FROM refs WHERE project_id = ?" [project-id]))

(defn get-refs-to
  "Find all references to a symbol (callers)."
  [db project-id to-ns to-name]
  (db/query db "SELECT * FROM refs WHERE project_id = ? AND to_ns = ? AND to_name = ?" [project-id to-ns to-name]))

(defn get-refs-from
  "Find all references from a symbol (callees)."
  [db project-id from-ns from-name]
  (db/query
    db
    "SELECT * FROM refs WHERE project_id = ? AND from_ns = ? AND from_name = ?"
    [project-id from-ns from-name]))

;; -- Namespace Dependencies -------------------------------------------------

(defn insert-ns-dep!
  "Insert a namespace dependency."
  [db {:keys [project-id from-ns to-ns]}]
  (db/execute!
    db
    "INSERT OR IGNORE INTO ns_deps (project_id, from_ns, to_ns) VALUES (?, ?, ?)"
    [project-id from-ns to-ns]))

(defn delete-ns-deps-for-project!
  "Delete all namespace deps for a project."
  [db project-id]
  (db/execute! db "DELETE FROM ns_deps WHERE project_id = ?" [project-id]))

(defn get-ns-dependents
  "Find all namespaces that depend on the given namespace."
  [db project-id to-ns]
  (db/query db "SELECT from_ns FROM ns_deps WHERE project_id = ? AND to_ns = ?" [project-id to-ns]))

(defn get-ns-dependencies
  "Find all namespaces that the given namespace depends on."
  [db project-id from-ns]
  (db/query db "SELECT to_ns FROM ns_deps WHERE project_id = ? AND from_ns = ?" [project-id from-ns]))

;; -- Commits ----------------------------------------------------------------

(defn insert-commit!
  "Insert a git commit. Returns the commit id."
  [db {:keys [project-id sha author timestamp message]}]
  (db/execute-returning-key!
    db
    "INSERT OR IGNORE INTO commits (project_id, sha, author, timestamp, message)
     VALUES (?, ?, ?, ?, ?)"
    [project-id sha author timestamp message]))

(defn insert-commit-file!
  "Insert a commit-file association with optional patch diff."
  [db {:keys [commit-id file-path change-type lines-added lines-removed patch]}]
  (db/execute!
    db
    "INSERT INTO commit_files (commit_id, file_path, change_type, lines_added, lines_removed, patch)
                VALUES (?, ?, ?, ?, ?, ?)"
    [commit-id file-path change-type lines-added lines-removed patch]))

(defn get-commits-for-file
  "Get recent commits touching a file."
  [db project-id file-path &
   {:keys [limit]
    :or   {limit 20}}]
  (db/query
    db
    "SELECT c.* FROM commits c
             JOIN commit_files cf ON c.id = cf.commit_id
             WHERE c.project_id = ? AND cf.file_path = ?
             ORDER BY c.timestamp DESC LIMIT ?"
    [project-id file-path limit]))

(defn get-latest-commit-sha
  "Get the SHA of the most recent indexed commit for a project."
  [db project-id]
  (:sha
    (first (db/query db "SELECT sha FROM commits WHERE project_id = ? ORDER BY timestamp DESC LIMIT 1" [project-id]))))

;; -- Conversation Sources ----------------------------------------------------

(defn upsert-conversation-source!
  "Insert or update a conversation source. Returns the source id."
  [db {:keys [source-path provider project-path last-line-count]}]
  (let [existing (db/query db "SELECT id FROM conversation_sources WHERE source_path = ?" [source-path])]
    (if (seq existing)
      (do
        (db/execute!
          db
          "UPDATE conversation_sources SET last_line_count = ?, last_modified_at = datetime('now') WHERE source_path = ?"
          [(or last-line-count 0) source-path])
        (:id (first existing)))
      (db/execute-returning-key!
        db
        "INSERT INTO conversation_sources (source_path, provider, project_path, last_line_count, last_modified_at)
         VALUES (?, ?, ?, ?, datetime('now'))"
        [source-path (or provider "claude-code") project-path (or last-line-count 0)]))))

(defn get-conversation-source
  "Get a conversation source by path."
  [db source-path]
  (first (db/query db "SELECT * FROM conversation_sources WHERE source_path = ?" [source-path])))

(defn delete-conversation-source!
  "Delete a conversation source (cascades to sessions and messages)."
  [db source-path]
  (db/execute! db "DELETE FROM conversation_sources WHERE source_path = ?" [source-path]))

;; -- Conversation Sessions ---------------------------------------------------

(defn upsert-conversation-session!
  "Insert or update a conversation session. Returns the session row id."
  [db
   {:keys [source-id session-id provider project-path title started-at ended-at message-count total-tokens-in
           total-tokens-out total-cost-usd]}]
  (let [existing (db/query
                   db
                   "SELECT id FROM conversation_sessions WHERE source_id = ? AND session_id = ?"
                   [source-id session-id])]
    (if (seq existing)
      (do
        (db/execute!
          db
          "UPDATE conversation_sessions
             SET title = ?, message_count = ?, total_tokens_in = ?, total_tokens_out = ?,
                 total_cost_usd = ?, ended_at = ?
             WHERE source_id = ? AND session_id = ?"
          [title message-count (or total-tokens-in 0) (or total-tokens-out 0) (or total-cost-usd 0) ended-at source-id
           session-id])
        (:id (first existing)))
      (db/execute-returning-key!
        db
        "INSERT INTO conversation_sessions
         (source_id, session_id, provider, project_path, title,
          started_at, ended_at, message_count, total_tokens_in, total_tokens_out, total_cost_usd)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        [source-id session-id (or provider "claude-code") project-path title started-at ended-at (or message-count 0)
         (or total-tokens-in 0) (or total-tokens-out 0) (or total-cost-usd 0)]))))

(defn get-conversation-session-by-session-id
  "Get a conversation session by its session UUID."
  [db session-id]
  (first (db/query db "SELECT * FROM conversation_sessions WHERE session_id = ?" [session-id])))

;; -- Conversation Messages ---------------------------------------------------

(defn insert-conversation-message!
  "Insert a conversation message."
  [db
   {:keys [session-row-id message-id role content-text model timestamp tokens-in tokens-out cost-usd tool-names
           is-sidechain]}]
  (db/execute!
    db
    "INSERT OR IGNORE INTO conversation_messages
     (session_row_id, message_id, role, content_text, model, timestamp,
      tokens_in, tokens_out, cost_usd, tool_names, is_sidechain)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    [session-row-id message-id role content-text model timestamp tokens-in tokens-out cost-usd
     (when (seq tool-names)
       (clojure.string/join "," tool-names)) (if is-sidechain 1 0)]))

(defn get-conversation-messages
  "Get all messages for a session, ordered by timestamp."
  [db session-row-id & {:keys [role]}]
  (if role
    (db/query
      db
      "SELECT * FROM conversation_messages WHERE session_row_id = ? AND role = ? ORDER BY timestamp"
      [session-row-id role])
    (db/query db "SELECT * FROM conversation_messages WHERE session_row_id = ? ORDER BY timestamp" [session-row-id])))

;; -- Embeddings --------------------------------------------------------------

(defn upsert-chunk-embedding!
  "Insert or replace a chunk embedding in the vec0 table."
  [db chunk-id embedding]
  (when (:vec? db)
    (let [embed-json (str "[" (clojure.string/join "," (map str embedding)) "]")]
      (db/execute! db "INSERT OR REPLACE INTO chunk_embeddings (id, embedding) VALUES (?, ?)" [chunk-id embed-json]))))

(defn get-chunks-without-embeddings
  "Get chunk IDs that don't yet have embeddings."
  [db project-id]
  (when (:vec? db)
    (db/query
      db
      "SELECT c.id, c.content, c.qualified_name, c.docstring
       FROM chunks c
       LEFT JOIN chunk_embeddings ce ON c.id = ce.id
       WHERE c.project_id = ? AND ce.id IS NULL"
      [project-id])))
