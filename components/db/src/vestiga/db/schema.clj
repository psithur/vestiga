(ns vestiga.db.schema
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [vestiga.db.connection :as db]))

(def current-version 1)

(defn- load-schema-sql "Load the schema SQL from resources." [] (slurp (io/resource "schema.sql")))

(defn- execute-sql-script!
  "Execute a multi-statement SQL script.
   Uses JDBC's execute() which handles multiple statements including triggers."
  [db sql]
  (let [conn ^java.sql.Connection (:conn db)
        stmt (.createStatement conn)]
    (try
      ;; Remove SQL comments (lines starting with --)
      (let [cleaned (->> (str/split-lines sql)
                         (remove #(re-matches #"\s*--.*" %))
                         (str/join "\n"))]
        ;; Execute each statement separated by semicolons,
        ;; but respect BEGIN...END blocks in triggers
        (loop [remaining (str/trim cleaned)]
          (when (not (str/blank? remaining))
            ;; Find the next statement end, respecting BEGIN...END
            (let [end-pos (loop [i     0
                                 depth 0]
                            (if (>= i (count remaining))
                              i
                              (let [ch (nth remaining i)]
                                (cond
                                  ;; Check for BEGIN
                                  (and
                                    (<= (+ i 5) (count remaining))
                                    (= "BEGIN" (str/upper-case (subs remaining i (min (+ i 5) (count remaining)))))
                                    (or (zero? i) (Character/isWhitespace (nth remaining (dec i)))))
                                  (recur (+ i 5) (inc depth))

                                  ;; Check for END
                                  (and
                                    (pos? depth)
                                    (<= (+ i 3) (count remaining))
                                    (= "END" (str/upper-case (subs remaining i (min (+ i 3) (count remaining)))))
                                    (or (zero? i) (Character/isWhitespace (nth remaining (dec i)))))
                                  (recur (+ i 3) (dec depth))

                                  ;; Semicolon at top level
                                  (and
                                    (= ch \;)
                                    (zero? depth))
                                  (inc i)

                                  :else
                                  (recur (inc i) depth)))))]
              (when (> end-pos 0)
                (let [stmt-text (str/trim (subs remaining 0 end-pos))]
                  (when (not (str/blank? stmt-text))
                    (.execute stmt stmt-text))
                  (recur (str/trim (subs remaining end-pos)))))))))
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
   Creates all tables, indexes, triggers, and FTS tables.
   Idempotent — safe to call on every startup."
  [db]
  (let [version (schema-version db)]
    (when (or (nil? version) (< version current-version))
      (log/info
        "Applying schema version"
        current-version
        (if version (str "(upgrading from " version ")") "(fresh install)"))
      (let [sql (load-schema-sql)]
        (execute-sql-script! db sql)
        ;; Record the schema version
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
           (log/info "Vector embedding tables created with dimension" dim)
           (catch Exception e (log/warn "Failed to create vector tables:" (.getMessage e)))))))
