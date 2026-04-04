(ns vestiga.embed.process
  (:require
    [babashka.process :as proc]
    [clojure.data.json :as json]
    [clojure.tools.logging :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn- http-get
  "Simple HTTP GET, returns response body string or nil on failure."
  [url]
  (try (let [client   (-> (HttpClient/newBuilder)
                          (.connectTimeout (Duration/ofSeconds 5))
                          (.build))
             request  (-> (HttpRequest/newBuilder)
                          (.uri (URI/create url))
                          (.timeout (Duration/ofSeconds 5))
                          (.GET)
                          (.build))
             response (.send client request (HttpResponse$BodyHandlers/ofString))]
         (when (= 200 (.statusCode response))
           (.body response)))
       (catch Exception _ nil)))

(defn ollama-running?
  "Check if Ollama is already running by hitting /api/tags."
  [base-url]
  (boolean (http-get (str base-url "/api/tags"))))

(defn- model-available?
  "Check if a specific model is available in Ollama."
  [base-url model]
  (when-let [body (http-get (str base-url "/api/tags"))]
    (let [parsed (json/read-str body :key-fn keyword)
          models (mapv :name (:models parsed))]
      (some #(or (= % model) (= % (str model ":latest"))) models))))

(defn- pull-model!
  "Pull a model from Ollama."
  [base-url model]
  (log/info "Pulling Ollama model:" model)
  (try (let [client   (-> (HttpClient/newBuilder)
                          (.connectTimeout (Duration/ofSeconds 300))
                          (.build))
             body     (json/write-str
                        {:name   model
                         :stream false})
             request  (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str base-url "/api/pull")))
                          (.header "Content-Type" "application/json")
                          (.timeout (Duration/ofSeconds 600))
                          (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                          (.build))
             response (.send client request (HttpResponse$BodyHandlers/ofString))]
         (if (= 200 (.statusCode response))
           (do (log/info "Model" model "pulled successfully") true)
           (do (log/error "Failed to pull model:" (.statusCode response) (.body response)) false)))
       (catch Exception e (log/error "Failed to pull model:" (.getMessage e)) false)))

(defn ensure-ollama!
  "Ensure Ollama is running and the required model is available.
   Returns {:process <Process or nil> :model <model-name> :base-url <url>}."
  [&
   {:keys [model base-url max-retries]
    :or   {model       "nomic-embed-text"
           base-url    "http://localhost:11434"
           max-retries 30}}]
  (let [already-running? (ollama-running? base-url)
        process (when-not already-running?
                  (log/info "Starting Ollama serve...")
                  (proc/process
                    ["ollama" "serve"]
                    {:out      :write
                     :err      :write
                     :shutdown :destroy}))]
    ;; Wait for Ollama to be ready
    (when-not already-running?
      (loop [retries 0]
        (when (< retries max-retries)
          (if (ollama-running? base-url) (log/info "Ollama is ready") (do (Thread/sleep 1000) (recur (inc retries)))))))

    ;; Check/pull model
    (when (ollama-running? base-url)
      (when-not (model-available? base-url model) (pull-model! base-url model)))

    {:process  (when process
                 (:proc process))
     :model    model
     :base-url base-url}))

(defn stop-ollama!
  "Stop an Ollama process we started (not one that was pre-existing).
   No-op if process is nil."
  [{:keys [process]}]
  (when process
    (log/info "Stopping Ollama process")
    (.destroy ^Process process)))
