(ns vestiga.search.interface.ranker
  (:require
    [vestiga.search.ranker :as ranker]))

(defn reciprocal-rank-fusion
  ([result-lists] (ranker/reciprocal-rank-fusion result-lists))
  ([result-lists k] (ranker/reciprocal-rank-fusion result-lists k)))
