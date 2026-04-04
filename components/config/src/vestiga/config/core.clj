(ns vestiga.config.core
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]))

(def defaults
  {:embed-model        "nomic-embed-text"
   :embed-dim          768
   :ollama-base-url    "http://localhost:11434"
   :ollama-timeout     120
   :index-paths        nil ;; nil means auto-detect
   :file-extensions    #{".clj" ".cljs" ".cljc" ".bb"}
   :max-chunk-lines    200
   :git-max-commits    10000
   :search-limit       20
   :vec-extension-path nil})

(defn load-config
  "Load config from .vestiga/config.edn if it exists,
   merged over defaults."
  ([] (load-config ".vestiga/config.edn"))
  ([path]
   (let [file (io/file path)]
     (if (.exists file) (merge defaults (edn/read-string (slurp file))) defaults))))

(defn- polylith-project?
  "Check if a project root contains workspace.edn (Polylith marker)."
  [^java.io.File root]
  (.isFile (io/file root "workspace.edn")))

(defn- discover-polylith-paths
  "Discover source paths in a Polylith workspace.
   Returns paths like components/foo/src, bases/bar/src, development/src."
  [^java.io.File root]
  (let [dirs     (for [parent ["components" "bases" "development"]
                       :let   [^java.io.File pdir (io/file root parent)]
                       :when  (.isDirectory pdir)
                       ^java.io.File child (.listFiles pdir)
                       :when  (.isDirectory child)
                       sub    ["src" "test"]
                       :let   [^java.io.File sub-dir (io/file child sub)]
                       :when  (.isDirectory sub-dir)]
                   (str parent "/" (.getName child) "/" sub))
        ;; development/src is a single level (no child dirs to iterate)
        dev-dirs (for [sub   ["src" "test"]
                       :let  [^java.io.File d (io/file root "development" sub)]
                       :when (.isDirectory d)]
                   (str "development/" sub))]
    (vec (distinct (concat dirs dev-dirs)))))

(defn discover-index-paths
  "Discover source paths for a project.
   Priority:
   1. Explicit config paths (if non-nil)
   2. Polylith layout (if workspace.edn exists)
   3. Standard [\"src\" \"test\"] filtered to existing dirs"
  [project-root & {:keys [config-paths]}]
  (let [root (io/file project-root)]
    (cond
      ;; Explicit config takes priority
      (seq config-paths)
      (vec config-paths)

      ;; Polylith project — discover from workspace layout
      (polylith-project? root)
      (let [poly-paths (discover-polylith-paths root)]
        (if (seq poly-paths) poly-paths ["src" "test"]))

      ;; Standard project — use existing dirs from defaults
      :else
      (let [existing (filterv #(.isDirectory (io/file root ^String %)) ["src" "test"])]
        (if (seq existing) existing ["src" "test"])))))
