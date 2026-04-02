(ns vestiga.index.file-tracker
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.io File]))

(defn file-hash
  "Compute SHA-256 hash of a file's contents."
  [^String path]
  (let [file (io/file path)]
    (when (.exists file)
      (let [digest (MessageDigest/getInstance "SHA-256")
            bytes  (.digest digest (.getBytes (slurp file) "UTF-8"))]
        (apply str (map #(format "%02x" %) bytes))))))

(defn find-source-files
  "Find all source files under the given paths with matching extensions."
  [project-root paths extensions]
  (let [root (io/file project-root)]
    (->> paths
         (mapcat (fn [path]
                   (let [dir (io/file root path)]
                     (when (.isDirectory dir)
                       (file-seq dir)))))
         (filter
           (fn [^File f]
             (and
               (.isFile f)
               (some #(str/ends-with? (.getName f) %) extensions))))
         (mapv
           (fn [^File f]
             (let [abs-path  (.getAbsolutePath f)
                   root-path (.getAbsolutePath root)]
               {:absolute-path abs-path
                :relative-path (subs abs-path (inc (count root-path)))}))))))

(defn files-needing-reindex
  "Given source files and the db, determine which files need reindexing.
   Returns the subset of files whose hash differs from what's stored."
  [files db project-id]
  (let [get-hash (requiring-resolve 'vestiga.db.interface.ops/get-file-hash)]
    (filterv
      (fn [{:keys [absolute-path relative-path]}]
        (let [current-hash (file-hash absolute-path)
              stored-hash  (get-hash db project-id relative-path)]
          (not= current-hash stored-hash)))
      files)))
