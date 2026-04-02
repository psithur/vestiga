(ns vestiga.mcp.interface.transport
  (:require
    [vestiga.mcp.transport :as transport]))

(defn make-reader
  [in]
  (transport/make-reader in))

(defn read-message
  [reader]
  (transport/read-message reader))

(defn write-message
  [out msg]
  (transport/write-message out msg))
