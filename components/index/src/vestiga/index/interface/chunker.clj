(ns vestiga.index.interface.chunker
  (:require
    [vestiga.index.chunker :as chunker]))

(defn split-top-level-forms
  [source-text]
  (chunker/split-top-level-forms source-text))

(defn correlate-chunks-with-symbols
  [chunks symbols]
  (chunker/correlate-chunks-with-symbols chunks symbols))
