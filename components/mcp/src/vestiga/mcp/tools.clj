(ns vestiga.mcp.tools
  (:require
    [clojure.string :as str]
    [vestiga.db.interface.search :as db-search]
    [vestiga.search.interface :as search]))

(def ^:dynamic *db* nil)
(def ^:dynamic *project-root* nil)

(defn- ensure-fresh-index!
  "Run a quick incremental re-index if the project root is known.
   Only re-chunks files whose content hash changed. Skips embeddings
   for speed — those are updated on full index runs."
  []
  (when (and
          *db*
          *project-root*)
    (try (let [index! (requiring-resolve 'vestiga.index.interface/index-project!)]
           (index! *db* *project-root* :skip-embeddings true))
         (catch Exception e
           ;; Don't let index failures break queries
           (let [log-warn (requiring-resolve 'clojure.tools.logging/warn)]
             (log-warn "Auto re-index failed:" (.getMessage e)))))))

(def tool-definitions
  [{:name "search_code"
    :description
    "Search code in the indexed codebase using natural language or code patterns. Uses hybrid BM25 text search and semantic vector search."

    :inputSchema {:type       "object"
                  :properties {:query     {:type        "string"
                                           :description "Search query — natural language or code pattern"}
                               :limit     {:type        "integer"
                                           :description "Max results (default 20)"
                                           :default     20}
                               :kinds     {:type "array"
                                           :items {:type "string"}
                                           :description
                                           "Filter by symbol kind: defn, defmacro, defprotocol, defrecord, def, ns"}
                               :namespace {:type        "string"
                                           :description "Filter by namespace (supports * glob)"}}
                  :required   ["query"]}}

   {:name        "find_references"
    :description "Find all references to a symbol. Provide a fully qualified name like my.ns/my-fn."
    :inputSchema {:type       "object"
                  :properties {:qualified_name {:type "string"
                                                :description
                                                "Fully qualified symbol name, e.g. my.app.core/handle-request"}}
                  :required   ["qualified_name"]}}

   {:name        "find_dependents"
    :description "Find all namespaces that depend on the given namespace."
    :inputSchema {:type       "object"
                  :properties {:namespace {:type        "string"
                                           :description "Namespace name, e.g. my.app.db"}}
                  :required   ["namespace"]}}

   {:name "impact_analysis"
    :description
    "Analyse the impact of changing a symbol: who calls it, what namespaces depend on it, and recent git history."

    :inputSchema {:type       "object"
                  :properties {:qualified_name {:type        "string"
                                                :description "Fully qualified symbol name"}}
                  :required   ["qualified_name"]}}

   {:name        "search_history"
    :description "Search git commit history for changes related to a topic or file."
    :inputSchema {:type       "object"
                  :properties {:query     {:type        "string"
                                           :description "Search query for commit messages and changed files"}
                               :limit     {:type        "integer"
                                           :description "Max results (default 20)"
                                           :default     20}
                               :file_path {:type        "string"
                                           :description "Filter to commits touching this file path"}}
                  :required   ["query"]}}

   {:name        "index_project"
    :description "Index or re-index the current project. Run this after significant code changes."
    :inputSchema {:type       "object"
                  :properties {:project_root {:type        "string"
                                              :description "Path to the project root directory"}
                               :full         {:type        "boolean"
                                              :description "Force full re-index (default: incremental)"
                                              :default     false}}
                  :required   ["project_root"]}}

   {:name "edit_code"
    :description
    "Apply AST-targeted edits to Clojure source files. Each edit targets a
  definition by namespace-qualified name (e.g. 'my.app.core/handle-request')
  rather than by line number or text matching.

  Operations:
  - replace_form: replace an entire top-level form (defn, def, defprotocol, etc.)
  - replace_body: replace only the body of a single-arity defn
  - add_form_before / add_form_after: insert a form adjacent to a target
  - delete_form: remove a top-level form
  - add_require: add a :require clause to a namespace (idempotent)
  - replace_ns: replace the entire (ns ...) form
  - append_to_ns: add a new form at the end of a namespace's file
  - replace_defmethod: replace a specific defmethod by dispatch value

  Target format:
  - Most ops: 'my.ns/my-fn' (namespace-qualified symbol name)
  - replace_defmethod: 'my.ns/multi-fn :dispatch-val'
  - add_require: require spec like 'clojure.string :as str'
  - append_to_ns: namespace name like 'my.app.core'

  Run index_project first to ensure the index is current."

    :inputSchema {:type       "object"
                  :properties {:project_root {:type        "string"
                                              :description "Path to the project root directory"}
                               :operations   {:type  "array"
                                              :items {:type       "object"
                                                      :required   ["operation"]
                                                      :properties {:operation {:type "string"
                                                                               :enum ["replace_form" "replace_body"
                                                                                      "add_form_before" "add_form_after"
                                                                                      "delete_form" "add_require"
                                                                                      "replace_ns" "append_to_ns"
                                                                                      "replace_defmethod"]}
                                                                   :target    {:type "string"}
                                                                   :content   {:type "string"}
                                                                   :file      {:type "string"}}}}}
                  :required   ["project_root" "operations"]}}])

(defn list-tools
  []
  tool-definitions)

(defn- format-search-results
  [results]
  (if (empty? results)
    "No results found."
    (str/join
      "\n\n---\n\n"
      (map
        (fn [r]
          (str
            (or (:qualified_name r) (:qualified-name r) "unknown")
            " ["
            (or (:kind r) "?")
            "]"
            "\n"
            (or (:file_path r) (:file-path r) "?")
            ":"
            (or (:start_line r) (:start-line r) "?")
            "\n\n"
            (or (:content r) "")))
        results))))

(defn- format-refs
  [refs]
  (if (empty? refs)
    "No references found."
    (str/join
      "\n"
      (map
        (fn [r]
          (str
            (:file_path r)
            ":"
            (:row r)
            ":"
            (:col r)
            " — "
            (:from_ns r)
            "/"
            (:from_name r)
            " calls "
            (:to_ns r)
            "/"
            (:to_name r)))
        refs))))

(defn- format-impact
  [impact]
  (str
    "## Callers ("
    (count (:callers impact))
    ")\n"
    (if (empty? (:callers impact))
      "None found.\n"
      (str/join
        "\n"
        (map #(str "  " (:file_path %) ":" (:row %) " — " (:from_ns %) "/" (:from_name %)) (:callers impact))))
    "\n\n## Namespace Dependents ("
    (count (:ns-dependents impact))
    ")\n"
    (if (empty? (:ns-dependents impact))
      "None found.\n"
      (str/join "\n" (map #(str "  " (:from_ns %)) (:ns-dependents impact))))
    "\n\n## Recent Commits ("
    (count (:recent-commits impact))
    ")\n"
    (if (empty? (:recent-commits impact))
      "None found.\n"
      (str/join "\n" (map #(str "  " (:sha %) " " (:message %)) (:recent-commits impact))))))

(defmulti call-tool
  (fn [name _args]
    name))

(defmethod call-tool "search_code"
  [_ args]
  (ensure-fresh-index!)
  (let [results (search/search
                  *db*
                  (:query args)
                  :limit
                  (or (:limit args) 20)
                  :kinds
                  (some-> (:kinds args)
                          set)
                  :namespace
                  (:namespace args))]
    {:content [{:type "text"
                :text (format-search-results results)}]}))

(defmethod call-tool "find_references"
  [_ args]
  (ensure-fresh-index!)
  (let [refs (search/find-references *db* (:qualified_name args))]
    {:content [{:type "text"
                :text (format-refs refs)}]}))

(defmethod call-tool "find_dependents"
  [_ args]
  (ensure-fresh-index!)
  (let [deps (search/find-dependents *db* (:namespace args))]
    {:content [{:type "text"
                :text (if (empty? deps) "No dependents found." (str/join "\n" (map :from_ns deps)))}]}))

(defmethod call-tool "impact_analysis"
  [_ args]
  (ensure-fresh-index!)
  (let [impact (search/impact-analysis *db* (:qualified_name args))]
    {:content [{:type "text"
                :text (format-impact impact)}]}))

(defmethod call-tool "search_history"
  [_ args]
  (let [limit        (or (:limit args) 20)
        file-path    (:file_path args)
        msg-results  (db-search/search-commits *db* (:query args) :limit limit :file-path file-path)
        diff-results (db-search/search-patches *db* (:query args) :limit limit :file-path file-path)
        msg-text     (when (seq msg-results)
                       (str
                         "## Commits (message match)\n\n"
                         (str/join "\n" (map #(str (:sha %) " | " (:author %) " | " (:message %)) msg-results))))
        diff-text    (when (seq diff-results)
                       (str
                         "## Commits (diff match)\n\n"
                         (str/join
                           "\n\n"
                           (map
                             #(str
                                (:sha %)
                                " | "
                                (:author %)
                                " | "
                                (:message %)
                                "\n  "
                                (:file_path %)
                                " ["
                                (:change_type %)
                                "]"
                                "\n  "
                                (:patch_snippet %))
                             diff-results))))
        combined     (str/join "\n\n" (remove nil? [msg-text diff-text]))]
    {:content [{:type "text"
                :text (if (str/blank? combined) "No matching commits found." combined)}]}))

(defmethod call-tool "index_project"
  [_ args]
  (let [coordinator (requiring-resolve 'vestiga.index.interface/index-project!)]
    (coordinator
      *db*
      (:project_root args)
      :full
      (boolean (:full args))
      :skip-embeddings
      (boolean (:skip_embeddings args)))
    {:content [{:type "text"
                :text (str "Indexing complete for " (:project_root args))}]}))

(defn- format-edit-results
  [result]
  (if (:success result)
    (str
      "All "
      (count (:results result))
      " edit(s) applied successfully.\n\n"
      (str/join
        "\n"
        (map-indexed
          (fn [i r]
            (str (inc i) ". " (get-in r [:operation :operation]) " " (get-in r [:operation :target]) " → " (:file r)))
          (:results result))))
    (str
      "Edit failed.\n\n"
      (str/join
        "\n"
        (map
          (fn [r]
            (if (= :error (:status r))
              (str "ERROR: " (:message r))
              (str "OK: " (get-in r [:operation :operation]) " " (get-in r [:operation :target]))))
          (:results result))))))

(defmethod call-tool "edit_code"
  [_ args]
  (let [project-root    (:project_root args)
        operations      (:operations args)
        kondo           (requiring-resolve 'vestiga.index.clj-kondo/run-analysis)
        discover        (requiring-resolve 'vestiga.config.interface/discover-index-paths)
        index-paths     (discover project-root)
        ;; Fresh analysis on every edit call — stale line numbers cause failures
        analysis-result (kondo project-root index-paths)
        analysis        (:analysis analysis-result)]
    (if-not analysis
      {:content [{:type "text"
                  :text (str "clj-kondo analysis failed: " (:error analysis-result))}]
       :isError true}
      (let [ast-apply (requiring-resolve 'vestiga.ast.interface/apply-edits!)
            result    (ast-apply analysis project-root operations)]
        {:content [{:type "text"
                    :text (format-edit-results result)}]
         :isError (not (:success result))}))))

(defmethod call-tool :default
  [name _]
  {:content [{:type "text"
              :text (str "Unknown tool: " name)}]
   :isError true})
