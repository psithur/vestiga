(ns vestiga.ast.applier-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [vestiga.ast.applier :as applier]
    [vestiga.test-helpers :as h]))

(defn- setup-sample-project
  "Create a minimal sample project in dir and return a mock kondo analysis."
  [dir]
  (let [src-dir (str dir "/src/sample")]
    (.mkdirs (io/file src-dir))
    (spit
      (str src-dir "/core.clj")
      "(ns sample.core)\n\n(defn greet\n  \"Greet someone.\"\n  [name]\n  (str \"Hello, \" name))\n\n(defn farewell [name]\n  (str \"Goodbye, \" name))\n")
    (spit
      (str src-dir "/multi.clj")
      "(ns sample.multi)\n\n(defmulti dispatch :type)\n\n(defmethod dispatch :http [req]\n  (handle-http req))\n\n(defmethod dispatch :grpc [req]\n  (handle-grpc req))\n")
    ;; Return mock kondo analysis
    {:namespace-definitions [{:name     :sample.core
                              :filename (str src-dir "/core.clj")
                              :row      1
                              :col      1}
                             {:name     :sample.multi
                              :filename (str src-dir "/multi.clj")
                              :row      1
                              :col      1}]
     :var-definitions       [{:ns         :sample.core
                              :name       'greet
                              :filename   (str src-dir "/core.clj")
                              :row        3
                              :col        1
                              :end-row    6
                              :end-col    26
                              :defined-by :clojure.core/defn}
                             {:ns         :sample.core
                              :name       'farewell
                              :filename   (str src-dir "/core.clj")
                              :row        8
                              :col        1
                              :end-row    9
                              :end-col    26
                              :defined-by :clojure.core/defn}
                             {:ns         :sample.multi
                              :name       'dispatch
                              :filename   (str src-dir "/multi.clj")
                              :row        3
                              :col        1
                              :end-row    3
                              :end-col    25
                              :defined-by :clojure.core/defmulti}]
     :var-usages            []
     :namespace-usages      []}))

(deftest ^:integration test-replace-form-on-disk
  (h/with-temp-dir
    (fn [dir]
      (let [analysis (setup-sample-project dir)
            result   (applier/apply-edits!
                       analysis
                       dir
                       [{:operation "replace_form"
                         :target    "sample.core/greet"
                         :content   "(defn greet [name]\n  (str \"G'day, \" name))"}])]
        (is
          (:success result))
        (let [content (slurp (str dir "/src/sample/core.clj"))]
          (is
            (str/includes? content "G'day"))
          (is
            (str/includes? content "farewell")))))))

(deftest ^:integration test-replace-body-on-disk
  (h/with-temp-dir
    (fn [dir]
      (let [analysis (setup-sample-project dir)
            result   (applier/apply-edits!
                       analysis
                       dir
                       [{:operation "replace_body"
                         :target    "sample.core/greet"
                         :content   "  (str \"Hey, \" name)"}])]
        (is
          (:success result))
        (let [content (slurp (str dir "/src/sample/core.clj"))]
          (is
            (str/includes? content "Hey, "))
          (is
            (str/includes? content "Greet someone."))
          (is
            (str/includes? content "[name]")))))))

(deftest ^:integration test-add-and-delete-form
  (h/with-temp-dir
    (fn [dir]
      (let [analysis (setup-sample-project dir)
            ;; Add a form after greet
            result1  (applier/apply-edits!
                       analysis
                       dir
                       [{:operation "add_form_after"
                         :target    "sample.core/greet"
                         :content   "(defn shout [name]\n  (str \"OI, \" name))"}])]
        (is
          (:success result1))
        (let [content (slurp (str dir "/src/sample/core.clj"))]
          (is
            (str/includes? content "defn shout")))))))

(deftest ^:integration test-add-require-on-disk
  (h/with-temp-dir
    (fn [dir]
      (let [analysis (setup-sample-project dir)
            result   (applier/apply-edits!
                       analysis
                       dir
                       [{:operation "add_require"
                         :target    "clojure.string :as str"
                         :file      (str dir "/src/sample/core.clj")}])]
        (is
          (:success result))
        (let [content (slurp (str dir "/src/sample/core.clj"))]
          (is
            (str/includes? content "clojure.string")))))))

(deftest ^:integration test-append-to-ns-on-disk
  (h/with-temp-dir
    (fn [dir]
      (let [analysis (setup-sample-project dir)
            result   (applier/apply-edits!
                       analysis
                       dir
                       [{:operation "append_to_ns"
                         :target    "sample.core"
                         :content   "(def new-thing 42)"}])]
        (is
          (:success result))
        (let [content (slurp (str dir "/src/sample/core.clj"))]
          (is
            (str/includes? content "new-thing")))))))

(deftest ^:integration test-replace-defmethod-on-disk
  (h/with-temp-dir
    (fn [dir]
      (let [analysis (setup-sample-project dir)
            result   (applier/apply-edits!
                       analysis
                       dir
                       [{:operation "replace_defmethod"
                         :target    "sample.multi/dispatch :http"
                         :content   "(defmethod dispatch :http [req]\n  (new-http-handler req))"}])]
        (is
          (:success result))
        (let [content (slurp (str dir "/src/sample/multi.clj"))]
          (is
            (str/includes? content "new-http-handler"))
          (is
            (str/includes? content "handle-grpc")))))))

(deftest ^:integration test-multi-op-single-file
  (h/with-temp-dir
    (fn [dir]
      (let [analysis (setup-sample-project dir)
            result   (applier/apply-edits!
                       analysis
                       dir
                       [{:operation "replace_body"
                         :target    "sample.core/greet"
                         :content   "  (str \"Hi, \" name)"}
                        {:operation "delete_form"
                         :target    "sample.core/farewell"}])]
        (is
          (:success result))
        (let [content (slurp (str dir "/src/sample/core.clj"))]
          (is
            (str/includes? content "Hi, "))
          (is
            (not (str/includes? content "farewell"))))))))

(deftest test-invalid-operations
  (let [result (applier/apply-edits! {} "." [{:operation "invalid_op"}])]
    (is
      (not (:success result)))
    (is
      (= :error (get-in result [:results 0 :status])))))

(deftest ^:integration test-missing-target-error
  (h/with-temp-dir
    (fn [dir]
      (let [analysis (setup-sample-project dir)
            result   (applier/apply-edits!
                       analysis
                       dir
                       [{:operation "replace_form"
                         :target    "sample.core/nonexistent"
                         :content   "(defn nonexistent [] nil)"}])]
        (is
          (not (:success result)))
        (is
          (= :error (get-in result [:results 0 :status])))))))
