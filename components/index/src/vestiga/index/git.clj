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

(defn- parse-git-log-numstat
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

;; -- Patch fetching (targeted, not bulk) ------------------------------------

(defn- extract-file-path
  "Extract file path from a 'diff --git a/path b/path' line."
  [diff-header]
  (when-let [m (re-find #"diff --git a/(.+?) b/(.+)" diff-header)]
    (nth m 2)))

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

(defn- split-into-file-diffs
  "Split a patch output into per-file diffs.
   Returns a vector of {:path :patch :change-type}."
  [patch-text]
  (when (and
          patch-text
          (not (str/blank? patch-text)))
    (let [parts (str/split patch-text #"(?=diff --git )")]
      (->> parts
           (remove str/blank?)
           (mapv
             (fn [file-diff]
               (let [path (extract-file-path (first (str/split-lines file-diff)))]
                 (when path
                   {:path        path
                    :patch       (str/trim file-diff)
                    :change-type (extract-change-type file-diff)}))))
           (filterv some?)))))

(defn get-commit-patches
  "Fetch per-file patches for a single commit by SHA.
   Returns a vector of {:path :patch :change-type}."
  [project-root sha]
  (when-let [output (run-git project-root "show" "--format=" "-p" sha)]
    (split-into-file-diffs output)))

(defn git-log
  "Extract commit history from git log with --numstat.
   Returns a vector of commit maps with :sha, :author, :timestamp, :message, :files.
   Each file has :path, :change-type, :lines-added, :lines-removed.

   Use get-commit-patches to fetch full diffs for specific commits."
  [project-root & {:keys [since-sha max-count paths]}]
  (let [format-str "COMMIT:%H|%an|%at|%s"
        args       (cond-> ["log" (str "--format=" format-str) "--numstat" "--diff-filter=ADMR"]
                     since-sha (conj (str since-sha "..HEAD"))
                     max-count (conj (str "-n" max-count))
                     paths     (into (cons "--" paths)))]
    (when-let [output (apply run-git project-root args)]
      (parse-git-log-numstat output))))

(defn git-log-with-patches
  "Extract commit history with per-file patches.
   Fetches patches individually per commit to avoid OOM on large repos.
   Use for small commit ranges (e.g. incremental indexing)."
  [project-root & {:keys [since-sha max-count]}]
  (let [commits (git-log project-root :since-sha since-sha :max-count max-count)]
    (when commits
      (mapv
        (fn [commit]
          (let [patches   (get-commit-patches project-root (:sha commit))
                patch-map (into {} (map (juxt :path identity) patches))]
            (update
              commit
              :files
              (fn [files]
                (mapv
                  (fn [f]
                    (if-let [p (get patch-map (:path f))]
                      (assoc f :patch (:patch p) :change-type (:change-type p))
                      f))
                  files)))))
        commits))))

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
