(ns vestiga.conversation.interface
  (:require
    [vestiga.conversation.claude-code :as claude-code]
    [vestiga.conversation.discovery :as discovery]
    [vestiga.conversation.indexer :as indexer]))

;; -- Discovery ---------------------------------------------------------------

(defn claude-code-dir
  "Return the path to ~/.claude/projects/."
  []
  (discovery/claude-code-dir))

(defn encode-project-path
  "Encode a project root path to the directory name Claude Code uses."
  [path]
  (discovery/encode-project-path path))

(defn decode-project-path
  "Decode a Claude Code project directory name back to the original path."
  [encoded]
  (discovery/decode-project-path encoded))

(defn find-sessions
  "Find all JSONL session files for a given project root."
  [project-root]
  (discovery/find-sessions project-root))

(defn find-all-sessions
  "Find all Claude Code projects and their session files."
  []
  (discovery/find-all-sessions))

;; -- Parsing -----------------------------------------------------------------

(defn parse-session
  "Parse a JSONL session file and return normalized messages.
   Options: :offset N — skip first N lines for incremental parsing."
  [path & opts]
  (apply claude-code/parse-session path opts))

(defn count-lines
  "Count the number of lines in a file."
  [path]
  (claude-code/count-lines path))

(defn normalize-message
  "Transform a raw JSONL entry into a normalized conversation message map."
  [raw]
  (claude-code/normalize-message raw))

(defn extract-session-title
  "Extract a session title from normalized messages."
  [messages]
  (claude-code/extract-session-title messages))

;; -- Indexing -----------------------------------------------------------------

(defn index-conversations!
  "Index Claude Code conversation sessions for a project into the DB.
   Supports incremental indexing — only parses new lines since last index."
  [db project-root]
  (indexer/index-conversations! db project-root))
