(ns vestiga.conversation.discovery
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import [java.io File]))

(defn claude-code-dir
  "Return the path to ~/.claude/projects/."
  []
  (str (System/getProperty "user.home") "/.claude/projects"))

(defn encode-project-path
  "Encode a project root path to the directory name Claude Code uses.
   /home/user/projects/vestiga → -home-user-projects-vestiga"
  [path]
  (str/replace path "/" "-"))

(defn decode-project-path
  "Decode a Claude Code project directory name back to the original path.
   -home-user-projects-vestiga → /home/user/projects/vestiga"
  [encoded]
  (let [;; The first char is always '-' representing the leading /
        without-leading (subs encoded 1)]
    (str "/" (str/replace without-leading "-" "/"))))

(defn find-sessions
  "Find all JSONL session files for a given project root.
   Returns a seq of maps: {:file <File>, :session-id <string>}."
  [project-root]
  (let [abs-path (.getCanonicalPath (io/file project-root))
        encoded  (encode-project-path abs-path)
        proj-dir (io/file (claude-code-dir) encoded)]
    (when (.isDirectory proj-dir)
      (->> (.listFiles proj-dir)
           (filter #(str/ends-with? (.getName ^File %) ".jsonl"))
           (map
             (fn [^File f]
               {:file       f
                :session-id (str/replace (.getName f) ".jsonl" "")}))
           (sort-by #(.lastModified ^File (:file %)) >)))))

(defn find-all-sessions
  "Find all Claude Code projects and their session files.
   Returns a seq of maps: {:project-path <string>, :encoded <string>, :sessions [...]}"
  []
  (let [base-dir (io/file (claude-code-dir))]
    (when (.isDirectory base-dir)
      (->> (.listFiles base-dir)
           (filter #(.isDirectory ^File %))
           (keep
             (fn [^File dir]
               (let [encoded  (.getName dir)
                     sessions (->> (.listFiles dir)
                                   (filter #(str/ends-with? (.getName ^File %) ".jsonl"))
                                   (map
                                     (fn [^File f]
                                       {:file       f
                                        :session-id (str/replace (.getName f) ".jsonl" "")}))
                                   (sort-by #(.lastModified ^File (:file %)) >))]
                 (when (seq sessions)
                   {:project-path (decode-project-path encoded)
                    :encoded      encoded
                    :sessions     (vec sessions)}))))
           (sort-by :project-path)))))
