(ns vestiga.mcp.transport
  (:require
    [clojure.data.json :as json])
  (:import [java.io InputStream OutputStream BufferedReader InputStreamReader]))

(defn make-reader
  "Create a BufferedReader from an InputStream. Must be created once and reused."
  ^BufferedReader [^InputStream in]
  (BufferedReader. (InputStreamReader. in "UTF-8")))

(defn read-message
  "Read one JSON-RPC message from a BufferedReader.
   Supports both newline-delimited JSON (MCP stdio) and Content-Length framing."
  [^BufferedReader reader]
  (try
    (loop []
      (let [line (.readLine reader)]
        (when line
          (let [trimmed (.trim line)]
            (cond
              ;; Empty line — skip
              (= trimmed "")
              (recur)

              ;; Content-Length header — read framed message
              (.startsWith trimmed "Content-Length:")
              (let [content-length (parse-long (.trim (subs trimmed 15)))]
                ;; Read until empty line (end of headers)
                (loop []
                  (let [h (.readLine reader)]
                    (when (and
                            h
                            (not= (.trim h) ""))
                      (recur))))
                ;; Read exactly content-length characters
                (let [buf (char-array content-length)]
                  (loop [offset 0]
                    (when (< offset content-length)
                      (let [n (.read reader buf offset (- content-length offset))]
                        (when (pos? n)
                          (recur (+ offset n))))))
                  (json/read-str (String. buf) :key-fn keyword)))

              ;; Bare JSON line (newline-delimited)
              (.startsWith trimmed "{")
              (json/read-str trimmed :key-fn keyword)

              ;; Unknown line — skip
              :else
              (recur))))))
    (catch Exception _ nil)))

(defn write-message
  "Write one JSON-RPC message to an OutputStream as newline-delimited JSON."
  [^OutputStream out msg]
  (let [^String body (json/write-str msg)
        ^bytes bytes (.getBytes body "UTF-8")]
    (.write out bytes)
    (.write out (.getBytes "\n" "UTF-8"))
    (.flush out)))
