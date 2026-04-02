(ns vestiga.index.chunker-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.index.chunker :as chunker]))

(deftest test-split-top-level-forms
  (testing "splits simple defn forms"
    (let [source "(ns my.core)\n\n(defn foo [x] x)\n\n(defn bar [y] y)"
          forms  (chunker/split-top-level-forms source)]
      (is
        (= 3 (count forms)))
      (is
        (= "(ns my.core)" (:content (first forms))))
      (is
        (= 1 (:start-line (first forms))))
      (is
        (= "(defn foo [x] x)" (:content (second forms))))
      (is
        (= "(defn bar [y] y)" (:content (nth forms 2))))))

  (testing "handles strings with parens"
    (let [source "(defn foo [] (str \"hello (world)\"))"
          forms  (chunker/split-top-level-forms source)]
      (is
        (= 1 (count forms)))))

  (testing "handles multi-line forms"
    (let [source "(defn foo\n  \"Docstring.\"\n  [x]\n  (+ x 1))"
          forms  (chunker/split-top-level-forms source)]
      (is
        (= 1 (count forms)))
      (is
        (= 1 (:start-line (first forms))))
      (is
        (= 4 (:end-line (first forms))))))

  (testing "handles regex literals"
    (let [source "(def pattern #\"[()]+\")\n\n(defn foo [] :ok)"
          forms  (chunker/split-top-level-forms source)]
      (is
        (= 2 (count forms)))))

  (testing "returns nil for empty input"
    (is
      (nil? (chunker/split-top-level-forms "")))
    (is
      (nil? (chunker/split-top-level-forms nil))))

  (testing "handles comments between forms"
    (let [source "(defn a [] 1)\n;; a comment\n(defn b [] 2)"
          forms  (chunker/split-top-level-forms source)]
      (is
        (= 2 (count forms))))))

(deftest test-correlate-chunks-with-symbols
  (testing "attaches symbol metadata to matching chunks"
    (let [chunks  [{:content    "(defn greet [name] name)"
                    :start-line 1
                    :end-line   1}
                   {:content    "(def x 42)"
                    :start-line 3
                    :end-line   3}]
          symbols [{:qualified-name "my.core/greet"
                    :namespace      "my.core"
                    :symbol-name    "greet"
                    :kind           "defn"
                    :start-line     1
                    :end-line       1}]
          result  (chunker/correlate-chunks-with-symbols chunks symbols)]
      (is
        (= "my.core/greet" (:qualified-name (first result))))
      (is
        (= "defn" (:kind (first result))))
      (is
        (= "top-level-form" (:kind (second result)))))))
