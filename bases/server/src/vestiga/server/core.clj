(ns vestiga.server.core
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as cli]
    [clojure.tools.logging :as log]
    [vestiga.config.interface :as config]
    [vestiga.conversation.interface :as conversation]
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

(declare guided-setup!)

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

(defn- prompt-yn
  "Prompt with a yes/no question. default is :yes or :no."
  [question default]
  (let [hint (if (= default :yes) "[Y/n]" "[y/N]")]
    (print (str question " " hint " "))
    (flush)
    (let [input (str/trim (or (read-line) ""))]
      (if (str/blank? input) (= default :yes) (boolean (#{"y" "yes"} (str/lower-case input)))))))

(defn- fresh-db? "Check if the database file exists." [db-path] (not (.exists (java.io.File. ^String db-path))))

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
        (mcp/start-server! db :project-root project-root)))))

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
        config       (config/load-config)
        first-run?   (fresh-db? db-path)]
    (with-db-conn
      db-path
      (fn [db]
        (if first-run?
          (guided-setup! db project-root config)
          (do (index/index-project! db project-root :config config :full (:full opts))
              (println "Indexing complete.")))))))

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
        (let [msg-results  (db-search/search-commits db query :limit (:limit opts) :file-path (:file opts))
              diff-results (db-search/search-patches db query :limit (:limit opts) :file-path (:file opts))]
          (when (seq msg-results)
            (println "## Commits (message match)\n")
            (doseq [c msg-results]
              (println (format "%s | %s | %s" (:sha c) (:author c) (:message c)))))
          (when (seq diff-results)
            (when (seq msg-results)
              (println))
            (println "## Commits (diff match)\n")
            (doseq [c diff-results]
              (println (format "%s | %s | %s" (:sha c) (:author c) (:message c)))
              (println (format "  %s [%s]" (:file_path c) (:change_type c)))
              (println (format "  %s" (:patch_snippet c)))
              (println)))
          (when (and
                  (empty? msg-results)
                  (empty? diff-results))
            (println "No matching commits found.")))))))

;; ---------------------------------------------------------------------------
;; Subcommand: hotspots
;; ---------------------------------------------------------------------------

(def hotspots-opts
  [["-d" "--db PATH" "Database path" :default ".vestiga/db.sqlite"]
   ["-l" "--limit N" "Max results" :default 20 :parse-fn parse-long]
   ["-n" "--namespace NS" "Filter to files matching namespace path"]
   ["-s" "--since DAYS" "Only count commits from the last N days" :parse-fn parse-long]
   ["-h" "--help" "Show help"]])

(defn cmd-hotspots
  [{:keys [opts]}]
  (with-db-conn
    (resolve-db-path opts)
    (fn [db]
      (let [since-ts (when (:since opts)
                       (- (quot (System/currentTimeMillis) 1000) (* (:since opts) 86400)))
            results  (db-search/hotspots db :limit (:limit opts) :since since-ts :namespace (:namespace opts))]
        (if (empty? results)
          (println "No hotspots found.")
          (do (println (format "%-60s %6s %8s %8s" "File" "Edits" "+Lines" "-Lines"))
              (println (apply str (repeat 86 "-")))
              (doseq [r results]
                (println
                  (format
                    "%-60s %6d %8s %8s"
                    (:file_path r)
                    (:edit_count r)
                    (or (:total_added r) "-")
                    (or (:total_removed r) "-"))))))))))

;; ---------------------------------------------------------------------------
;; Shared embedding helper
;; ---------------------------------------------------------------------------

(defn- with-ollama-provider
  "If vec0 is available, ensure ollama is running and call (f provider dim).
   Handles lifecycle. No-op if vec0 or ollama unavailable."
  [db f]
  (when (:vec? db)
    (let [ensure!  (requiring-resolve 'vestiga.embed.interface.process/ensure-ollama!)
          running? (requiring-resolve 'vestiga.embed.interface.process/ollama-running?)
          stop!    (requiring-resolve 'vestiga.embed.interface.process/stop-ollama!)
          base-url "http://localhost:11434"
          state    (ensure! :base-url base-url)]
      (when (running? base-url)
        (try (let [provider ((requiring-resolve 'vestiga.embed.interface/->ollama-provider) :base-url base-url)
                   dim      ((requiring-resolve 'vestiga.embed.interface/embedding-dim) provider)]
               (when dim
                 (schema/ensure-vec-tables! db dim)
                 (f provider dim)))
             (finally (stop! state)))))))

;; ---------------------------------------------------------------------------
;; Conversation indexing
;; ---------------------------------------------------------------------------

(defn- index-conversations!
  "Index Claude Code conversation sessions for a project into the DB."
  [db project-root]
  (let [sessions (conversation/find-sessions project-root)]
    (when (seq sessions)
      (println (str "Found " (count sessions) " conversation session(s) for " project-root))
      (conversation/index-conversations! db project-root)
      (println "Conversation indexing complete."))))

;; ---------------------------------------------------------------------------
;; Guided first-run setup
;; ---------------------------------------------------------------------------

(defn- guided-setup!
  "Interactive first-run setup. Asks the user what to index and does it all."
  [db project-root config]
  (println)
  (println "Welcome to vestiga! This looks like a fresh index.")
  (println (str "Project: " (.getCanonicalPath (java.io.File. ^String project-root))))
  (println)

  ;; 1. Gather preferences before doing work
  (let [sessions       (conversation/find-sessions project-root)
        all-projects   (conversation/find-all-sessions)
        total-sessions (reduce + 0 (map #(count (:sessions %)) all-projects))
        has-ollama?    (and
                         (:vec? db)
                         (which "ollama"))

        conv-choice    (cond
                         (> (count all-projects) 1)
                         (do (when (seq sessions)
                               (println (str "Found " (count sessions) " conversation session(s) for this project.")))
                             (println
                               (str
                                 "Found "
                                 (count all-projects)
                                 " projects with "
                                 total-sessions
                                 " total sessions in ~/.claude/projects/."))
                             (if (prompt-yn "Index conversation history from all projects?" :yes)
                               :all
                               (when (seq sessions)
                                 (when (prompt-yn "Index conversations for this project only?" :yes)
                                   :project))))

                         (seq sessions)
                         (do (println (str "Found " (count sessions) " conversation session(s) for this project."))
                             (when (prompt-yn "Index conversation history?" :yes)
                               :project)))

        embed?         (when has-ollama?
                         (prompt-yn
                           "Generate semantic embeddings via Ollama? (enables fuzzy/natural language search)"
                           :yes))]

    ;; 2. Index code (with or without embeddings)
    (println)
    (println "Indexing source code...")
    (index/index-project! db project-root :config config :skip-embeddings (not embed?))

    ;; 3. Index conversations
    (case conv-choice
      :all     (do (println (str "Indexing conversations from " (count all-projects) " projects..."))
                   (conversation/index-all-conversations! db))
      :project (do (println "Indexing conversations for this project...")
                   (conversation/index-conversations! db project-root))
      nil)

    ;; 4. Embed conversation messages (code embeddings handled by index-project! above)
    (when (and
            embed?
            conv-choice)
      (with-ollama-provider
        db
        (fn [provider _dim]
          (println "Embedding conversation messages...")
          (conversation/embed-conversations! db provider)))))

  (println)
  (println "Setup complete! You can now use: vestiga search, vestiga conversation-search, etc."))

;; ---------------------------------------------------------------------------
;; Subcommand: conversations (list sessions)
;; ---------------------------------------------------------------------------

(def conversations-opts
  [["-p" "--project-root PATH" "Project root directory" :default "."]
   ["-d" "--db PATH" "Database path"]
   ["-l" "--limit N" "Max sessions" :default 20 :parse-fn parse-long]
   ["-i" "--index" "Index conversations before listing"]
   ["-a" "--all" "Index/list conversations from all projects (not just current)"]
   ["-h" "--help" "Show help"]])

(defn cmd-conversations
  [{:keys [opts]}]
  (let [project-root (:project-root opts)
        db-path      (resolve-db-path opts project-root)]
    (with-db-conn
      db-path
      (fn [db]
        (when (:index opts)
          (if (:all opts)
            (do (println "Indexing all Claude Code conversations...")
                (conversation/index-all-conversations! db)
                (println "Done."))
            (index-conversations! db project-root))
          (with-ollama-provider
            db
            (fn [provider _dim]
              (conversation/embed-conversations! db provider))))
        (let [sessions (db-search/list-conversation-sessions db :limit (:limit opts))]
          (if (empty? sessions)
            (println "No conversation sessions found. Run with --index to index conversations first.")
            (do (println (format "%-38s %-20s %6s %8s  %s" "SESSION-ID" "DATE" "MSGS" "COST" "TITLE"))
                (println (apply str (repeat 100 "-")))
                (doseq [s sessions]
                  (println
                    (format
                      "%-38s %-20s %6d %8s  %s"
                      (:session_id s)
                      (if (:started_at s) (str (java.time.Instant/ofEpochMilli (:started_at s))) "?")
                      (or (:message_count s) 0)
                      (if (and
                            (:total_cost_usd s)
                            (pos? (:total_cost_usd s)))
                        (format "$%.2f" (:total_cost_usd s))
                        "-")
                      (or (:title s) "(untitled)")))))))))))

;; ---------------------------------------------------------------------------
;; Subcommand: conversation (view single session)
;; ---------------------------------------------------------------------------

(def conversation-opts
  [["-d" "--db PATH" "Database path"]
   ["-p" "--project-root PATH" "Project root directory" :default "."]
   ["-r" "--role ROLE" "Filter by role (user/assistant)"]
   ["-h" "--help" "Show help"]])

(defn cmd-conversation
  [{:keys [opts args]}]
  (when (empty? args)
    (println "Usage: vestiga conversation [opts] <session-id>")
    (System/exit 1))
  (let [session-id (first args)
        db-path    (resolve-db-path opts (:project-root opts))]
    (with-db-conn
      db-path
      (fn [db]
        (let [session (ops/get-conversation-session-by-session-id db session-id)]
          (if-not session
            (println "Session not found:" session-id)
            (let [messages (ops/get-conversation-messages db (:id session) :role (:role opts))]
              (println (str "Session: " (:session_id session)))
              (println (str "Title:   " (or (:title session) "(untitled)")))
              (println (str "Project: " (or (:project_path session) "?")))
              (println (str "Messages: " (:message_count session)))
              (println)
              (doseq [m messages]
                (println
                  (format
                    "--- [%s] %s %s ---"
                    (str/upper-case (or (:role m) "?"))
                    (if (:timestamp m) (str (java.time.Instant/ofEpochMilli (:timestamp m))) "?")
                    (if (:model m) (str "(" (:model m) ")") "")))
                (println (:content_text m))
                (when (:tool_names m)
                  (println (str "  Tools: " (:tool_names m))))
                (println)))))))))

;; ---------------------------------------------------------------------------
;; Subcommand: conversation-search
;; ---------------------------------------------------------------------------

(def conversation-search-opts
  [["-d" "--db PATH" "Database path"]
   ["-p" "--project-root PATH" "Project root directory" :default "."]
   ["-l" "--limit N" "Max results" :default 20 :parse-fn parse-long]
   ["-r" "--role ROLE" "Filter by role (user/assistant)"]
   ["-t" "--tool TOOL" "Filter by tool name"]
   ["-h" "--help" "Show help"]])

(defn cmd-conversation-search
  [{:keys [opts args]}]
  (when (empty? args)
    (println "Usage: vestiga conversation-search [opts] <query>")
    (System/exit 1))
  (let [query   (str/join " " args)
        db-path (resolve-db-path opts (:project-root opts))]
    (with-db-conn
      db-path
      (fn [db]
        (let [results
              (search/search-conversations db query :limit (:limit opts) :role (:role opts) :tool-name (:tool opts))]
          (if (empty? results)
            (println "No matching conversation messages found.")
            (doseq [r results]
              (println
                (format
                  "[%s] %s | %s | %s"
                  (or (:role r) "?")
                  (or (:title r) "(untitled)")
                  (or (:session_id r) "?")
                  (if (:timestamp r) (str (java.time.Instant/ofEpochMilli (:timestamp r))) "?")))
              (println (str "  " (or (:content_snippet r) "")))
              (when (:tool_names r)
                (println (str "  Tools: " (:tool_names r))))
              (println))))))))

;; ---------------------------------------------------------------------------
;; Top-level dispatch
;; ---------------------------------------------------------------------------

(def subcommands
  {"mcp"                 {:fn   cmd-mcp
                          :opts mcp-opts
                          :desc "Start the MCP JSON-RPC server (for AI tool integration)"}
   "index"               {:fn   cmd-index
                          :opts index-opts
                          :desc "Index a project for searching"}
   "search"              {:fn   cmd-search
                          :opts search-opts
                          :desc "Search indexed code"}
   "refs"                {:fn   cmd-refs
                          :opts refs-opts
                          :desc "Find all references to a symbol"}
   "deps"                {:fn   cmd-deps
                          :opts deps-opts
                          :desc "Find all namespaces that depend on a namespace"}
   "impact"              {:fn   cmd-impact
                          :opts impact-opts
                          :desc "Analyse impact of changing a symbol"}
   "history"             {:fn   cmd-history
                          :opts history-opts
                          :desc "Search git commit history"}
   "hotspots"            {:fn   cmd-hotspots
                          :opts hotspots-opts
                          :desc "Find most frequently changed files"}
   "conversations"       {:fn   cmd-conversations
                          :opts conversations-opts
                          :desc "List indexed conversation sessions"}
   "conversation"        {:fn   cmd-conversation
                          :opts conversation-opts
                          :desc "View a single conversation session"}
   "conversation-search" {:fn   cmd-conversation-search
                          :opts conversation-search-opts
                          :desc "Search across conversation history"}})

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
