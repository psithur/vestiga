(ns vestiga.mcp.transport
  (:require
    [clojure.data.json :as json])
  (:import [java.io InputStream OutputStream BufferedReader InputStreamReader]))

(defn read-message
  "Read one JSON-RPC message from an InputStream.
   Returns parsed JSON map, or nil on EOF."
  [^InputStream in]
  (let [reader (BufferedReader. (InputStreamReader. in "UTF-8"))]
    (try (let [header-line (.readLine reader)]
           (when header-line
             (let [content-length (when (and
                                          header-line
                                          (.startsWith header-line "Content-Length:"))
                                    (parse-long (.trim (subs header-line 15))))]
               (when content-length
                 ;; Read the empty line after headers
                 (.readLine reader)
                 ;; Read exactly content-length characters
                 (let [buf (char-array content-length)]
                   (loop [offset 0]
                     (when (< offset content-length)
                       (let [read (.read reader buf offset (- content-length offset))]
                         (when (pos? read)
                           (recur (+ offset read))))))
                   (json/read-str (String. buf) :key-fn keyword))))))
         (catch Exception _ nil))))

(defn write-message
  "Write one JSON-RPC message to an OutputStream."
  [^OutputStream out msg]
  (let [body  (json/write-str msg)
        bytes (.getBytes body "UTF-8")]
    (.write out (.getBytes (str "Content-Length: " (count bytes) "\r\n\r\n") "UTF-8"))
    (.write out bytes)
    (.flush out)))
