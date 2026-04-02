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
   Returns parsed JSON map, or nil on EOF."
  [^BufferedReader reader]
  (try (let [header-line (.readLine reader)]
         (when header-line
           (let [content-length (when (.startsWith header-line "Content-Length:")
                                  (parse-long (.trim (subs header-line 15))))]
             (when content-length
               ;; Read the empty line after headers
               (.readLine reader)
               ;; Read exactly content-length characters
               (let [buf (char-array content-length)]
                 (loop [offset 0]
                   (when (< offset content-length)
                     (let [n (.read reader buf offset (- content-length offset))]
                       (when (pos? n)
                         (recur (+ offset n))))))
                 (json/read-str (String. buf) :key-fn keyword))))))
       (catch Exception _ nil)))

(defn write-message
  "Write one JSON-RPC message to an OutputStream."
  [^OutputStream out msg]
  (let [body         (json/write-str msg)
        ^bytes bytes (.getBytes ^String body "UTF-8")]
    (.write out (.getBytes (str "Content-Length: " (alength bytes) "\r\n\r\n") "UTF-8"))
    (.write out bytes)
    (.flush out)))
