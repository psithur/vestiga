(ns vestiga.ast.editor-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [rewrite-clj.zip :as z]
    [vestiga.ast.editor :as editor]
    [vestiga.ast.resolver :as resolver]))

(def sample-src
  "(ns sample.core)\n\n(defn greet\n  \"Greet someone.\"\n  [name]\n  (str \"Hello, \" name))\n\n(defn farewell [name]\n  (str \"Goodbye, \" name))\n")

(deftest test-replace-form
  (let [zloc   (z/of-string sample-src)
        target (resolver/find-defn-by-name zloc "greet")
        result (editor/replace-form target "(defn greet [name]\n  (str \"G'day, \" name))")
        output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (testing "new form is present"
      (is
        (str/includes? output "G'day")))
    (testing "other forms are untouched"
      (is
        (str/includes? output "(defn farewell")))
    (testing "ns form is untouched"
      (is
        (str/includes? output "(ns sample.core)")))))

(deftest test-replace-body
  (let [zloc   (z/of-string sample-src)
        target (resolver/find-defn-by-name zloc "greet")
        result (editor/replace-body target "  (str \"G'day, \" name)")
        output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (testing "body is replaced"
      (is
        (str/includes? output "G'day")))
    (testing "docstring is preserved"
      (is
        (str/includes? output "Greet someone.")))
    (testing "arglist is preserved"
      (is
        (str/includes? output "[name]")))
    (testing "defn keyword is preserved"
      (is
        (str/includes? output "(defn greet")))))

(deftest test-replace-body-multi-expression
  (let [zloc   (z/of-string sample-src)
        target (resolver/find-defn-by-name zloc "greet")
        result (editor/replace-body target "(println \"logging\")\n  (str \"Hi, \" name)")
        output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (testing "both expressions present"
      (is
        (str/includes? output "println"))
      (is
        (str/includes? output "Hi, ")))))

(deftest test-replace-body-rejects-multi-arity
  (let [src    "(ns x)\n\n(defn foo\n  ([a] a)\n  ([a b] (+ a b)))"
        zloc   (z/of-string src)
        target (resolver/find-defn-by-name zloc "foo")
        result (editor/replace-body target "(* a b)")]
    (is
      (:error result))
    (is
      (str/includes? (:error result) "ulti-arity"))))

(deftest test-replace-body-rejects-non-defn
  (let [src    "(ns x)\n\n(def timeout 5000)"
        zloc   (z/of-string src)
        target (resolver/find-defn-by-name zloc "timeout")
        result (editor/replace-body target "6000")]
    (is
      (:error result))
    (is
      (str/includes? (:error result) "Not a defn"))))

(deftest test-add-form-before
  (let [zloc   (z/of-string sample-src)
        target (resolver/find-defn-by-name zloc "farewell")
        result (editor/add-form-before target "(defn shout [name]\n  (str \"OI, \" name))")
        output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (testing "new form is inserted"
      (is
        (str/includes? output "defn shout")))
    (testing "inserted before farewell"
      (is
        (< (.indexOf output "shout") (.indexOf output "farewell"))))))

(deftest test-add-form-after
  (let [zloc   (z/of-string sample-src)
        target (resolver/find-defn-by-name zloc "greet")
        result (editor/add-form-after target "(defn shout [name]\n  (str \"OI, \" name))")
        output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (testing "new form is inserted"
      (is
        (str/includes? output "defn shout")))
    (testing "inserted between greet and farewell"
      (is
        (< (.indexOf output "shout") (.indexOf output "farewell")))
      (is
        (> (.indexOf output "shout") (.indexOf output "greet"))))))

(deftest test-delete-form
  (let [zloc   (z/of-string sample-src)
        target (resolver/find-defn-by-name zloc "farewell")
        result (editor/delete-form target)
        output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (is
      (not (str/includes? output "farewell")))
    (is
      (str/includes? output "greet"))))

(deftest test-add-require
  (let [zloc   (z/of-string sample-src)
        ns-loc (resolver/find-ns-form zloc)]
    (testing "adds new require"
      (let [result (editor/add-require ns-loc "clojure.string :as str")
            output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
        (is
          (str/includes? output ":require"))
        (is
          (str/includes? output "clojure.string"))))
    (testing "idempotent"
      (let [with-req (editor/add-require ns-loc "clojure.string :as str")
            output1  (z/root-string (z/of-string (rewrite-clj.node/string with-req)))
            ns-loc2  (resolver/find-ns-form (z/of-string output1))
            result   (editor/add-require ns-loc2 "clojure.string :as str")
            output2  (z/root-string (z/of-string (rewrite-clj.node/string result)))]
        ;; Should appear exactly once
        (is
          (= 1 (count (re-seq #"clojure\.string" output2))))))))

(deftest test-add-require-to-existing
  (let [src    "(ns sample.ns-v\n  (:require\n    [clojure.string :as str]))"
        zloc   (z/of-string src)
        ns-loc (resolver/find-ns-form zloc)
        result (editor/add-require ns-loc "clojure.set :as set")
        output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (testing "adds to existing :require"
      (is
        (str/includes? output "clojure.set"))
      (is
        (str/includes? output "clojure.string")))))

(deftest test-replace-ns
  (let [zloc   (z/of-string sample-src)
        ns-loc (resolver/find-ns-form zloc)
        result (editor/replace-ns ns-loc "(ns sample.core\n  (:require [clojure.string :as str]))")
        output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (testing "ns form is replaced"
      (is
        (str/includes? output "(:require [clojure.string :as str])")))
    (testing "other forms untouched"
      (is
        (str/includes? output "(defn greet")))))

(deftest test-append-to-file
  (let [zloc   (z/of-string sample-src)
        result (editor/append-to-file zloc "(def new-thing 42)")
        output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (is
      (str/includes? output "new-thing"))
    (is
      (str/ends-with? (str/trim output) "(def new-thing 42)"))))

(deftest test-round-trip-identity
  (let [src  sample-src
        zloc (z/of-string src)]
    ;; For every top-level form, replace it with itself
    (loop [loc zloc]
      (when loc
        (when (z/list? loc)
          (let [original-text (z/string loc)
                replaced      (editor/replace-form loc original-text)
                output        (z/root-string (z/of-string (rewrite-clj.node/string replaced)))]
            (is
              (= src output)
              (str
                "Round-trip failed for form starting with: "
                (subs original-text 0 (min 40 (count original-text)))))))
        (recur (z/right loc))))))

(deftest test-replace-defmethod
  (let
    [src
     "(ns x)\n\n(defmulti dispatch :type)\n\n(defmethod dispatch :http [req]\n  (handle-http req))\n\n(defmethod dispatch :grpc [req]\n  (handle-grpc req))"
     zloc (z/of-string src)
     target (resolver/find-defmethod-by-dispatch zloc "dispatch" ":http")
     result (editor/replace-form target "(defmethod dispatch :http [req]\n  (new-handler req))")
     output (z/root-string (z/of-string (rewrite-clj.node/string result)))]
    (testing "defmethod is replaced"
      (is
        (str/includes? output "new-handler")))
    (testing "other defmethod untouched"
      (is
        (str/includes? output "handle-grpc")))))
