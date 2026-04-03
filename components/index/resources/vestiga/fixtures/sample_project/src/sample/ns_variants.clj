(ns sample.ns-variants
  (:require
    [clojure.string :as str])
  (:import [java.util Date]))

(defn uses-require
  "Uses the required namespace."
  [s]
  (str/upper-case s))

(defn uses-import
  "Uses the imported class."
  []
  (Date.))
