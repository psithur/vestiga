(ns vestiga.conversation.indexer
  "Orchestrates indexing of Claude Code conversation sessions into the DB."
  (:require
    [clojure.tools.logging :as log]
    [vestiga.conversation.claude-code :as claude-code]
    [vestiga.conversation.discovery :as discovery]
    [vestiga.db.interface.ops :as ops]
    [vestiga.embed.interface :as embed]))

(def ^:private max-embed-chars "Max characters to send for a single embedding." 8000)

(defn- truncate-for-embedding [text] (if (> (count text) max-embed-chars) (subs text 0 max-embed-chars) text))

(defn- index-sessions!
  "Index a seq of {:file, :session-id} maps for a given project-path."
  [db project-path sessions]
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
                                :project-path    project-path
                                :last-line-count total-lines})
              all-msgs       (if (pos? prev-lines) (claude-code/parse-session file-path) messages)
              title          (claude-code/extract-session-title all-msgs)
              timestamps     (keep :conversation/timestamp all-msgs)
              session-row-id (ops/upsert-conversation-session!
                               db
                               {:source-id        source-id
                                :session-id       session-id
                                :provider         "claude-code"
                                :project-path     project-path
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
               :is-sidechain   (:conversation/is-sidechain msg)})))))))

(def ^:private embed-batch-size "Number of messages to embed and persist per batch." 32)

(defn embed-conversations!
  "Generate embeddings for conversation messages that don't yet have them.
   Embeds and persists in small batches so progress survives failures."
  [db provider]
  (let [unembedded (ops/get-conversation-messages-without-embeddings db)]
    (when (seq unembedded)
      (let [total   (count unembedded)
            batches (partition-all embed-batch-size unembedded)]
        (log/info "Embedding" total "conversation messages in" (count batches) "batches")
        (loop [remaining batches
               embedded  0]
          (if-let [batch (first remaining)]
            (let [texts      (mapv #(truncate-for-embedding (:content_text %)) batch)
                  embeddings (try (embed/embed-texts provider texts)
                                  (catch Exception e (log/warn "Batch failed, skipping:" (.getMessage e)) nil))
                  n          (count (or embeddings []))]
              (when (pos? n)
                (doseq [[msg emb] (map vector batch embeddings)]
                  (ops/upsert-conversation-message-embedding! db (:id msg) emb)))
              (let [done (+ embedded n)]
                (when (pos? n)
                  (log/info "Embedded" done "/" total "conversation messages"))
                (recur (rest remaining) done)))
            (log/info "Conversation embedding complete:" embedded "/" total)))))))

(defn index-conversations!
  "Index Claude Code conversation sessions for a project into the DB."
  [db project-root]
  (let [sessions (discovery/find-sessions project-root)]
    (when (seq sessions)
      (index-sessions! db project-root sessions))))

(defn index-all-conversations!
  "Index all Claude Code conversation sessions across every project."
  [db]
  (doseq [{:keys [project-path sessions]} (discovery/find-all-sessions)]
    (index-sessions! db project-path sessions)))
