(ns vestiga.index.file-tracker-test
  (:require
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]
    [vestiga.db.interface.ops :as ops]
    [vestiga.index.file-tracker :as tracker]
    [vestiga.test-helpers :as h]))

(deftest test-file-hash
  (testing "computes SHA-256 hash of file contents"
    (h/with-temp-dir
      (fn [dir]
        (let [path (str dir "/test.clj")]
          (spit path "(defn foo [] :ok)")
          (let [hash (tracker/file-hash path)]
            (is
              (string? hash))
            (is
              (= 64 (count hash))
              "SHA-256 hex should be 64 chars")))))))

(deftest test-file-hash-deterministic
  (testing "same content produces same hash"
    (h/with-temp-dir
      (fn [dir]
        (spit (str dir "/a.clj") "(defn foo [] :ok)")
        (spit (str dir "/b.clj") "(defn foo [] :ok)")
        (is
          (= (tracker/file-hash (str dir "/a.clj")) (tracker/file-hash (str dir "/b.clj"))))))))

(deftest test-file-hash-changes-with-content
  (testing "different content produces different hash"
    (h/with-temp-dir
      (fn [dir]
        (spit (str dir "/a.clj") "(defn foo [] :ok)")
        (spit (str dir "/b.clj") "(defn bar [] :nope)")
        (is
          (not= (tracker/file-hash (str dir "/a.clj")) (tracker/file-hash (str dir "/b.clj"))))))))

(deftest test-file-hash-nonexistent
  (testing "returns nil for nonexistent file"
    (is
      (nil? (tracker/file-hash "/nonexistent/path.clj")))))

(deftest test-find-source-files
  (testing "finds .clj files under specified paths"
    (h/with-temp-dir
      (fn [dir]
        (let [src-dir (io/file dir "src" "my")]
          (.mkdirs src-dir)
          (spit (str src-dir "/core.clj") "(ns my.core)")
          (spit (str src-dir "/util.clj") "(ns my.util)")
          ;; Also create a non-clj file that should be excluded
          (spit (str src-dir "/readme.txt") "not clojure"))

        (let [files (tracker/find-source-files dir ["src"] #{".clj"})]
          (is
            (= 2 (count files)))
          (is
            (every? #(clojure.string/ends-with? (:relative-path %) ".clj") files))
          (is
            (every? #(.startsWith (:relative-path %) "src/") files)))))))

(deftest test-find-source-files-multiple-paths
  (testing "finds files under multiple paths"
    (h/with-temp-dir
      (fn [dir]
        (.mkdirs (io/file dir "src" "app"))
        (.mkdirs (io/file dir "test" "app"))
        (spit (str dir "/src/app/core.clj") "(ns app.core)")
        (spit (str dir "/test/app/core_test.clj") "(ns app.core-test)")

        (let [files (tracker/find-source-files dir ["src" "test"] #{".clj"})]
          (is
            (= 2 (count files))))))))

(deftest test-find-source-files-multiple-extensions
  (testing "finds files with multiple extensions"
    (h/with-temp-dir
      (fn [dir]
        (.mkdirs (io/file dir "src"))
        (spit (str dir "/src/core.clj") "(ns core)")
        (spit (str dir "/src/app.cljs") "(ns app)")
        (spit (str dir "/src/shared.cljc") "(ns shared)")
        (spit (str dir "/src/ignore.txt") "not this")

        (let [files (tracker/find-source-files dir ["src"] #{".clj" ".cljs" ".cljc"})]
          (is
            (= 3 (count files))))))))

(deftest test-files-needing-reindex
  (testing "identifies files that differ from stored hash"
    (h/with-temp-dir
      (fn [dir]
        ;; Create source files
        (.mkdirs (io/file dir "src"))
        (spit (str dir "/src/a.clj") "(ns a)")
        (spit (str dir "/src/b.clj") "(ns b)")

        (h/with-temp-db
          (fn [db]
            (let [pid    (ops/upsert-project!
                           db
                           {:root-path dir
                            :name      "test"})
                  files  (tracker/find-source-files dir ["src"] #{".clj"})
                  hash-a (tracker/file-hash (str dir "/src/a.clj"))]

              ;; Index only a.clj (store its hash)
              (ops/insert-chunk!
                db
                {:project-id     pid
                 :file-path      "src/a.clj"
                 :namespace      "a"
                 :qualified-name nil
                 :symbol-name    nil
                 :kind           "ns"
                 :content        "(ns a)"
                 :start-line     1
                 :end-line       1
                 :arity          nil
                 :docstring      nil
                 :file-hash      hash-a})

              ;; Both files should need reindex: b.clj has no stored hash,
              ;; a.clj hash matches
              (let [needs-reindex (tracker/files-needing-reindex files db pid)]
                (is
                  (= 1 (count needs-reindex))
                  "Only b.clj should need reindexing")
                (is
                  (clojure.string/ends-with? (:relative-path (first needs-reindex)) "b.clj")))

              ;; Now modify a.clj
              (spit (str dir "/src/a.clj") "(ns a)\n(def x 1)")
              (let [needs-reindex (tracker/files-needing-reindex files db pid)]
                (is
                  (= 2 (count needs-reindex))
                  "Both files should need reindexing after a.clj changes")))))))))
