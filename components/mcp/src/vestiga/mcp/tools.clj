(ns vestiga.mcp.tools
  (:require
    [clojure.string :as str]
    [vestiga.db.interface.search :as db-search]
    [vestiga.search.interface :as search]))

(def ^:dynamic *db* nil)

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
                  :required   ["project_root"]}}])

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
  (let [refs (search/find-references *db* (:qualified_name args))]
    {:content [{:type "text"
                :text (format-refs refs)}]}))

(defmethod call-tool "find_dependents"
  [_ args]
  (let [deps (search/find-dependents *db* (:namespace args))]
    {:content [{:type "text"
                :text (if (empty? deps) "No dependents found." (str/join "\n" (map :from_ns deps)))}]}))

(defmethod call-tool "impact_analysis"
  [_ args]
  (let [impact (search/impact-analysis *db* (:qualified_name args))]
    {:content [{:type "text"
                :text (format-impact impact)}]}))

(defmethod call-tool "search_history"
  [_ args]
  (let [results (db-search/search-commits *db* (:query args) :limit (or (:limit args) 20) :file-path (:file_path args))]
    {:content [{:type "text"
                :text (if (empty? results)
                        "No matching commits found."
                        (str/join
                          "\n\n"
                          (map
                            (fn [c]
                              (str (:sha c) " | " (:author c) " | " (:message c)))
                            results)))}]}))

(defmethod call-tool "index_project"
  [_ args]
  (let [coordinator (requiring-resolve 'vestiga.index.interface/index-project!)]
    (coordinator *db* (:project_root args) :full (boolean (:full args)))
    {:content [{:type "text"
                :text (str "Indexing complete for " (:project_root args))}]}))

(defmethod call-tool :default
  [name _]
  {:content [{:type "text"
              :text (str "Unknown tool: " name)}]
   :isError true})
