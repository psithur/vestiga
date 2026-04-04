(ns build
  (:require
    [clojure.tools.build.api :as b]))

(def lib 'io.github.psithur/vestiga)
(def version "0.1.0")
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(def src-dirs
  ["components/config/src"
   "components/db/src"
   "components/db/resources"
   "components/index/src"
   "components/embed/src"
   "components/search/src"
   "components/mcp/src"
   "components/ast/src"
   "bases/server/src"
   "bases/server/resources"])

(def basis
  (delay
    (b/create-basis
      {:project "deps.edn"
       :aliases [:dev :build]})))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn uber
  [_]
  (clean nil)
  (b/copy-dir
    {:src-dirs   src-dirs
     :target-dir class-dir})
  (b/compile-clj
    {:basis      @basis
     :ns-compile '[vestiga.server.core]
     :class-dir  class-dir})
  (b/uber
    {:class-dir class-dir
     :uber-file uber-file
     :basis     @basis
     :main      'vestiga.server.core}))

(defn native
  [_]
  (uber nil)
  (b/process
    {:command-args ["native-image"
                    "-jar" uber-file
                    "-o" "target/vestiga"
                    "-H:+ReportExceptionStackTraces"
                    "--no-fallback"
                    "--enable-url-protocols=http"
                    (str "-Dorg.sqlite.lib.exportPath=target")
                    "--initialize-at-build-time"
                    (str "-H:ResourceConfigurationFiles=bases/server/resources/native-image/resource-config.json")
                    (str "-H:ReflectionConfigurationFiles=bases/server/resources/native-image/reflect-config.json")]}))
