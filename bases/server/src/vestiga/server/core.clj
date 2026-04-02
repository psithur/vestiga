(ns vestiga.server.core
  (:require
    [clojure.tools.logging :as log]
    [vestiga.config.interface :as config]
    [vestiga.db.interface :as db]
    [vestiga.db.interface.schema :as schema]
    [vestiga.embed.interface :as ollama]
    [vestiga.embed.interface.process :as embed-proc]
    [vestiga.index.interface :as coordinator]
    [vestiga.mcp.interface :as mcp]
    [vestiga.search.interface :as search])
  (:gen-class))

(defn- which
  "Check if a binary is on PATH. Returns true if found."
  [binary]
  (try (zero? (.waitFor (.exec (Runtime/getRuntime) ^"[Ljava.lang.String;" (into-array String ["which" binary]))))
       (catch Exception _ false)))

(defn check-prerequisites!
  "Verify external binaries are available. Returns map of availability."
  []
  {:clj-kondo (which "clj-kondo")
   :git       (which "git")
   :ollama    (which "ollama")})

(defn -main
  [& args]
  (let [cmd (first args)]
    (case cmd
      ("serve" nil) (let [;; Suppress stderr logging — MCP clients interpret it as errors
                          _ (System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "off")
                          prereqs (check-prerequisites!)
                          _ (when-not (:clj-kondo prereqs) (log/error "clj-kondo not found on PATH") (System/exit 1))
                          config  (config/load-config)
                          ollama  (when (:ollama prereqs)
                                    (try (embed-proc/ensure-ollama! :model (:embed-model config))
                                         (catch Exception e
                                           (log/warn "Ollama not available, semantic search disabled:" (.getMessage e))
                                           nil)))
                          db-path (or (second args) ".vestiga/db.sqlite")
                          db-conn (db/open-db db-path)]
                      (try (schema/ensure-schema! db-conn)
                           (mcp/start-server! db-conn)
                           (finally
                             (db/close-db db-conn)
                             (when ollama
                               (embed-proc/stop-ollama! ollama)))))

      "index"       (let [project-root (or (second args) ".")
                          config       (config/load-config)
                          ollama       (try (embed-proc/ensure-ollama! :model (:embed-model config))
                                            (catch Exception e (log/warn "Ollama not available:" (.getMessage e)) nil))
                          db-path      (str project-root "/.vestiga/db.sqlite")
                          db-conn      (db/open-db db-path)]
                      (try (schema/ensure-schema! db-conn)
                           (coordinator/index-project! db-conn project-root :config config)
                           (finally
                             (db/close-db db-conn)
                             (when ollama
                               (embed-proc/stop-ollama! ollama)))))

      "search"      (let [query   (second args)
                          db-path (or (nth args 2 nil) ".vestiga/db.sqlite")
                          db-conn (db/open-db db-path)]
                      (try (let [results (search/search db-conn query)]
                             (doseq [r results]
                               (println
                                 (format
                                   "%s:%d  %s  [%s]"
                                   (or (:file_path r) (:file-path r) "?")
                                   (or (:start_line r) (:start-line r) 0)
                                   (or (:qualified_name r) (:qualified-name r) "?")
                                   (or (:kind r) "?")))
                               (println (:content r))
                               (println)))
                           (finally (db/close-db db-conn))))

      (do (println "Usage: vestiga [serve|index|search] [args...]") (System/exit 1)))))
