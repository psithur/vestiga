(ns vestiga.search.ranker)

(def default-k 60)

(defn reciprocal-rank-fusion
  "Combine multiple ranked result lists using Reciprocal Rank Fusion.
   Each input list is a sequence of maps with at least an :id key.
   Returns a sorted vector of maps with :id and :score."
  ([result-lists] (reciprocal-rank-fusion result-lists default-k))
  ([result-lists k]
   (let [;; Build score map: id -> accumulated RRF score
         scores (reduce
                  (fn [acc result-list]
                    (reduce
                      (fn [acc2 [rank item]]
                        (let [id        (:id item)
                              rrf-score (/ 1.0 (+ k rank 1))]
                          (update acc2 id (fnil + 0.0) rrf-score)))
                      acc
                      (map-indexed vector result-list)))
                  {}
                  result-lists)
         ;; Collect all items by id (take first occurrence)
         items  (reduce
                  (fn [acc result-list]
                    (reduce
                      (fn [acc2 item]
                        (if (contains? acc2 (:id item)) acc2 (assoc acc2 (:id item) item)))
                      acc
                      result-list))
                  {}
                  result-lists)]
     (->> scores
          (mapv
            (fn [[id score]]
              (assoc (get items id) :rrf-score score)))
          (sort-by :rrf-score >)
          vec))))
