(ns vestiga.db.interface.ops
  (:require
    [vestiga.db.ops :as impl]))

;; -- Projects ---------------------------------------------------------------

(defn upsert-project!
  [db project-map]
  (impl/upsert-project! db project-map))

(defn update-project-head!
  [db project-id head-sha]
  (impl/update-project-head! db project-id head-sha))

(defn get-project
  [db root-path]
  (impl/get-project db root-path))

;; -- Chunks -----------------------------------------------------------------

(defn insert-chunk!
  [db chunk-map]
  (impl/insert-chunk! db chunk-map))

(defn delete-chunks-for-file!
  [db project-id file-path]
  (impl/delete-chunks-for-file! db project-id file-path))

(defn delete-chunks-for-project!
  [db project-id]
  (impl/delete-chunks-for-project! db project-id))

(defn get-chunks-for-file
  [db project-id file-path]
  (impl/get-chunks-for-file db project-id file-path))

(defn get-chunk-by-qname
  [db qualified-name]
  (impl/get-chunk-by-qname db qualified-name))

(defn get-file-hash
  [db project-id file-path]
  (impl/get-file-hash db project-id file-path))

;; -- Refs -------------------------------------------------------------------

(defn insert-ref!
  [db ref-map]
  (impl/insert-ref! db ref-map))

(defn delete-refs-for-file!
  [db project-id file-path]
  (impl/delete-refs-for-file! db project-id file-path))

(defn delete-refs-for-project!
  [db project-id]
  (impl/delete-refs-for-project! db project-id))

(defn get-refs-to
  [db project-id to-ns to-name]
  (impl/get-refs-to db project-id to-ns to-name))

(defn get-refs-from
  [db project-id from-ns from-name]
  (impl/get-refs-from db project-id from-ns from-name))

;; -- Namespace Dependencies -------------------------------------------------

(defn insert-ns-dep!
  [db ns-dep-map]
  (impl/insert-ns-dep! db ns-dep-map))

(defn delete-ns-deps-for-project!
  [db project-id]
  (impl/delete-ns-deps-for-project! db project-id))

(defn get-ns-dependents
  [db project-id to-ns]
  (impl/get-ns-dependents db project-id to-ns))

(defn get-ns-dependencies
  [db project-id from-ns]
  (impl/get-ns-dependencies db project-id from-ns))

;; -- Commits ----------------------------------------------------------------

(defn insert-commit!
  [db commit-map]
  (impl/insert-commit! db commit-map))

(defn insert-commit-file!
  [db commit-file-map]
  (impl/insert-commit-file! db commit-file-map))

(defn get-commits-for-file
  [db project-id file-path & args]
  (apply impl/get-commits-for-file db project-id file-path args))

(defn get-latest-commit-sha
  [db project-id]
  (impl/get-latest-commit-sha db project-id))

;; -- Conversation Sources ----------------------------------------------------

(defn upsert-conversation-source!
  [db source-map]
  (impl/upsert-conversation-source! db source-map))

(defn get-conversation-source
  [db source-path]
  (impl/get-conversation-source db source-path))

(defn delete-conversation-source!
  [db source-path]
  (impl/delete-conversation-source! db source-path))

;; -- Conversation Sessions ---------------------------------------------------

(defn upsert-conversation-session!
  [db session-map]
  (impl/upsert-conversation-session! db session-map))

(defn get-conversation-session-by-session-id
  [db session-id]
  (impl/get-conversation-session-by-session-id db session-id))

;; -- Conversation Messages ---------------------------------------------------

(defn insert-conversation-message!
  [db msg-map]
  (impl/insert-conversation-message! db msg-map))

(defn get-conversation-messages
  [db session-row-id & args]
  (apply impl/get-conversation-messages db session-row-id args))

;; -- Embeddings --------------------------------------------------------------

(defn upsert-chunk-embedding!
  [db chunk-id embedding]
  (impl/upsert-chunk-embedding! db chunk-id embedding))

(defn get-chunks-without-embeddings
  [db project-id]
  (impl/get-chunks-without-embeddings db project-id))
