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

(defn- count-diff-lines
  "Count added/removed lines from a unified diff patch string."
  [patch]
  (let [lines (str/split-lines patch)]
    {:lines-added   (count
                      (filter
                        #(and
                           (str/starts-with? % "+")
                           (not (str/starts-with? % "+++"))
                           (not (str/starts-with? % "+++")))
                        lines))
     :lines-removed (count
                      (filter
                        #(and
                           (str/starts-with? % "-")
                           (not (str/starts-with? % "---")))
                        lines))}))

(defn- extract-change-type
  "Determine change type from diff header lines."
  [patch]
  (cond
    (str/includes? patch "new file mode")
    "A"
    (str/includes? patch "deleted file mode")
    "D"
    (str/includes? patch "rename from")
    "R"
    :else
    "M"))

(defn- extract-file-path
  "Extract file path from a 'diff --git a/path b/path' line."
  [diff-header]
  (when-let [m (re-find #"diff --git a/(.+?) b/(.+)" diff-header)]
    (nth m 2)))

(defn- split-into-file-diffs
  "Split a commit's patch output into per-file diffs.
   Returns a vector of {:path :patch :change-type :lines-added :lines-removed}."
  [patch-text]
  (when (and
          patch-text
          (not (str/blank? patch-text)))
    (let [;; Split on "diff --git" boundaries, keeping the delimiter
          parts (str/split patch-text #"(?=diff --git )")]
      (->> parts
           (remove str/blank?)
           (mapv
             (fn [file-diff]
               (let [path   (extract-file-path (first (str/split-lines file-diff)))
                     counts (count-diff-lines file-diff)]
                 (when path
                   (merge
                     {:path        path
                      :patch       (str/trim file-diff)
                      :change-type (extract-change-type file-diff)}
                     counts)))))
           (filterv some?)))))

(defn- parse-git-log-with-patches
  "Parse git log output with --format and -p (patch output).
   Splits output by COMMIT: markers, then splits each commit's
   diff section into per-file patches."
  [output]
  (when output
    (let [;; Split on COMMIT: markers
          parts (str/split output #"(?=COMMIT:)")]
      (->> parts
           (remove str/blank?)
           (mapv
             (fn [part]
               (let [lines      (str/split-lines part)
                     header     (first lines)
                     hparts     (str/split (subs header 7) #"\|" 4)
                     rest-text  (str/join "\n" (rest lines))
                     file-diffs (split-into-file-diffs rest-text)]
                 {:sha       (nth hparts 0)
                  :author    (nth hparts 1)
                  :timestamp (parse-long (nth hparts 2))
                  :message   (nth hparts 3)
                  :files     (or file-diffs [])})))))))

(defn git-log
  "Extract commit history from git log with per-file diffs.
   Returns a vector of commit maps with :sha, :author, :timestamp, :message, :files.
   Each file has :path, :change-type, :lines-added, :lines-removed, :patch."
  [project-root & {:keys [since-sha max-count paths]}]
  (let [format-str "COMMIT:%H|%an|%at|%s"
        args       (cond-> ["log" (str "--format=" format-str) "-p" "--diff-filter=ADMR"]
                     since-sha (conj (str since-sha "..HEAD"))
                     max-count (conj (str "-n" max-count))
                     paths     (into (cons "--" paths)))]
    (when-let [output (apply run-git project-root args)]
      (parse-git-log-with-patches output))))

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
