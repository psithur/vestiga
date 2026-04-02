(ns vestiga.embed.interface
  (:require
    [vestiga.embed.ollama :as ollama]
    [vestiga.embed.provider :as provider]))

(def EmbeddingProvider provider/EmbeddingProvider)

(defn embed-texts
  [this texts]
  (provider/embed-texts this texts))

(defn embedding-dim
  [this]
  (provider/embedding-dim this))

(defn provider-name
  [this]
  (provider/provider-name this))

(defn ->ollama-provider
  [& opts]
  (apply ollama/->ollama-provider opts))
