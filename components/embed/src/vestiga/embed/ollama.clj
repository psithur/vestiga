(ns vestiga.embed.ollama
  (:require
    [clojure.data.json :as json]
    [clojure.tools.logging :as log]
    [vestiga.embed.provider :as provider])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(def default-model "nomic-embed-text")
(def default-base-url "http://localhost:11434")
(def default-batch-size 32)

(defn- make-http-client
  "Create an HttpClient with the given timeout."
  [timeout-secs]
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds timeout-secs))
      (.build)))

(defn- post-json
  "POST JSON to a URL and return parsed response."
  [^HttpClient client url body timeout-secs]
  (let [json-body (json/write-str body)
        request   (-> (HttpRequest/newBuilder)
                      (.uri (URI/create url))
                      (.header "Content-Type" "application/json")
                      (.timeout (Duration/ofSeconds timeout-secs))
                      (.POST (HttpRequest$BodyPublishers/ofString json-body))
                      (.build))
        response  (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode response))
      (json/read-str (.body response) :key-fn keyword))))

(defn- embed-batch
  "Embed a single batch of texts via Ollama API."
  [client base-url model texts timeout]
  (let [url      (str base-url "/api/embed")
        response (post-json
                   client
                   url
                   {:model model
                    :input texts}
                   timeout)]
    (when response
      (:embeddings response))))

(defn ->ollama-provider
  "Create an Ollama embedding provider."
  [&
   {:keys [model base-url timeout batch-size]
    :or   {model      default-model
           base-url   default-base-url
           timeout    120
           batch-size default-batch-size}}]
  (let [client (make-http-client timeout)
        dim    (atom nil)]
    (reify
      provider/EmbeddingProvider
      (embed-texts [_ texts]
        (let [batches        (partition-all batch-size texts)
              all-embeddings (mapcat (fn [batch]
                                       (let [result (embed-batch client base-url model (vec batch) timeout)]
                                         (when (and
                                                 (nil? @dim)
                                                 (seq result))
                                           (reset! dim (count (first result))))
                                         result))
                               batches)]
          (vec all-embeddings)))

      (embedding-dim [_]
        (or @dim
            ;; If we haven't embedded anything yet, do a probe
            (let [result (embed-batch client base-url model ["probe"] timeout)]
              (when (seq result)
                (reset! dim (count (first result)))
                @dim))))

      (provider-name [_] (str "ollama/" model)))))
