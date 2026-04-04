(ns vestiga.index.clj-kondo
  (:require
    [babashka.process :as p]
    [clojure.data.json :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [malli.core :as m]))

(def KondoAnalysis
  [:map
   [:namespace-definitions
    [:vector
     [:map
      [:name :keyword]
      [:filename :string]
      [:row :int]
      [:col :int]
      [:end-row {:optional true} :int]
      [:end-col {:optional true} :int]
      [:doc {:optional true} :string]]]]
   [:namespace-usages
    [:vector
     [:map
      [:from :keyword]
      [:to :keyword]]]]
   [:var-definitions
    [:vector
     [:map
      [:ns :keyword]
      [:name :symbol]
      [:filename :string]
      [:row :int]
      [:col :int]
      [:end-row {:optional true} :int]
      [:end-col {:optional true} :int]
      [:defined-by {:optional true} :keyword]
      [:arglist-strs {:optional true} [:vector :string]]
      [:doc {:optional true} :string]
      [:private {:optional true} :boolean]]]]
   [:var-usages
    [:vector
     [:map
      [:from :keyword]
      [:from-var {:optional true} :symbol]
      [:to :keyword]
      [:name :symbol]
      [:filename :string]
      [:row :int]
      [:col :int]]]]])

(defn run-analysis
  "Run clj-kondo on the given paths and return parsed analysis data.
   Returns {:analysis <map>} or {:error <string>}."
  [project-root paths]
  (let [lint-path  (str/join ":" paths)
        config-str (pr-str
                     {:output   {:format :json}
                      :analysis {:arglists              true
                                 :var-definitions       {:meta true}
                                 :var-usages            {:lang :clj}
                                 :namespace-definitions true
                                 :namespace-usages      true
                                 :keywords              true}})
        result     (p/shell
                     {:dir      project-root
                      :out      :string
                      :err      :string
                      :continue true}
                     "clj-kondo"
                     "--lint"
                     lint-path
                     "--config"
                     config-str
                     "--parallel")]
    ;; clj-kondo exits 2 for warnings, 3 for errors in linted code — analysis is still valid.
    ;; Only fail if clj-kondo itself crashed (no stdout) or couldn't run at all.
    (if (and
          (> (:exit result) 3)
          (str/blank? (:out result)))
      {:error (str "clj-kondo failed with exit code " (:exit result) ": " (:err result))}
      (try (let [parsed   (json/read-str (:out result) :key-fn keyword)
                 analysis (:analysis parsed)]
             (if analysis {:analysis analysis} {:error "No :analysis key in clj-kondo output"}))
           (catch Exception e {:error (str "Failed to parse clj-kondo output: " (.getMessage e))})))))

(defn var-def->symbol-kind
  "Map clj-kondo's :defined-by to our symbol kinds."
  [defined-by]
  (let [s (some-> defined-by
                  str)]
    (case s
      (":clojure.core/defn" "clojure.core/defn")           "defn"
      (":clojure.core/defn-" "clojure.core/defn-")         "defn-"
      (":clojure.core/def" "clojure.core/def")             "def"
      (":clojure.core/defonce" "clojure.core/defonce")     "defonce"
      (":clojure.core/defmacro" "clojure.core/defmacro")   "defmacro"
      (":clojure.core/defmulti" "clojure.core/defmulti")   "defmulti"
      (":clojure.core/defmethod" "clojure.core/defmethod") "defmethod"
      (":clojure.core/defprotocol" "clojure.core/defprotocol") "defprotocol"
      (":clojure.core/defrecord" "clojure.core/defrecord") "defrecord"
      (":clojure.core/deftype" "clojure.core/deftype")     "deftype"
      ;; Default: extract the last segment after /
      (when defined-by
        (let [n (name defined-by)]
          (if (str/includes? n "/") (last (str/split n #"/")) n))))))

(defn analysis->symbols
  "Transform clj-kondo var-definitions into our symbol records."
  [analysis]
  (->> (:var-definitions analysis)
       (mapv
         (fn [vd]
           {:qualified-name (str (name (:ns vd)) "/" (name (:name vd)))
            :namespace      (name (:ns vd))
            :symbol-name    (name (:name vd))
            :kind           (var-def->symbol-kind (:defined-by vd))
            :file-path      (:filename vd)
            :start-line     (:row vd)
            :end-line       (or (:end-row vd) (:row vd))
            :arity          (when (:arglist-strs vd)
                              (json/write-str (:arglist-strs vd)))
            :docstring      (:doc vd)
            :private?       (:private vd)}))))

(defn analysis->refs
  "Transform clj-kondo var-usages into reference records."
  [analysis]
  (->> (:var-usages analysis)
       (mapv
         (fn [vu]
           {:from-ns   (name (:from vu))
            :from-name (if (:from-var vu) (name (:from-var vu)) "")
            :to-ns     (name (:to vu))
            :to-name   (name (:name vu))
            :file-path (:filename vu)
            :row       (:row vu)
            :col       (:col vu)}))))

(defn analysis->ns-deps
  "Transform clj-kondo namespace-usages into ns dependency records."
  [analysis]
  (->> (:namespace-usages analysis)
       (mapv
         (fn [nu]
           {:from-ns (name (:from nu))
            :to-ns   (name (:to nu))}))
       (distinct)
       (vec)))
