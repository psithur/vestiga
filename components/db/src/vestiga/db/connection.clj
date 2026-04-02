(ns vestiga.db.connection
  (:require
    [clojure.java.io :as io]
    [clojure.tools.logging :as log])
  (:import [java.sql DriverManager Connection PreparedStatement ResultSet]))

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
  [{:keys [^Connection conn]}]
  (when (and
          conn
          (not (.isClosed conn)))
    (.close conn)))

(defn with-db
  "Execute f with an open database, ensuring cleanup."
  [db-path f]
  (let [db (open-db db-path)]
    (try (f db) (finally (close-db db)))))

(defn execute-raw!
  "Execute a SQL statement that may or may not return results.
   Used for DDL, PRAGMAs, etc. Returns nil."
  [{:keys [^Connection conn]} sql]
  (let [stmt (.createStatement conn)]
    (try (.execute stmt sql) (finally (.close stmt)))))

(defn execute!
  "Execute a SQL statement with parameters. Returns update count."
  [{:keys [^Connection conn]} sql params]
  (let [ps (.prepareStatement conn sql)]
    (try (doseq [[i v] (map-indexed vector params)]
           (cond
             (nil? v)
             (.setNull ps (inc i) java.sql.Types/NULL)
             (string? v)
             (.setString ps (inc i) v)
             (integer? v)
             (.setLong ps (inc i) (long v))
             (float? v)
             (.setDouble ps (inc i) (double v))
             :else
             (.setObject ps (inc i) v)))
         (.executeUpdate ps)
         (finally (.close ps)))))

(defn execute-returning-key!
  "Execute a SQL INSERT and return the generated key."
  [{:keys [^Connection conn]} sql params]
  (let [ps (.prepareStatement conn sql java.sql.Statement/RETURN_GENERATED_KEYS)]
    (try (doseq [[i v] (map-indexed vector params)]
           (cond
             (nil? v)
             (.setNull ps (inc i) java.sql.Types/NULL)
             (string? v)
             (.setString ps (inc i) v)
             (integer? v)
             (.setLong ps (inc i) (long v))
             (float? v)
             (.setDouble ps (inc i) (double v))
             :else
             (.setObject ps (inc i) v)))
         (.executeUpdate ps)
         (let [rs (.getGeneratedKeys ps)]
           (when (.next rs)
             (.getLong rs 1)))
         (finally (.close ps)))))

(defn- resultset->maps
  "Convert a ResultSet to a vector of maps."
  [^ResultSet rs]
  (let [meta      (.getMetaData rs)
        col-count (.getColumnCount meta)
        col-names (mapv #(keyword (.getColumnLabel meta (inc %))) (range col-count))]
    (loop [rows (transient [])]
      (if (.next rs)
        (recur
          (conj!
            rows
            (into
              {}
              (map
                (fn [i]
                  [(nth col-names i) (.getObject rs (inc i))])
                (range col-count)))))
        (persistent! rows)))))

(defn query
  "Execute a SQL query with parameters. Returns vector of maps."
  [{:keys [^Connection conn]} sql params]
  (let [ps (.prepareStatement conn sql)]
    (try (doseq [[i v] (map-indexed vector params)]
           (cond
             (nil? v)
             (.setNull ps (inc i) java.sql.Types/NULL)
             (string? v)
             (.setString ps (inc i) v)
             (integer? v)
             (.setLong ps (inc i) (long v))
             (float? v)
             (.setDouble ps (inc i) (double v))
             :else
             (.setObject ps (inc i) v)))
         (let [rs (.executeQuery ps)]
           (resultset->maps rs))
         (finally (.close ps)))))

(defn with-transaction
  "Execute f within a transaction. Rolls back on exception."
  [{:keys [^Connection conn]
    :as   db} f]
  (let [auto-commit (.getAutoCommit conn)]
    (.setAutoCommit conn false)
    (try (let [result (f db)]
           (.commit conn)
           result)
         (catch Exception e (.rollback conn) (throw e))
         (finally (.setAutoCommit conn auto-commit)))))
