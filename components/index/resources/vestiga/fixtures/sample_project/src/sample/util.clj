(ns sample.util)

(defn transform-item
  "Transform an item by adding a processed flag."
  [item]
  (assoc item :processed true))

(defn valid?
  "Check if an item is valid."
  [item]
  (and
    (:name item)
    (not (:deleted item))))

(defn format-output
  "Format an item for display."
  [item]
  (str (:name item) " - " (if (:processed item) "done" "pending")))
