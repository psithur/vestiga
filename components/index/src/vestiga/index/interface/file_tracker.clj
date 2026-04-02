(ns vestiga.index.interface.file-tracker
  (:require
    [vestiga.index.file-tracker :as file-tracker]))

(defn file-hash
  [path]
  (file-tracker/file-hash path))

(defn find-source-files
  [project-root paths extensions]
  (file-tracker/find-source-files project-root paths extensions))

(defn files-needing-reindex
  [files db project-id]
  (file-tracker/files-needing-reindex files db project-id))
