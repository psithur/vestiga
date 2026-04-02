(ns vestiga.index.interface.git
  (:require
    [vestiga.index.git :as git]))

(defn git-head-sha
  [project-root]
  (git/git-head-sha project-root))

(defn git-log
  [project-root & opts]
  (apply git/git-log project-root opts))

(defn git-changed-files
  [project-root since-sha]
  (git/git-changed-files project-root since-sha))

(defn git-diff-stat
  [project-root from-sha to-sha]
  (git/git-diff-stat project-root from-sha to-sha))
