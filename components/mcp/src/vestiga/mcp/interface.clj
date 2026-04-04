(ns vestiga.mcp.interface
  (:require
    [vestiga.mcp.server :as server]
    [vestiga.mcp.tools :as tools]))

(defn start-server!
  [db & {:keys [project-root]}]
  (server/start-server! db :project-root project-root))

(defn list-tools
  []
  (tools/list-tools))

(defn call-tool
  [name arguments]
  (tools/call-tool name arguments))
