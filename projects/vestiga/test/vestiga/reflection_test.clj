(ns vestiga.reflection-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def vestiga-namespaces
  "All vestiga source namespaces to check for reflection."
  '[vestiga.config.core vestiga.config.interface vestiga.db.connection vestiga.db.schema vestiga.db.ops
    vestiga.db.search vestiga.db.interface vestiga.db.interface.schema vestiga.db.interface.ops
    vestiga.db.interface.search vestiga.index.chunker vestiga.index.clj-kondo vestiga.index.coordinator
    vestiga.index.file-tracker vestiga.index.git vestiga.index.interface vestiga.index.interface.chunker
    vestiga.index.interface.kondo vestiga.index.interface.git vestiga.index.interface.file-tracker
    vestiga.embed.provider vestiga.embed.ollama vestiga.embed.process vestiga.embed.interface
    vestiga.embed.interface.process vestiga.search.engine vestiga.search.ranker vestiga.search.interface
    vestiga.search.interface.ranker vestiga.mcp.server vestiga.mcp.tools vestiga.mcp.transport vestiga.mcp.interface
    vestiga.mcp.interface.transport vestiga.server.core])

(deftest test-no-reflection-warnings
  (testing "all vestiga namespaces compile without reflection warnings"
    (let [warnings (java.io.StringWriter.)]
      (binding [*warn-on-reflection* true
                *err* (java.io.PrintWriter. warnings)]
        (doseq [ns-sym vestiga-namespaces]
          (try (require ns-sym :reload)
               (catch Exception e
                 ;; Log but don't fail on require errors — other tests cover that
                 (.write warnings (str "Failed to require " ns-sym ": " (.getMessage e) "\n"))))))
      (let [warning-text     (str warnings)
            reflection-lines (->> (str/split-lines warning-text)
                                  (filter #(str/includes? % "Reflection warning"))
                                  (vec))]
        (is
          (empty? reflection-lines)
          (str "Found " (count reflection-lines) " reflection warning(s):\n" (str/join "\n" reflection-lines)))))))
