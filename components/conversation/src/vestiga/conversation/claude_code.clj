(ns vestiga.conversation.claude-code
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.string :as str]))

(def ^:private max-content-length "Maximum characters to store per message content." 10000)

(def ^:private max-title-length "Maximum characters for session title." 200)

(def ^:private indexable-types
  "Message types we index (skip system, progress, file-history-snapshot, etc.)."
  #{"user" "assistant"})

(defn- parse-iso-timestamp
  "Parse an ISO 8601 timestamp string to epoch milliseconds."
  [ts]
  (when ts
    (try (.toEpochMilli (java.time.Instant/parse ts)) (catch Exception _ nil))))

(defn- truncate
  "Truncate string to n chars."
  [s n]
  (if (and
        s
        (> (count s) n))
    (subs s 0 n)
    s))

(defn extract-content-text
  "Collapse message.content (string or array) into a single text string.
   For arrays: concatenate text items. Skip tool_result content (too noisy)."
  [content]
  (cond
    (string? content)
    content

    (sequential? content)
    (->> content
         (keep
           (fn [item]
             (cond
               (string? item)
               item
               (map? item)
               (case (get item "type")
                 "text"     (get item "text")
                 "thinking" (get item "thinking")
                 nil))))
         (str/join "\n"))

    :else
    ""))

(defn extract-tool-names
  "Pull tool names from tool_use content items."
  [content]
  (when (sequential? content)
    (->> content
         (keep
           (fn [item]
             (when (and
                     (map? item)
                     (= "tool_use" (get item "type")))
               (get item "name"))))
         distinct
         vec)))

(defn normalize-message
  "Transform a raw JSONL entry into a normalized conversation message map.
   Returns nil for message types we don't index."
  [raw]
  (let [msg-type (get raw "type")]
    (when (indexable-types msg-type)
      (let [message      (get raw "message")
            content      (or (get message "content") (get raw "content"))
            usage        (get message "usage")
            content-text (truncate (extract-content-text content) max-content-length)
            tool-names   (extract-tool-names (get message "content"))]
        {:conversation/message-id   (get raw "uuid")
         :conversation/session-id   (get raw "sessionId")
         :conversation/parent-id    (get raw "parentUuid")
         :conversation/role         (or (get message "role") msg-type)
         :conversation/content-text (or content-text "")
         :conversation/model        (get message "model")
         :conversation/timestamp    (parse-iso-timestamp (get raw "timestamp"))
         :conversation/tokens-in    (get usage "input_tokens")
         :conversation/tokens-out   (get usage "output_tokens")
         :conversation/cost-usd     (get raw "costUSD")
         :conversation/tool-names   (when (seq tool-names)
                                      tool-names)
         :conversation/is-sidechain (boolean (get raw "isSidechain"))}))))

(defn parse-jsonl-file
  "Parse a JSONL file into a seq of raw JSON maps.
   Options:
     :offset - skip first N lines (for incremental parsing)"
  [path &
   {:keys [offset]
    :or   {offset 0}}]
  (with-open [rdr (io/reader path)]
    (let [lines (line-seq rdr)]
      (->> (if (pos? offset) (drop offset lines) lines)
           (keep
             (fn [line]
               (when-not (str/blank? line) (try (json/read-str line) (catch Exception _ nil)))))
           doall))))

(defn count-lines
  "Count the number of lines in a file."
  [path]
  (with-open [rdr (io/reader path)]
    (count (line-seq rdr))))

(defn parse-session
  "Parse a JSONL session file and return normalized messages.
   Options:
     :offset - skip first N lines for incremental parsing
   Returns a vec of normalized message maps (filtered to user/assistant only)."
  [path &
   {:keys [offset]
    :or   {offset 0}}]
  (->> (parse-jsonl-file path :offset offset)
       (keep normalize-message)
       vec))

(defn extract-session-title
  "Extract a session title from normalized messages.
   Uses first user message text, truncated."
  [messages]
  (when-let [first-user
             (->> messages
                  (filter #(= "user" (:conversation/role %)))
                  first)]
    (let [text (:conversation/content-text first-user)]
      (when-not (str/blank? text) (truncate (str/trim (first (str/split-lines text))) max-title-length)))))
