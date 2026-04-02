(ns vestiga.index.git
  (:require
    [babashka.process :as proc]
    [clojure.string :as str]
    [clojure.tools.logging :as log]))

(defn- run-git
  "Run a git command and return stdout as string."
  [project-root & args]
  (let [result (apply
                 proc/shell
                 {:dir      project-root
                  :out      :string
                  :err      :string
                  :continue true}
                 "git"
                 args)]
    (if (zero? (:exit result)) (:out result) (do (log/warn "git command failed:" args (:err result)) nil))))

(defn git-head-sha
  "Return current HEAD SHA."
  [project-root]
  (some-> (run-git project-root "rev-parse" "HEAD")
          str/trim))

(defn git-changed-files
  "Return files changed since the given SHA.
   Used for incremental indexing."
  [project-root since-sha]
  (when-let [output (run-git project-root "diff" "--name-only" (str since-sha "..HEAD"))]
    (vec (remove str/blank? (str/split-lines output)))))

(defn- parse-numstat-line
  "Parse a --numstat line: 'added\tremoved\tpath'"
  [line]
  (let [parts (str/split line #"\t")]
    (when (= (count parts) 3)
      (let [added   (first parts)
            removed (second parts)
            path    (nth parts 2)]
        {:path          path
         :lines-added   (when (not= added "-")
                          (parse-long added))
         :lines-removed (when (not= removed "-")
                          (parse-long removed))}))))

(defn- parse-git-log
  "Parse git log output with --format and --numstat."
  [output]
  (when output
    (let [lines (str/split-lines output)]
      (loop [remaining lines
             commits   (transient [])
             current   nil]
        (if (empty? remaining)
          (persistent! (if current (conj! commits current) commits))
          (let [line (first remaining)]
            (cond
              ;; Commit header line: SHA|author|timestamp|message
              (str/starts-with? line "COMMIT:")
              (let [parts      (str/split (subs line 7) #"\|" 4)
                    new-commit {:sha       (nth parts 0)
                                :author    (nth parts 1)
                                :timestamp (parse-long (nth parts 2))
                                :message   (nth parts 3)
                                :files     []}]
                (recur (rest remaining) (if current (conj! commits current) commits) new-commit))

              ;; Numstat line
              (and
                current
                (not (str/blank? line)))
              (let [parsed (parse-numstat-line line)]
                (if parsed
                  (recur (rest remaining) commits (update current :files conj (assoc parsed :change-type "M")))
                  (recur (rest remaining) commits current)))

              ;; Blank line
              :else
              (recur (rest remaining) commits current))))))))

(defn git-log
  "Extract commit history from git log.
   Returns a vector of commit maps with :sha, :author, :timestamp, :message, :files."
  [project-root & {:keys [since-sha max-count paths]}]
  (let [format-str "COMMIT:%H|%an|%at|%s"
        args       (cond-> ["log" (str "--format=" format-str) "--numstat" "--diff-filter=ADMR"]
                     since-sha (conj (str since-sha "..HEAD"))
                     max-count (conj (str "-n" max-count))
                     paths     (into (cons "--" paths)))]
    (when-let [output (apply run-git project-root args)]
      (parse-git-log output))))

(defn git-diff-stat
  "Get changed files between two SHAs with change type."
  [project-root from-sha to-sha]
  (when-let [output (run-git project-root "diff" "--name-status" from-sha to-sha)]
    (->> (str/split-lines output)
         (remove str/blank?)
         (mapv
           (fn [line]
             (let [[status path] (str/split line #"\t" 2)]
               {:path        path
                :change-type status}))))))
