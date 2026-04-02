(ns vestiga.config.core
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]))

(def defaults
  {:embed-model        "nomic-embed-text"
   :embed-dim          768
   :ollama-base-url    "http://localhost:11434"
   :ollama-timeout     120
   :index-paths        ["src" "test"]
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
