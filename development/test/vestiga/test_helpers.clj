(ns vestiga.test-helpers
  (:require
    [babashka.process :as proc]
    [clojure.data.json :as json]
    [clojure.java.io :as io]
    [vestiga.db.interface :as db]
    [vestiga.db.interface.schema :as schema])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn with-temp-db
  "Create an in-memory SQLite database for testing.
   Applies schema. Passes db to f. Closes on exit."
  [f]
  (let [db (db/open-db ":memory:")]
    (try (schema/ensure-schema! db) (f db) (finally (db/close-db db)))))

(defn with-temp-dir
  "Create a temporary directory, pass its path to f, clean up after."
  [f]
  (let [dir (Files/createTempDirectory "vestiga-test" (into-array FileAttribute []))]
    (try (f (str dir)) (finally (run! io/delete-file (reverse (file-seq (io/file (str dir)))))))))

(def ^:private git-test-env
  "Environment variables to isolate git from global config."
  {"GIT_CONFIG_GLOBAL"   "/dev/null"
   "GIT_CONFIG_SYSTEM"   "/dev/null"
   "GIT_COMMITTER_NAME"  "Test"
   "GIT_COMMITTER_EMAIL" "test@test.com"
   "GIT_AUTHOR_NAME"     "Test"
   "GIT_AUTHOR_EMAIL"    "test@test.com"})

(defn git-shell
  "Run a git command in dir, isolated from global git config."
  [dir & args]
  (apply
    proc/shell
    {:dir       dir
     :extra-env git-test-env}
    args))

(defn with-temp-git-repo
  "Create a temporary directory with an initialised git repo.
   setup-fn and test-fn each receive [dir]."
  [setup-fn test-fn]
  (with-temp-dir
    (fn [dir]
      (git-shell dir "git" "init")
      (git-shell dir "git" "config" "user.email" "test@test.com")
      (git-shell dir "git" "config" "user.name" "Test")
      (git-shell dir "git" "config" "commit.gpgsign" "false")
      (when setup-fn
        (setup-fn dir))
      (test-fn dir))))

(defn fixture-path
  "Resolve a path under test/vestiga/fixtures/."
  [relative-path]
  (io/resource (str "vestiga/fixtures/" relative-path)))

(defn mock-ollama-handler
  "Returns a handler fn that mimics Ollama's /api/embed endpoint.
   Returns random unit vectors of the given dimension."
  [dim]
  (fn [request]
    (let [body       (json/read-str (slurp (:body request)) :key-fn keyword)
          n          (count (:input body))
          embeddings (vec
                       (repeatedly
                         n
                         #(vec
                            (repeatedly
                              dim
                              (fn []
                                (- (rand 2.0) 1.0))))))]
      {:status 200
       :body   (json/write-str
                 {:model      (:model body)
                  :embeddings embeddings})})))
