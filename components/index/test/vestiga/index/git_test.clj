(ns vestiga.index.git-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.index.git :as git]
    [vestiga.test-helpers :as h]))

(deftest ^:integration test-git-head-sha
  (h/with-temp-git-repo
    (fn [dir]
      (spit (str dir "/README.md") "hello")
      (h/git-shell dir "git" "add" ".")
      (h/git-shell dir "git" "commit" "-m" "init"))
    (fn [dir]
      (testing "returns HEAD SHA"
        (let [sha (git/git-head-sha dir)]
          (is
            (some? sha))
          (is
            (= 40 (count sha))))))))

(deftest ^:integration test-git-log
  (h/with-temp-git-repo
    (fn [dir]
      (spit (str dir "/file.txt") "v1")
      (h/git-shell dir "git" "add" ".")
      (h/git-shell dir "git" "commit" "-m" "First commit")
      (spit (str dir "/file.txt") "v2")
      (h/git-shell dir "git" "add" ".")
      (h/git-shell dir "git" "commit" "-m" "Second commit"))
    (fn [dir]
      (testing "parses git log into commit records"
        (let [commits (git/git-log dir)]
          (is
            (= 2 (count commits)))
          (is
            (= "Second commit" (:message (first commits))))
          (is
            (= "First commit" (:message (second commits)))))))))

(deftest ^:integration test-git-changed-files
  (h/with-temp-git-repo
    (fn [dir]
      (spit (str dir "/a.txt") "1")
      (h/git-shell dir "git" "add" ".")
      (h/git-shell dir "git" "commit" "-m" "init"))
    (fn [dir]
      (let [sha1 (git/git-head-sha dir)]
        (spit (str dir "/b.txt") "2")
        (h/git-shell dir "git" "add" ".")
        (h/git-shell dir "git" "commit" "-m" "add b")
        (testing "shows files changed since SHA"
          (let [changed (git/git-changed-files dir sha1)]
            (is
              (some #(= "b.txt" %) changed))))))))
