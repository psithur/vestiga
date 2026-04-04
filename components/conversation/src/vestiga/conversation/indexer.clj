(ns vestiga.conversation.indexer
  "Orchestrates indexing of Claude Code conversation sessions into the DB."
  (:require
    [vestiga.conversation.claude-code :as claude-code]
    [vestiga.conversation.discovery :as discovery]
    [vestiga.db.interface.ops :as ops]))

(defn index-conversations!
  "Index Claude Code conversation sessions for a project into the DB.
   Supports incremental indexing — only parses new lines since last index."
  [db project-root]
  (let [sessions (discovery/find-sessions project-root)]
    (when (seq sessions)
      (doseq [{:keys [file session-id]} sessions]
        (let [file-path   (.getAbsolutePath ^java.io.File file)
              source      (ops/get-conversation-source db file-path)
              prev-lines  (or (:last_line_count source) 0)
              total-lines (claude-code/count-lines file-path)]
          (when (> total-lines prev-lines)
            (let [messages       (claude-code/parse-session file-path :offset prev-lines)
                  source-id      (ops/upsert-conversation-source!
                                   db
                                   {:source-path     file-path
                                    :provider        "claude-code"
                                    :project-path    project-root
                                    :last-line-count total-lines})
                  ;; For stats/title, parse full file if incremental
                  all-msgs       (if (pos? prev-lines) (claude-code/parse-session file-path) messages)
                  title          (claude-code/extract-session-title all-msgs)
                  timestamps     (keep :conversation/timestamp all-msgs)
                  session-row-id (ops/upsert-conversation-session!
                                   db
                                   {:source-id        source-id
                                    :session-id       session-id
                                    :provider         "claude-code"
                                    :project-path     project-root
                                    :title            title
                                    :started-at       (when (seq timestamps)
                                                        (apply min timestamps))
                                    :ended-at         (when (seq timestamps)
                                                        (apply max timestamps))
                                    :message-count    (count all-msgs)
                                    :total-tokens-in  (reduce + 0 (keep :conversation/tokens-in all-msgs))
                                    :total-tokens-out (reduce + 0 (keep :conversation/tokens-out all-msgs))
                                    :total-cost-usd   (reduce + 0.0 (keep :conversation/cost-usd all-msgs))})]
              (doseq [msg messages]
                (ops/insert-conversation-message!
                  db
                  {:session-row-id session-row-id
                   :message-id     (:conversation/message-id msg)
                   :role           (:conversation/role msg)
                   :content-text   (:conversation/content-text msg)
                   :model          (:conversation/model msg)
                   :timestamp      (:conversation/timestamp msg)
                   :tokens-in      (:conversation/tokens-in msg)
                   :tokens-out     (:conversation/tokens-out msg)
                   :cost-usd       (:conversation/cost-usd msg)
                   :tool-names     (:conversation/tool-names msg)
                   :is-sidechain   (:conversation/is-sidechain msg)}))
              (count messages))))))))
