(ns vestiga.ast.resolver-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [rewrite-clj.zip :as z]
    [vestiga.ast.resolver :as resolver]))

(def sample-src
  "(ns sample.core)\n\n(defn greet\n  \"Greet someone.\"\n  [name]\n  (str \"Hello, \" name))\n\n(defn farewell [name]\n  (str \"Goodbye, \" name))\n")

(def def-src
  "(ns sample.defs)\n\n(def timeout 5000)\n\n(defonce state (atom {}))\n\n(defmacro with-timing [& body] `(do ~@body))\n")

(def multimethod-src
  "(ns sample.multi)\n\n(defmulti dispatch :type)\n\n(defmethod dispatch :http [req]\n  (handle-http req))\n\n(defmethod dispatch :grpc [req]\n  (handle-grpc req))\n")

(def protocol-src
  "(ns sample.proto)\n\n(defprotocol Cacheable\n  (cache-key [this])\n  (ttl [this]))\n\n(defrecord AppCache [store ttl-ms]\n  Cacheable\n  (cache-key [this] (:name store))\n  (ttl [this] ttl-ms))\n")

(deftest test-find-ns-form
  (let [zloc (z/of-string sample-src)]
    (testing "finds ns form"
      (let [loc (resolver/find-ns-form zloc)]
        (is
          (some? loc))
        (is
          (not (:error loc)))
        (is
          (str/starts-with? (z/string loc) "(ns sample.core)"))))

    (testing "returns error when no ns form"
      (let [loc (resolver/find-ns-form (z/of-string "(defn foo [] 1)"))]
        (is
          (:error loc))))))

(deftest test-find-defn-by-name
  (let [zloc (z/of-string sample-src)]
    (testing "finds defn by simple name"
      (let [loc (resolver/find-defn-by-name zloc "greet")]
        (is
          (some? loc))
        (is
          (not (:error loc)))
        (is
          (str/starts-with? (z/string loc) "(defn greet"))))

    (testing "finds defn by qualified name"
      (let [loc (resolver/find-defn-by-name zloc "sample.core/greet")]
        (is
          (some? loc))
        (is
          (str/starts-with? (z/string loc) "(defn greet"))))

    (testing "returns error for missing symbol"
      (let [loc (resolver/find-defn-by-name zloc "nonexistent")]
        (is
          (:error loc))))))

(deftest test-find-def-forms
  (let [zloc (z/of-string def-src)]
    (testing "finds def"
      (let [loc (resolver/find-defn-by-name zloc "timeout")]
        (is
          (some? loc))
        (is
          (not (:error loc)))
        (is
          (str/starts-with? (z/string loc) "(def timeout"))))

    (testing "finds defonce"
      (let [loc (resolver/find-defn-by-name zloc "state")]
        (is
          (some? loc))
        (is
          (str/starts-with? (z/string loc) "(defonce state"))))

    (testing "finds defmacro"
      (let [loc (resolver/find-defn-by-name zloc "with-timing")]
        (is
          (some? loc))
        (is
          (str/starts-with? (z/string loc) "(defmacro with-timing"))))))

(deftest test-find-protocol-and-record
  (let [zloc (z/of-string protocol-src)]
    (testing "finds defprotocol"
      (let [loc (resolver/find-defn-by-name zloc "Cacheable")]
        (is
          (some? loc))
        (is
          (str/starts-with? (z/string loc) "(defprotocol Cacheable"))))

    (testing "finds defrecord"
      (let [loc (resolver/find-defn-by-name zloc "AppCache")]
        (is
          (some? loc))
        (is
          (str/starts-with? (z/string loc) "(defrecord AppCache"))))))

(deftest test-find-defmethod-by-dispatch
  (let [zloc (z/of-string multimethod-src)]
    (testing "finds defmethod by dispatch value"
      (let [loc (resolver/find-defmethod-by-dispatch zloc "dispatch" ":http")]
        (is
          (some? loc))
        (is
          (not (:error loc)))
        (is
          (str/includes? (z/string loc) ":http"))))

    (testing "distinguishes dispatch values"
      (let [loc (resolver/find-defmethod-by-dispatch zloc "dispatch" ":grpc")]
        (is
          (str/includes? (z/string loc) "handle-grpc"))))

    (testing "returns error for missing dispatch value"
      (let [loc (resolver/find-defmethod-by-dispatch zloc "dispatch" ":ws")]
        (is
          (:error loc))))

    (testing "finds defmulti via find-defn-by-name"
      (let [loc (resolver/find-defn-by-name zloc "dispatch")]
        (is
          (some? loc))
        (is
          (not (:error loc)))
        (is
          (str/starts-with? (z/string loc) "(defmulti dispatch"))))))
