(ns vestiga.mcp.interface.transport
  (:require
    [vestiga.mcp.transport :as transport]))

(defn read-message
  [in]
  (transport/read-message in))

(defn write-message
  [out msg]
  (transport/write-message out msg))
