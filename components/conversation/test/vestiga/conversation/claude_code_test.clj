(ns vestiga.conversation.claude-code-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.conversation.claude-code :as cc]))

(def ^:private user-msg-raw
  {"type"        "user"
   "uuid"        "msg-001"
   "parentUuid"  nil
   "sessionId"   "sess-001"
   "timestamp"   "2026-04-01T10:00:00.000Z"
   "isSidechain" false
   "message"     {"role"    "user"
                  "content" "How do I implement FTS5 search?"}})

(def ^:private assistant-msg-raw
  {"type"        "assistant"
   "uuid"        "msg-002"
   "parentUuid"  "msg-001"
   "sessionId"   "sess-001"
   "timestamp"   "2026-04-01T10:00:05.000Z"
   "isSidechain" false
   "costUSD"     0.05
   "message"     {"role"        "assistant"
                  "model"       "claude-opus-4-6"
                  "stop_reason" "end_turn"
                  "usage"       {"input_tokens"  1500
                                 "output_tokens" 200}
                  "content"     [{"type" "text"
                                  "text" "You can use FTS5 like this..."}
                                 {"type"  "tool_use"
                                  "id"    "toolu_01"
                                  "name"  "Read"
                                  "input" {"file_path" "/path/to/file.clj"}}]}})

(def ^:private assistant-with-thinking
  {"type"      "assistant"
   "uuid"      "msg-003"
   "sessionId" "sess-001"
   "timestamp" "2026-04-01T10:00:10.000Z"
   "message"   {"role"    "assistant"
                "content" [{"type"     "thinking"
                            "thinking" "Let me think about this..."}
                           {"type" "text"
                            "text" "Here's my answer."}]}})

(def ^:private system-msg-raw
  {"type"    "system"
   "uuid"    "msg-sys"
   "subtype" "info"
   "content" "System info"})

(def ^:private progress-msg-raw
  {"type" "progress"
   "uuid" "msg-prog"
   "data" {"type"   "agent_progress"
           "status" "running"}})

(deftest normalize-user-message
  (let [result (cc/normalize-message user-msg-raw)]
    (is
      (some? result))
    (is
      (= "msg-001" (:conversation/message-id result)))
    (is
      (= "sess-001" (:conversation/session-id result)))
    (is
      (nil? (:conversation/parent-id result)))
    (is
      (= "user" (:conversation/role result)))
    (is
      (= "How do I implement FTS5 search?" (:conversation/content-text result)))
    (is
      (nil? (:conversation/model result)))
    (is
      (= 1775037600000 (:conversation/timestamp result)))
    (is
      (nil? (:conversation/tokens-in result)))
    (is
      (nil? (:conversation/tool-names result)))
    (is
      (false? (:conversation/is-sidechain result)))))

(deftest normalize-assistant-message
  (let [result (cc/normalize-message assistant-msg-raw)]
    (is
      (some? result))
    (is
      (= "msg-002" (:conversation/message-id result)))
    (is
      (= "msg-001" (:conversation/parent-id result)))
    (is
      (= "assistant" (:conversation/role result)))
    (is
      (= "claude-opus-4-6" (:conversation/model result)))
    (is
      (= 1500 (:conversation/tokens-in result)))
    (is
      (= 200 (:conversation/tokens-out result)))
    (is
      (= 0.05 (:conversation/cost-usd result)))
    (is
      (= ["Read"] (:conversation/tool-names result)))
    (testing "content text extracts text items, skips tool_use"
      (is
        (= "You can use FTS5 like this..." (:conversation/content-text result))))))

(deftest normalize-thinking-content
  (let [result (cc/normalize-message assistant-with-thinking)]
    (testing "thinking content is included in text"
      (is
        (clojure.string/includes? (:conversation/content-text result) "Let me think"))
      (is
        (clojure.string/includes? (:conversation/content-text result) "Here's my answer")))))

(deftest filters-non-indexable-types
  (is
    (nil? (cc/normalize-message system-msg-raw)))
  (is
    (nil? (cc/normalize-message progress-msg-raw)))
  (is
    (nil? (cc/normalize-message {"type" "file-history-snapshot"})))
  (is
    (nil? (cc/normalize-message {"type" "summary"})))
  (is
    (nil? (cc/normalize-message {"type" "queue-operation"}))))

(deftest extract-content-text-handles-string
  (is
    (= "hello" (cc/extract-content-text "hello"))))

(deftest extract-content-text-handles-array
  (is
    (= "Hello\nWorld"
       (cc/extract-content-text
         [{"type" "text"
           "text" "Hello"}
          {"type" "text"
           "text" "World"}]))))

(deftest extract-content-text-handles-nil
  (is
    (= "" (cc/extract-content-text nil))))

(deftest extract-tool-names-test
  (is
    (= ["Read" "Bash"]
       (cc/extract-tool-names
         [{"type" "text"
           "text" "foo"}
          {"type" "tool_use"
           "name" "Read"}
          {"type" "tool_use"
           "name" "Bash"}])))
  (is
    (nil? (cc/extract-tool-names "not-an-array")))
  (is
    (empty?
      (cc/extract-tool-names
        [{"type" "text"
          "text" "no tools"}]))))

(deftest truncation
  (let [long-content (apply str (repeat 15000 "x"))
        raw          {"type"      "user"
                      "uuid"      "trunc-msg"
                      "sessionId" "sess-trunc"
                      "timestamp" "2026-01-01T00:00:00.000Z"
                      "message"   {"role"    "user"
                                   "content" long-content}}
        result       (cc/normalize-message raw)]
    (is
      (= 10000 (count (:conversation/content-text result))))))

(deftest extract-session-title-test
  (let [msgs [{:conversation/role         "assistant"
               :conversation/content-text "I can help"}
              {:conversation/role         "user"
               :conversation/content-text "How to search?\nMore details here."}]]
    (is
      (= "How to search?" (cc/extract-session-title msgs))))
  (testing "returns nil for empty messages"
    (is
      (nil? (cc/extract-session-title [])))))
