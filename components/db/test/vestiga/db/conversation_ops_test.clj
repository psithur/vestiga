(ns vestiga.db.conversation-ops-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.interface.ops :as ops]
    [vestiga.db.interface.search :as db-search]
    [vestiga.test-helpers :as h]))

(deftest conversation-crud-test
  (h/with-temp-db
    (fn [db]
      (testing "upsert source"
        (let [source-id (ops/upsert-conversation-source!
                          db
                          {:source-path     "/tmp/test.jsonl"
                           :provider        "claude-code"
                           :project-path    "/home/user/project"
                           :last-line-count 100})]
          (is
            (pos? source-id))

          (testing "get source"
            (let [source (ops/get-conversation-source db "/tmp/test.jsonl")]
              (is
                (= source-id (:id source)))
              (is
                (= 100 (:last_line_count source)))))

          (testing "upsert session"
            (let [session-id (ops/upsert-conversation-session!
                               db
                               {:source-id        source-id
                                :session-id       "sess-001"
                                :provider         "claude-code"
                                :project-path     "/home/user/project"
                                :title            "Test session"
                                :started-at       1700000000000
                                :ended-at         1700001000000
                                :message-count    5
                                :total-tokens-in  1000
                                :total-tokens-out 500
                                :total-cost-usd   0.10})]
              (is
                (pos? session-id))

              (testing "insert messages"
                (ops/insert-conversation-message!
                  db
                  {:session-row-id session-id
                   :message-id     "msg-001"
                   :role           "user"
                   :content-text   "How do I use FTS5 search in SQLite?"
                   :model          nil
                   :timestamp      1700000000000
                   :tokens-in      nil
                   :tokens-out     nil
                   :cost-usd       nil
                   :tool-names     nil
                   :is-sidechain   false})
                (ops/insert-conversation-message!
                  db
                  {:session-row-id session-id
                   :message-id     "msg-002"
                   :role           "assistant"
                   :content-text   "FTS5 is a virtual table module that provides full-text search."
                   :model          "claude-opus-4-6"
                   :timestamp      1700000005000
                   :tokens-in      500
                   :tokens-out     200
                   :cost-usd       0.05
                   :tool-names     ["Read" "Bash"]
                   :is-sidechain   false}))

              (testing "get messages"
                (let [msgs (ops/get-conversation-messages db session-id)]
                  (is
                    (= 2 (count msgs)))
                  (is
                    (= "user" (:role (first msgs))))
                  (is
                    (= "assistant" (:role (second msgs))))))

              (testing "get messages filtered by role"
                (let [asst-msgs (ops/get-conversation-messages db session-id :role "assistant")]
                  (is
                    (= 1 (count asst-msgs)))
                  (is
                    (= "Read,Bash" (:tool_names (first asst-msgs))))))

              (testing "lookup session by uuid"
                (let [s (ops/get-conversation-session-by-session-id db "sess-001")]
                  (is
                    (some? s))
                  (is
                    (= "Test session" (:title s)))))))

          (testing "list sessions"
            (let [sessions (db-search/list-conversation-sessions db :limit 10)]
              (is
                (= 1 (count sessions)))
              (is
                (= "sess-001" (:session_id (first sessions)))))))))))

(deftest conversation-fts-search-test
  (h/with-temp-db
    (fn [db]
      (let [source-id  (ops/upsert-conversation-source!
                         db
                         {:source-path "/tmp/search-test.jsonl"
                          :provider    "claude-code"})
            session-id (ops/upsert-conversation-session!
                         db
                         {:source-id     source-id
                          :session-id    "sess-search"
                          :provider      "claude-code"
                          :title         "Search test session"
                          :started-at    1700000000000
                          :message-count 2})]
        (ops/insert-conversation-message!
          db
          {:session-row-id session-id
           :message-id     "s-msg-001"
           :role           "user"
           :content-text   "Implement incremental indexing with hash-based change detection"
           :timestamp      1700000000000})
        (ops/insert-conversation-message!
          db
          {:session-row-id session-id
           :message-id     "s-msg-002"
           :role           "assistant"
           :content-text   "I will implement file tracking with content hashes for incremental re-indexing."
           :timestamp      1700000001000
           :tool-names     ["Edit" "Write"]})

        (testing "FTS search finds matching messages"
          (let [results (db-search/search-conversations db "incremental indexing" :limit 10)]
            (is
              (pos? (count results)))
            (is
              (some #(= "user" (:role %)) results))))

        (testing "FTS search with role filter"
          (let [results (db-search/search-conversations db "indexing" :role "assistant" :limit 10)]
            (is
              (every? #(= "assistant" (:role %)) results))))

        (testing "FTS search with tool filter"
          (let [results (db-search/search-conversations db "indexing" :tool-name "Edit" :limit 10)]
            (is
              (pos? (count results)))))

        (testing "FTS search returns session context"
          (let [results (db-search/search-conversations db "incremental" :limit 1)]
            (when (seq results)
              (is
                (= "Search test session" (:title (first results))))
              (is
                (= "sess-search" (:session_id (first results)))))))))))
