(ns vestiga.index.clj-kondo-test
  (:require
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [vestiga.index.clj-kondo :as kondo]))

(deftest test-var-def->symbol-kind
  (testing "maps known defined-by values"
    (is
      (= "defn" (kondo/var-def->symbol-kind :clojure.core/defn)))
    (is
      (= "defprotocol" (kondo/var-def->symbol-kind :clojure.core/defprotocol)))
    (is
      (= "defrecord" (kondo/var-def->symbol-kind :clojure.core/defrecord)))
    (is
      (= "defmacro" (kondo/var-def->symbol-kind :clojure.core/defmacro)))
    (is
      (= "def" (kondo/var-def->symbol-kind :clojure.core/def))))

  (testing "falls back to keyword name for unknown"
    (is
      (= "defroute" (kondo/var-def->symbol-kind :compojure.core/defroute)))))

(deftest test-analysis->symbols
  (testing "parses var-definitions into symbol records"
    (let [raw     (slurp (io/resource "vestiga/fixtures/kondo_output.json"))
          parsed  (json/read-str raw :key-fn keyword)
          symbols (kondo/analysis->symbols (:analysis parsed))]
      (is
        (pos? (count symbols)))
      (is
        (every? :qualified-name symbols))
      (is
        (every? :kind symbols))
      (is
        (some #(= "defn" (:kind %)) symbols))
      (is
        (some #(= "sample.core/greet" (:qualified-name %)) symbols))
      (is
        (some #(= "defprotocol" (:kind %)) symbols)))))

(deftest test-analysis->refs
  (testing "parses var-usages into reference records"
    (let [raw    (slurp (io/resource "vestiga/fixtures/kondo_output.json"))
          parsed (json/read-str raw :key-fn keyword)
          refs   (kondo/analysis->refs (:analysis parsed))]
      (is
        (pos? (count refs)))
      (is
        (every? :to-ns refs))
      (is
        (every? :to-name refs))
      (is
        (some #(= "transform-item" (:to-name %)) refs)))))

(deftest test-analysis->ns-deps
  (testing "parses namespace-usages into dependency records"
    (let [raw    (slurp (io/resource "vestiga/fixtures/kondo_output.json"))
          parsed (json/read-str raw :key-fn keyword)
          deps   (kondo/analysis->ns-deps (:analysis parsed))]
      (is
        (pos? (count deps)))
      (is
        (some
          #(and
             (= "sample.core" (:from-ns %))
             (= "sample.util" (:to-ns %)))
          deps)))))
