(ns vestiga.server.core
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [clojure.tools.logging :as log]
    [vestiga.config.interface :as config]
    [vestiga.db.interface :as db]
    [vestiga.db.interface.ops :as ops]
    [vestiga.db.interface.schema :as schema]
    [vestiga.db.interface.search :as db-search]
    [vestiga.index.interface :as index]
    [vestiga.mcp.interface :as mcp]
    [vestiga.search.interface :as search])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn- which
  "Check if a binary is on PATH. Returns true if found."
  [binary]
  (try (zero? (.waitFor (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String ["which" binary]))))
       (catch Exception _ false)))

(defn- with-db-conn
  "Open a DB connection, ensure schema, call (f db), close on exit."
  [db-path f]
  (let [db-conn (db/open-db db-path)]
    (try (schema/ensure-schema! db-conn) (f db-conn) (finally (db/close-db db-conn)))))

(defn- print-results
  "Print search results in a human-readable format."
  [results]
  (if (empty? results)
    (println "No results found.")
    (doseq [r results]
      (println
        (format
          "%s:%d  %s  [%s]"
          (or (:file_path r) (:file-path r) "?")
          (or (:start_line r) (:start-line r) 0)
          (or (:qualified_name r) (:qualified-name r) "?")
          (or (:kind r) "?")))
      (when-let [content (:content r)]
        (println content))
      (println))))

(defn- resolve-db-path
  "Resolve the database path from options or default.
   If no explicit --db is given, uses <project-root>/.vestiga/db.sqlite,
   falling back to CWD-relative .vestiga/db.sqlite."
  ([opts] (resolve-db-path opts nil))
  ([opts project-root]
   (or (:db opts)
       (when project-root
         (str project-root "/.vestiga/db.sqlite"))
       ".vestiga/db.sqlite")))

;; ---------------------------------------------------------------------------
;; Subcommand: mcp
;; ---------------------------------------------------------------------------

(def mcp-opts
  [["-p" "--project-root PATH" "Project root directory (default: current directory)" :default "."]
   ["-d" "--db PATH" "Database path (default: <project-root>/.vestiga/db.sqlite)"]
   ["-h" "--help" "Show help"]])

(defn cmd-mcp
  [{:keys [opts]}]
  ;; Suppress stderr logging — MCP clients interpret it as errors
  (System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "off")
  (let [project-root (:project-root opts)
        db-path      (resolve-db-path opts project-root)]
    (with-db-conn
      db-path
      (fn [db]
        (mcp/start-server! db)))))

;; ---------------------------------------------------------------------------
;; Subcommand: index
;; ---------------------------------------------------------------------------

(def index-opts
  [["-p" "--project-root PATH" "Project root directory" :default "."]
   ["-d" "--db PATH" "Database path (default: <project-root>/.vestiga/db.sqlite)"]
   ["-f" "--full" "Force full re-index"]
   ["-h" "--help" "Show help"]])

(defn cmd-index
  [{:keys [opts]}]
  (let [project-root (:project-root opts)
        db-path      (resolve-db-path opts project-root)
        config       (config/load-config)]
    (with-db-conn
      db-path
      (fn [db]
        (index/index-project! db project-root :config config :full (:full opts))))
    (println "Indexing complete.")))

;; ---------------------------------------------------------------------------
;; Subcommand: search
;; ---------------------------------------------------------------------------

(def search-opts
  [["-d" "--db PATH" "Database path" :default ".vestiga/db.sqlite"]
   ["-l" "--limit N" "Max results" :default 20 :parse-fn parse-long]
   ["-k" "--kind KIND" "Filter by symbol kind (defn, defmacro, defprotocol, etc.)"
    :multi true :default [] :update-fn conj]
   ["-n" "--namespace NS" "Filter by namespace (supports * glob)"]
   ["-h" "--help" "Show help"]])

(defn cmd-search
  [{:keys [opts args]}]
  (when (empty? args)
    (println "Usage: vestiga search [opts] <query>")
    (System/exit 1))
  (let [query (str/join " " args)]
    (with-db-conn
      (resolve-db-path opts)
      (fn [db]
        (let [results (search/search
                        db
                        query
                        :limit
                        (:limit opts)
                        :kinds
                        (when (seq (:kind opts))
                          (set (:kind opts)))
                        :namespace
                        (:namespace opts))]
          (print-results results))))))

;; ---------------------------------------------------------------------------
;; Subcommand: refs (find-references)
;; ---------------------------------------------------------------------------

(def refs-opts
  [["-d" "--db PATH" "Database path" :default ".vestiga/db.sqlite"]
   ["-h" "--help" "Show help"]])

(defn cmd-refs
  [{:keys [opts args]}]
  (when (empty? args)
    (println "Usage: vestiga refs <qualified-name>")
    (println "  e.g. vestiga refs my.app.core/handle-request")
    (System/exit 1))
  (let [qname (first args)]
    (with-db-conn
      (resolve-db-path opts)
      (fn [db]
        (let [refs (search/find-references db qname)]
          (if (empty? refs)
            (println "No references found for" qname)
            (doseq [r refs]
              (println
                (format
                  "%s:%s:%s  %s/%s -> %s/%s"
                  (or (:file_path r) "?")
                  (or (:row r) "?")
                  (or (:col r) "?")
                  (or (:from_ns r) "?")
                  (or (:from_name r) "?")
                  (or (:to_ns r) "?")
                  (or (:to_name r) "?"))))))))))

;; ---------------------------------------------------------------------------
;; Subcommand: deps (find-dependents)
;; ---------------------------------------------------------------------------

(def deps-opts
  [["-d" "--db PATH" "Database path" :default ".vestiga/db.sqlite"]
   ["-h" "--help" "Show help"]])

(defn cmd-deps
  [{:keys [opts args]}]
  (when (empty? args)
    (println "Usage: vestiga deps <namespace>")
    (println "  e.g. vestiga deps my.app.db")
    (System/exit 1))
  (let [ns-name (first args)]
    (with-db-conn
      (resolve-db-path opts)
      (fn [db]
        (let [dependents (search/find-dependents db ns-name)]
          (if (empty? dependents)
            (println "No dependents found for" ns-name)
            (doseq [d dependents]
              (println (:from_ns d)))))))))

;; ---------------------------------------------------------------------------
;; Subcommand: impact
;; ---------------------------------------------------------------------------

(def impact-opts
  [["-d" "--db PATH" "Database path" :default ".vestiga/db.sqlite"]
   ["-h" "--help" "Show help"]])

(defn cmd-impact
  [{:keys [opts args]}]
  (when (empty? args)
    (println "Usage: vestiga impact <qualified-name>")
    (println "  e.g. vestiga impact my.app.core/handle-request")
    (System/exit 1))
  (let [qname (first args)]
    (with-db-conn
      (resolve-db-path opts)
      (fn [db]
        (let [{:keys [callers ns-dependents recent-commits]} (search/impact-analysis db qname)]
          (println "## Callers" (str "(" (count callers) ")"))
          (if (empty? callers)
            (println "  None found.")
            (doseq [c callers]
              (println
                (format
                  "  %s:%s  %s/%s"
                  (or (:file_path c) "?")
                  (or (:row c) "?")
                  (or (:from_ns c) "?")
                  (or (:from_name c) "?")))))
          (println)
          (println "## Namespace Dependents" (str "(" (count ns-dependents) ")"))
          (if (empty? ns-dependents)
            (println "  None found.")
            (doseq [d ns-dependents]
              (println (str "  " (:from_ns d)))))
          (println)
          (println "## Recent Commits" (str "(" (count recent-commits) ")"))
          (if (empty? recent-commits)
            (println "  None found.")
            (doseq [c recent-commits]
              (println (format "  %s %s" (:sha c) (:message c))))))))))

;; ---------------------------------------------------------------------------
;; Subcommand: history
;; ---------------------------------------------------------------------------

(def history-opts
  [["-d" "--db PATH" "Database path" :default ".vestiga/db.sqlite"]
   ["-l" "--limit N" "Max results" :default 20 :parse-fn parse-long]
   ["-f" "--file PATH" "Filter to commits touching this file"]
   ["-h" "--help" "Show help"]])

(defn cmd-history
  [{:keys [opts args]}]
  (when (empty? args)
    (println "Usage: vestiga history [opts] <query>")
    (System/exit 1))
  (let [query (str/join " " args)]
    (with-db-conn
      (resolve-db-path opts)
      (fn [db]
        (let [results (db-search/search-commits db query :limit (:limit opts) :file-path (:file opts))]
          (if (empty? results)
            (println "No matching commits found.")
            (doseq [c results]
              (println (format "%s | %s | %s" (:sha c) (:author c) (:message c))))))))))

;; ---------------------------------------------------------------------------
;; Top-level dispatch
;; ---------------------------------------------------------------------------

(def subcommands
  {"mcp"     {:fn   cmd-mcp
              :opts mcp-opts
              :desc "Start the MCP JSON-RPC server (for AI tool integration)"}
   "index"   {:fn   cmd-index
              :opts index-opts
              :desc "Index a project for searching"}
   "search"  {:fn   cmd-search
              :opts search-opts
              :desc "Search indexed code"}
   "refs"    {:fn   cmd-refs
              :opts refs-opts
              :desc "Find all references to a symbol"}
   "deps"    {:fn   cmd-deps
              :opts deps-opts
              :desc "Find all namespaces that depend on a namespace"}
   "impact"  {:fn   cmd-impact
              :opts impact-opts
              :desc "Analyse impact of changing a symbol"}
   "history" {:fn   cmd-history
              :opts history-opts
              :desc "Search git commit history"}})

(defn- print-usage
  []
  (println "vestiga — local code intelligence for Clojure")
  (println)
  (println "Usage: vestiga <command> [options] [args]")
  (println)
  (println "Commands:")
  (doseq [[name {:keys [desc]}] (sort subcommands)]
    (println (format "  %-10s %s" name desc)))
  (println)
  (println "Run 'vestiga <command> --help' for command-specific options."))

(defn -main
  [& args]
  (let [cmd (first args)
        sub (get subcommands cmd)]
    (if-not sub
      (do (print-usage)
          (when cmd
            (System/exit 1)))
      (let [{:keys [fn opts]} sub
            {:keys [options arguments errors summary]} (cli/parse-opts (rest args) opts)]
        (cond
          errors
          (do (doseq [e errors]
                (println e))
              (System/exit 1))

          (:help options)
          (do (println (str "vestiga " cmd)) (println) (println summary))

          :else
          (fn {:opts options
               :args arguments}))))))
