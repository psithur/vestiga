(ns vestiga.db.schema
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vestiga.db.connection :as db]))

(def current-version 3)

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

(defn ensure-schema!
  "Apply the database schema if needed.
   On fresh databases, applies the full schema from schema.sql.
   On existing databases with a different version, throws an error
   instructing the user to delete and re-index."
  [db]
  (let [version (schema-version db)]
    (cond
      ;; Fresh install — apply full schema
      (nil? version)
      (do (log/info "Applying schema version" current-version "(fresh install)")
          (execute-sql-script! db (load-schema-sql))
          (db/execute! db "INSERT OR REPLACE INTO schema_version (version) VALUES (?)" [current-version])
          (log/info "Schema version" current-version "applied successfully"))

      ;; Correct version — nothing to do
      (= version current-version)
      nil

      ;; Wrong version — error out
      :else
      (throw
        (ex-info
          (str
            "Database schema version mismatch: found v"
            version
            ", expected v"
            current-version
            ". Delete .vestiga/db.sqlite and re-index.")
          {:found    version
           :expected current-version})))))

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
