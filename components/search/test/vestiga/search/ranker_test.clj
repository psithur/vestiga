(ns vestiga.search.ranker-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.search.ranker :as ranker]))

(deftest test-rrf-basic
  (testing "single list returns items in order"
    (let [list1  [{:id   1
                   :name "a"}
                  {:id   2
                   :name "b"}
                  {:id   3
                   :name "c"}]
          result (ranker/reciprocal-rank-fusion [list1])]
      (is
        (= 3 (count result)))
      (is
        (= 1 (:id (first result))))))

  (testing "items in multiple lists get higher scores"
    (let [list1  [{:id 1} {:id 2} {:id 3}]
          list2  [{:id 2} {:id 1} {:id 4}]
          result (ranker/reciprocal-rank-fusion [list1 list2])]
      ;; id 1 and 2 appear in both lists, should score higher than 3 and 4
      (let [scores (into {} (map (juxt :id :rrf-score) result))]
        (is
          (> (scores 1) (scores 3)))
        (is
          (> (scores 2) (scores 4))))))

  (testing "all input IDs appear in output"
    (let [list1  [{:id 1} {:id 2}]
          list2  [{:id 3} {:id 4}]
          result (ranker/reciprocal-rank-fusion [list1 list2])
          ids    (set (map :id result))]
      (is
        (= #{1 2 3 4} ids))))

  (testing "empty input returns empty output"
    (is
      (empty? (ranker/reciprocal-rank-fusion [])))
    (is
      (empty? (ranker/reciprocal-rank-fusion [[]])))))

(deftest test-rrf-monotonicity
  (testing "adding an item to more lists increases its score"
    (let [list1          [{:id 1} {:id 2}]
          list2          [{:id 3}]
          result-without (ranker/reciprocal-rank-fusion [list1 list2])
          list2-with-1   [{:id 1} {:id 3}]
          result-with    (ranker/reciprocal-rank-fusion [list1 list2-with-1])
          score-without  (some
                           #(when (= 1 (:id %))
                              (:rrf-score %))
                           result-without)
          score-with     (some
                           #(when (= 1 (:id %))
                              (:rrf-score %))
                           result-with)]
      (is
        (> score-with score-without)))))
