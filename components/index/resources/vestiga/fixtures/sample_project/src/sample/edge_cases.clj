(ns sample.edge-cases)

(def regex-example #"foo\(bar\)")

(defn string-with-parens
  "A docstring with (parens) and [brackets]."
  [x]
  (str "result: (" x ")"))

(defn ^:private private-fn
  [x]
  (inc x))

(defonce state (atom {}))

(defmacro with-timing
  "Time the body."
  [& body]
  `(let [start# (System/currentTimeMillis)]
     ~@body
     (- (System/currentTimeMillis) start#)))
