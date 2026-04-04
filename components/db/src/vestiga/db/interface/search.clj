(ns vestiga.db.interface.search
  (:require
    [vestiga.db.search :as impl]))

(defn bm25-search
  [db query-text & args]
  (apply impl/bm25-search db query-text args))

(defn search-commits
  [db query-text & args]
  (apply impl/search-commits db query-text args))

(defn find-by-qualified-name
  [db qualified-name]
  (impl/find-by-qualified-name db qualified-name))

(defn find-by-namespace
  [db project-id namespace-name]
  (impl/find-by-namespace db project-id namespace-name))

(defn find-refs-to-symbol
  [db project-id qualified-name]
  (impl/find-refs-to-symbol db project-id qualified-name))

(defn vector-search
  [db query-embedding & args]
  (apply impl/vector-search db query-embedding args))

(defn search-patches
  [db query-text & args]
  (apply impl/search-patches db query-text args))

(defn find-ns-dependents
  [db project-id namespace-name]
  (impl/find-ns-dependents db project-id namespace-name))

(defn hotspots
  [db & args]
  (apply impl/hotspots db args))

;; -- Conversations -----------------------------------------------------------

(defn search-conversations
  [db query-text & args]
  (apply impl/search-conversations db query-text args))

(defn list-conversation-sessions
  [db & args]
  (apply impl/list-conversation-sessions db args))
