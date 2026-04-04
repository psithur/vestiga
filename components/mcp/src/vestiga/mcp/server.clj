(ns vestiga.mcp.server
  (:require
    [clojure.data.json :as json]
    [clojure.tools.logging :as log]
    [vestiga.mcp.tools :as tools]
    [vestiga.mcp.transport :as transport]))

(def server-info
  {:name    "vestiga"
   :version "0.1.0"})

(def capabilities {:tools {:listChanged false}})

(defmulti handle-method :method)

(defmethod handle-method "initialize"
  [_request]
  {:protocolVersion "2024-11-05"
   :capabilities    capabilities
   :serverInfo      server-info})

(defmethod handle-method "tools/list" [_] {:tools (tools/list-tools)})

(defmethod handle-method "tools/call"
  [request]
  (let [{:keys [name arguments]} (:params request)]
    (tools/call-tool name arguments)))

(defmethod handle-method "notifications/initialized" [_] nil)

(defmethod handle-method :default
  [request]
  {:error {:code    -32601
           :message (str "Unknown method: " (:method request))}})

(defn start-server!
  "Start the MCP server, reading JSON-RPC from stdin, writing to stdout.
   Blocks until stdin is closed."
  [db & {:keys [project-root]}]
  (log/info "Starting MCP server...")
  (let [reader (transport/make-reader System/in)]
    (binding [tools/*db* db
              tools/*project-root* project-root]
      (loop []
        (when-let [request (transport/read-message reader)]
          (log/debug "Received request:" (:method request))
          (let [response (try (handle-method request)
                              (catch Exception e
                                (log/error e "Error handling method" (:method request))
                                {:error {:code    -32603
                                         :message (str "Internal error: " (.getMessage e))}}))]
            (when response
              (transport/write-message
                System/out
                (if (:error response)
                  {:jsonrpc "2.0"
                   :id      (:id request)
                   :error   (:error response)}
                  {:jsonrpc "2.0"
                   :id      (:id request)
                   :result  response}))))
          (recur))))))
