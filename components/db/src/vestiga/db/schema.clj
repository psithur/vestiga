(ns vestiga.db.schema
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vestiga.db.connection :as db]))

(def current-version 3)

(def ^:private migrations
  "Ordered migrations. Each is [from-version sql-string]."
  [[1
    "ALTER TABLE commit_files ADD COLUMN patch TEXT;

CREATE VIRTUAL TABLE IF NOT EXISTS commit_patches_fts USING fts5(
  file_path,
  patch,
  content='commit_files',
  content_rowid='id',
  tokenize='porter unicode61'
);

CREATE TRIGGER IF NOT EXISTS commit_files_ai AFTER INSERT ON commit_files
WHEN new.patch IS NOT NULL BEGIN
  INSERT INTO commit_patches_fts(rowid, file_path, patch)
  VALUES (new.id, new.file_path, new.patch);
END;

CREATE TRIGGER IF NOT EXISTS commit_files_ad AFTER DELETE ON commit_files
WHEN old.patch IS NOT NULL BEGIN
  INSERT INTO commit_patches_fts(commit_patches_fts, rowid, file_path, patch)
  VALUES ('delete', old.id, old.file_path, old.patch);
END;"]
   [2
    "CREATE TABLE IF NOT EXISTS conversation_sources (
  id INTEGER PRIMARY KEY,
  source_path TEXT NOT NULL UNIQUE,
  provider TEXT NOT NULL DEFAULT 'claude-code',
  project_path TEXT,
  last_line_count INTEGER DEFAULT 0,
  last_modified_at TEXT
);

CREATE TABLE IF NOT EXISTS conversation_sessions (
  id INTEGER PRIMARY KEY,
  source_id INTEGER NOT NULL REFERENCES conversation_sources(id) ON DELETE CASCADE,
  session_id TEXT NOT NULL,
  provider TEXT NOT NULL DEFAULT 'claude-code',
  project_path TEXT,
  title TEXT,
  started_at INTEGER,
  ended_at INTEGER,
  message_count INTEGER DEFAULT 0,
  total_tokens_in INTEGER DEFAULT 0,
  total_tokens_out INTEGER DEFAULT 0,
  total_cost_usd REAL DEFAULT 0,
  UNIQUE(source_id, session_id)
);

CREATE INDEX IF NOT EXISTS idx_conv_sessions_started ON conversation_sessions(started_at DESC);

CREATE TABLE IF NOT EXISTS conversation_messages (
  id INTEGER PRIMARY KEY,
  session_row_id INTEGER NOT NULL REFERENCES conversation_sessions(id) ON DELETE CASCADE,
  message_id TEXT NOT NULL,
  role TEXT NOT NULL,
  content_text TEXT NOT NULL,
  model TEXT,
  timestamp INTEGER NOT NULL,
  tokens_in INTEGER,
  tokens_out INTEGER,
  cost_usd REAL,
  tool_names TEXT,
  is_sidechain INTEGER DEFAULT 0,
  UNIQUE(session_row_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_conv_messages_session ON conversation_messages(session_row_id);
CREATE INDEX IF NOT EXISTS idx_conv_messages_ts ON conversation_messages(timestamp);

CREATE VIRTUAL TABLE IF NOT EXISTS conversation_messages_fts USING fts5(
  content_text,
  tool_names,
  content='conversation_messages',
  content_rowid='id',
  tokenize='porter unicode61'
);

CREATE TRIGGER IF NOT EXISTS conv_msg_ai AFTER INSERT ON conversation_messages BEGIN
  INSERT INTO conversation_messages_fts(rowid, content_text, tool_names)
  VALUES (new.id, new.content_text, new.tool_names);
END;

CREATE TRIGGER IF NOT EXISTS conv_msg_ad AFTER DELETE ON conversation_messages BEGIN
  INSERT INTO conversation_messages_fts(conversation_messages_fts, rowid, content_text, tool_names)
  VALUES ('delete', old.id, old.content_text, old.tool_names);
END;"]])

(defn- load-schema-sql "Load the schema SQL from resources." [] (slurp (io/resource "schema.sql")))

(defn- split-sql-statements
  "Split SQL script into individual statements, respecting BEGIN...END trigger blocks.
   Returns a vector of statement strings."
  [sql]
  (let [lines (str/split-lines sql)]
    (loop [remaining lines
           current   []
           in-block  false
           result    []]
      (if (empty? remaining)
        (let [stmt (str/trim (str/join "\n" current))]
          (if (str/blank? stmt) result (conj result stmt)))
        (let [line    (first remaining)
              trimmed (str/trim line)]
          (cond
            ;; Skip pure comment lines
            (re-matches #"--.*" trimmed)
            (recur (rest remaining) current in-block result)

            ;; Entering a BEGIN block (trigger body)
            (and
              (not in-block)
              (re-find #"(?i)\bBEGIN\b" trimmed))
            (if (re-find #"(?i)\bEND\s*;" trimmed)
              ;; Single-line BEGIN...END; (shouldn't happen but handle it)
              (let [stmt (str/trim (str/join "\n" (conj current line)))]
                (recur (rest remaining) [] false (if (str/blank? stmt) result (conj result stmt))))
              (recur (rest remaining) (conj current line) true result))

            ;; Exiting a block with END;
            (and
              in-block
              (re-find #"(?i)\bEND\s*;" trimmed))
            (let [stmt (str/trim (str/join "\n" (conj current line)))]
              (recur (rest remaining) [] false (if (str/blank? stmt) result (conj result stmt))))

            ;; Inside a block — accumulate
            in-block
            (recur (rest remaining) (conj current line) true result)

            ;; Statement ending with semicolon (outside block)
            (str/ends-with? trimmed ";")
            (let [stmt (str/trim (str/join "\n" (conj current line)))]
              (recur (rest remaining) [] false (if (str/blank? stmt) result (conj result stmt))))

            ;; Accumulate continuation lines
            :else
            (recur (rest remaining) (conj current line) false result)))))))

(defn- execute-sql-script!
  "Execute a multi-statement SQL script."
  [db sql]
  (let [conn ^java.sql.Connection (:conn db)
        stmt (.createStatement conn)]
    (try (doseq [s (split-sql-statements sql)]
           (.execute stmt s))
         (finally (.close stmt)))))

(defn- schema-version
  "Get the current schema version, or nil if no schema exists."
  [db]
  (try (let [rows (db/query db "SELECT MAX(version) as version FROM schema_version" [])]
         (when (seq rows)
           (:version (first rows))))
       (catch Exception _ nil)))

(defn- apply-migrations!
  "Apply incremental migrations from the current version to target."
  [db from-version]
  (doseq [[from-ver sql] migrations
          :when (>= from-ver from-version)]
    (log/info "Applying migration from version" from-ver "to" (inc from-ver))
    (execute-sql-script! db sql)))

(defn ensure-schema!
  "Apply the database schema if needed.
   Creates all tables, indexes, triggers, and FTS tables.
   Idempotent — safe to call on every startup."
  [db]
  (let [version (schema-version db)]
    (cond
      ;; Fresh install — apply full schema
      (nil? version)
      (do (log/info "Applying schema version" current-version "(fresh install)")
          (execute-sql-script! db (load-schema-sql))
          (db/execute! db "INSERT OR REPLACE INTO schema_version (version) VALUES (?)" [current-version])
          (log/info "Schema version" current-version "applied successfully"))

      ;; Needs migration
      (< version current-version)
      (do (log/info "Applying schema version" current-version (str "(upgrading from " version ")"))
          (apply-migrations! db version)
          (db/execute! db "INSERT OR REPLACE INTO schema_version (version) VALUES (?)" [current-version])
          (log/info "Schema version" current-version "applied successfully")))))

(defn ensure-vec-tables!
  "Create vector embedding tables if sqlite-vec is available."
  [db embed-dim]
  (when (:vec? db)
    (let [dim (or embed-dim 768)]
      (try (db/execute!
             db
             (str
               "CREATE VIRTUAL TABLE IF NOT EXISTS chunk_embeddings USING vec0("
               "id INTEGER PRIMARY KEY, "
               "embedding float["
               dim
               "])")
             [])
           (db/execute!
             db
             (str
               "CREATE VIRTUAL TABLE IF NOT EXISTS commit_embeddings USING vec0("
               "id INTEGER PRIMARY KEY, "
               "embedding float["
               dim
               "])")
             [])
           (db/execute!
             db
             (str
               "CREATE VIRTUAL TABLE IF NOT EXISTS conversation_message_embeddings USING vec0("
               "id INTEGER PRIMARY KEY, "
               "embedding float["
               dim
               "])")
             [])
           (log/info "Vector embedding tables created with dimension" dim)
           (catch Exception e (log/warn "Failed to create vector tables:" (.getMessage e)))))))
