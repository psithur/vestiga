(ns sample.multi-arity)

(defn foo
  "A multi-arity function."
  ([a] a)
  ([a b] (+ a b))
  ([a b c] (+ a b c)))

(defn single-arity
  "A single-arity function."
  [x]
  (* x 2))
