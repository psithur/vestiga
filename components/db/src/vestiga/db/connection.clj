(ns vestiga.db.connection
  (:require
    [clojure.java.io :as io]
    [clojure.tools.logging :as log])
  (:import [java.sql DriverManager Connection PreparedStatement ResultSet Statement]))

(defn- get-conn ^Connection [db] (:conn db))

(defn- apply-pragmas!
  "Apply SQLite PRAGMA settings for performance and correctness."
  [^Connection conn]
  (let [stmt (.createStatement conn)]
    (doto stmt
      (.execute "PRAGMA journal_mode = WAL")
      (.execute "PRAGMA foreign_keys = ON")
      (.execute "PRAGMA busy_timeout = 5000")
      (.close))))

(defn- try-load-vec-extension!
  "Attempt to load the sqlite-vec extension. Returns true on success."
  [^Connection conn vec-extension-path]
  (try (let [stmt (.createStatement conn)]
         (if vec-extension-path
           (.execute stmt (str "SELECT load_extension('" vec-extension-path "')"))
           (.execute stmt "SELECT load_extension('vec0')"))
         (.close stmt)
         (log/info "sqlite-vec extension loaded successfully")
         true)
       (catch Exception e (log/warn "sqlite-vec extension not available:" (.getMessage e)) false)))

(defn open-db
  "Opens a SQLite connection to the given path.
   Creates parent directories if needed.
   Applies PRAGMA settings.
   Attempts to load sqlite-vec extension.
   Returns a map:
   {:conn       java.sql.Connection
    :vec?       boolean
    :db-path    string}"
  [db-path & {:keys [vec-extension-path]}]
  (when (and
          (not= db-path ":memory:")
          (not= db-path ""))
    (let [parent (.getParentFile (io/file db-path))]
      (when (and
              parent
              (not (.exists parent)))
        (.mkdirs parent))))
  (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" db-path))]
    (apply-pragmas! conn)
    (let [vec? (try-load-vec-extension! conn vec-extension-path)]
      {:conn    conn
       :vec?    vec?
       :db-path db-path})))

(defn close-db
  "Close the database connection."
  [db]
  (let [^Connection conn (get-conn db)]
    (when (and
            conn
            (not (.isClosed conn)))
      (.close conn))))

(defn with-db
  "Execute f with an open database, ensuring cleanup."
  [db-path f]
  (let [db (open-db db-path)]
    (try (f db) (finally (close-db db)))))

(defn execute-raw!
  "Execute a SQL statement that may or may not return results.
   Used for DDL, PRAGMAs, etc. Returns nil."
  [db sql]
  (let [^Connection conn (get-conn db)
        ^Statement stmt  (.createStatement conn)]
    (try (.execute stmt sql) (finally (.close stmt)))))

(defn- bind-params!
  "Bind parameters to a PreparedStatement."
  [^PreparedStatement ps params]
  (doseq [[i v] (map-indexed vector params)]
    (let [idx (int (inc i))]
      (cond
        (nil? v)
        (.setNull ps idx java.sql.Types/NULL)
        (string? v)
        (.setString ps idx ^String v)
        (integer? v)
        (.setLong ps idx (long v))
        (float? v)
        (.setDouble ps idx (double v))
        :else
        (.setObject ps idx v)))))

(defn execute!
  "Execute a SQL statement with parameters. Returns update count."
  [db sql params]
  (let [^Connection conn      (get-conn db)
        ^PreparedStatement ps (.prepareStatement conn ^String sql)]
    (try (bind-params! ps params) (.executeUpdate ps) (finally (.close ps)))))

(defn execute-returning-key!
  "Execute a SQL INSERT and return the generated key."
  [db sql params]
  (let [^Connection conn      (get-conn db)
        ^PreparedStatement ps (.prepareStatement conn ^String sql (int Statement/RETURN_GENERATED_KEYS))]
    (try (bind-params! ps params)
         (.executeUpdate ps)
         (let [^ResultSet rs (.getGeneratedKeys ps)]
           (when (.next rs)
             (.getLong rs (int 1))))
         (finally (.close ps)))))

(defn- resultset->maps
  "Convert a ResultSet to a vector of maps."
  [^ResultSet rs]
  (let [meta      (.getMetaData rs)
        col-count (.getColumnCount meta)
        col-names (mapv #(keyword (.getColumnLabel meta (int (inc %)))) (range col-count))]
    (loop [rows (transient [])]
      (if (.next rs)
        (recur
          (conj!
            rows
            (into
              {}
              (map
                (fn [i]
                  [(nth col-names i) (.getObject rs (int (inc i)))])
                (range col-count)))))
        (persistent! rows)))))

(defn query
  "Execute a SQL query with parameters. Returns vector of maps."
  [db sql params]
  (let [^Connection conn      (get-conn db)
        ^PreparedStatement ps (.prepareStatement conn ^String sql)]
    (try (bind-params! ps params)
         (let [rs (.executeQuery ps)]
           (resultset->maps rs))
         (finally (.close ps)))))

(defn with-transaction
  "Execute f within a transaction. Rolls back on exception."
  [{:keys [conn]
    :as   db} f]
  (let [^Connection c conn
        auto-commit   (.getAutoCommit c)]
    (.setAutoCommit c false)
    (try (let [result (f db)]
           (.commit c)
           result)
         (catch Exception e (.rollback c) (throw e))
         (finally (.setAutoCommit c auto-commit)))))
