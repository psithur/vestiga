(ns vestiga.conversation.discovery-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.conversation.discovery :as d]))

(deftest encode-project-path-test
  (is
    (= "-home-user-projects-vestiga" (d/encode-project-path "/home/user/projects/vestiga")))
  (is
    (= "-Users-jack-client-my-project" (d/encode-project-path "/Users/jack/client/my-project"))))

(deftest decode-project-path-test
  (is
    (= "/home/user/projects/vestiga" (d/decode-project-path "-home-user-projects-vestiga")))
  (is
    (= "/Users/jack/client/my/project" (d/decode-project-path "-Users-jack-client-my-project"))))

(deftest roundtrip-test
  (testing "encode then decode is identity for absolute paths"
    (let [paths ["/home/user/projects/vestiga"
                 "/tmp/test"
                 "/Users/jack/code"]]
      (doseq [p paths]
        (is
          (= p (d/decode-project-path (d/encode-project-path p))))))))

(deftest claude-code-dir-test
  (is
    (clojure.string/ends-with? (d/claude-code-dir) "/.claude/projects")))

(deftest find-sessions-returns-nil-for-missing-project
  (is
    (nil? (d/find-sessions "/nonexistent/path/that/should/not/exist"))))
