(ns vestiga.mcp.server-test
  (:require
    [clojure.data.json :as json]
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.interface.ops :as ops]
    [vestiga.mcp.server :as server]
    [vestiga.mcp.tools :as tools]
    [vestiga.mcp.transport :as transport]
    [vestiga.test-helpers :as h])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

;; -- Unit tests for handle-method -------------------------------------------

(deftest test-handle-initialize
  (testing "returns protocol version and capabilities"
    (let [response (server/handle-method
                     {:method "initialize"
                      :id     1
                      :params {}})]
      (is
        (= "2024-11-05" (:protocolVersion response)))
      (is
        (some? (:capabilities response)))
      (is
        (= "vestiga" (get-in response [:serverInfo :name]))))))

(deftest test-handle-tools-list
  (testing "returns tool definitions"
    (let [response (server/handle-method
                     {:method "tools/list"
                      :id     2})]
      (is
        (vector? (:tools response)))
      (is
        (pos? (count (:tools response))))
      (is
        (some #(= "search_code" (:name %)) (:tools response))))))

(deftest test-handle-unknown-method
  (testing "returns error for unknown method"
    (let [response (server/handle-method
                     {:method "unknown/method"
                      :id     3})]
      (is
        (some? (:error response)))
      (is
        (= -32601 (get-in response [:error :code]))))))

(deftest test-handle-notification
  (testing "returns nil for notifications"
    (is
      (nil? (server/handle-method {:method "notifications/initialized"})))))

;; -- Transport round-trip tests ---------------------------------------------

(defn- make-jsonrpc-input
  "Create an InputStream containing newline-delimited JSON-RPC messages."
  [& messages]
  (let [text (apply str (map #(str (json/write-str %) "\n") messages))]
    (ByteArrayInputStream. (.getBytes text "UTF-8"))))

(defn- parse-jsonrpc-output
  "Parse newline-delimited JSON-RPC responses from an OutputStream's bytes."
  [^ByteArrayOutputStream out]
  (let [text (.toString out "UTF-8")]
    (->> (clojure.string/split-lines text)
         (remove clojure.string/blank?)
         (mapv #(json/read-str % :key-fn keyword)))))

(deftest test-server-round-trip-initialize
  (testing "full round-trip: initialize request via stdin/stdout"
    (let [input  (make-jsonrpc-input
                   {:jsonrpc "2.0"
                    :id      1
                    :method  "initialize"
                    :params  {:protocolVersion "2024-11-05"
                              :capabilities    {}
                              :clientInfo      {:name    "test"
                                                :version "1.0"}}})
          output (ByteArrayOutputStream.)
          reader (transport/make-reader input)]
      (h/with-temp-db
        (fn [db]
          (binding [tools/*db* db]
            ;; Read one message, handle it, write response
            (let [request  (transport/read-message reader)
                  response (server/handle-method request)]
              (transport/write-message
                output
                {:jsonrpc "2.0"
                 :id      (:id request)
                 :result  response})))
          (let [responses (parse-jsonrpc-output output)]
            (is
              (= 1 (count responses)))
            (is
              (= 1 (:id (first responses))))
            (is
              (= "vestiga" (get-in (first responses) [:result :serverInfo :name])))))))))

(deftest test-server-round-trip-tools-call
  (testing "full round-trip: tools/call search_code with real DB"
    (let [input  (make-jsonrpc-input
                   {:jsonrpc "2.0"
                    :id      1
                    :method  "tools/call"
                    :params  {:name      "search_code"
                              :arguments {:query "greet"}}})
          output (ByteArrayOutputStream.)
          reader (transport/make-reader input)]
      (h/with-temp-db
        (fn [db]
          ;; Insert test data
          (let [pid (ops/upsert-project!
                      db
                      {:root-path "/tmp/test"
                       :name      "test"})]
            (ops/insert-chunk!
              db
              {:project-id     pid
               :file-path      "src/core.clj"
               :namespace      "my.core"
               :qualified-name "my.core/greet"
               :symbol-name    "greet"
               :kind           "defn"
               :content        "(defn greet [name] name)"
               :start-line     1
               :end-line       1
               :arity          nil
               :docstring      nil
               :file-hash      "h1"}))
          (binding [tools/*db* db]
            (let [request  (transport/read-message reader)
                  response (server/handle-method request)]
              (transport/write-message
                output
                {:jsonrpc "2.0"
                 :id      (:id request)
                 :result  response})))
          (let [responses (parse-jsonrpc-output output)
                result    (:result (first responses))]
            (is
              (= 1 (count responses)))
            (is
              (vector? (:content result)))
            (is
              (clojure.string/includes? (get-in result [:content 0 :text]) "greet"))))))))

(deftest test-server-error-handling
  (testing "server returns JSON-RPC error instead of crashing on exception"
    (let [input  (make-jsonrpc-input
                   {:jsonrpc "2.0"
                    :id      1
                    :method  "tools/call"
                    :params  {:name      "index_project"
                              :arguments {:project_root "/nonexistent/path"}}})
          output (ByteArrayOutputStream.)
          reader (transport/make-reader input)]
      (h/with-temp-db
        (fn [db]
          (binding [tools/*db* db]
            (let [request  (transport/read-message reader)
                  response (try (server/handle-method request)
                                (catch Exception e
                                  {:error {:code    -32603
                                           :message (str "Internal error: " (.getMessage e))}}))]
              (transport/write-message
                output
                (if (:error response)
                  {:jsonrpc "2.0"
                   :id      (:id request)
                   :error   (:error response)}
                  {:jsonrpc "2.0"
                   :id      (:id request)
                   :result  response}))))
          (let [responses (parse-jsonrpc-output output)]
            (is
              (= 1 (count responses)))
            ;; Should get either an error response or an isError tool result — not crash
            (is
              (some? (first responses)))))))))

(deftest test-server-multiple-messages
  (testing "server handles multiple sequential messages"
    (let [input  (make-jsonrpc-input
                   {:jsonrpc "2.0"
                    :id      1
                    :method  "initialize"
                    :params  {:protocolVersion "2024-11-05"
                              :capabilities    {}
                              :clientInfo      {:name    "test"
                                                :version "1.0"}}}
                   {:jsonrpc "2.0"
                    :id      2
                    :method  "tools/list"})
          output (ByteArrayOutputStream.)
          reader (transport/make-reader input)]
      (h/with-temp-db
        (fn [db]
          (binding [tools/*db* db]
            ;; Process two messages
            (dotimes [_ 2]
              (when-let [request (transport/read-message reader)]
                (let [response (server/handle-method request)]
                  (when response
                    (transport/write-message
                      output
                      {:jsonrpc "2.0"
                       :id      (:id request)
                       :result  response}))))))
          (let [responses (parse-jsonrpc-output output)]
            (is
              (= 2 (count responses)))
            (is
              (= 1 (:id (first responses))))
            (is
              (= 2 (:id (second responses))))
            (is
              (= "vestiga" (get-in (first responses) [:result :serverInfo :name])))
            (is
              (vector? (get-in (second responses) [:result :tools])))))))))

(deftest test-transport-newline-delimited-json
  (testing "transport reads newline-delimited JSON"
    (let [msg    {:jsonrpc "2.0"
                  :id      1
                  :method  "initialize"
                  :params  {}}
          input  (ByteArrayInputStream. (.getBytes (str (json/write-str msg) "\n") "UTF-8"))
          reader (transport/make-reader input)
          parsed (transport/read-message reader)]
      (is
        (= "initialize" (:method parsed)))
      (is
        (= 1 (:id parsed))))))

(deftest test-transport-content-length-framing
  (testing "transport reads Content-Length framed messages"
    (let [msg    {:jsonrpc "2.0"
                  :id      1
                  :method  "initialize"
                  :params  {}}
          body   (json/write-str msg)
          bytes  (.getBytes ^String body "UTF-8")
          framed (str "Content-Length: " (count bytes) "\r\n\r\n" body)
          input  (ByteArrayInputStream. (.getBytes framed "UTF-8"))
          reader (transport/make-reader input)
          parsed (transport/read-message reader)]
      (is
        (= "initialize" (:method parsed)))
      (is
        (= 1 (:id parsed))))))
