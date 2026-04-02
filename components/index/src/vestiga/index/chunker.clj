(ns vestiga.index.chunker
  (:require
    [clojure.string :as str]))

(defn- in-string?
  "Check if we're inside a string literal at position i."
  [^String s i]
  (loop [j       0
         in-str  false
         escaped false]
    (if (>= j i)
      in-str
      (let [ch (.charAt s j)]
        (cond
          escaped
          (recur (inc j) in-str false)
          (= ch \\)
          (recur (inc j) in-str true)
          (= ch \")
          (recur (inc j) (not in-str) false)
          :else
          (recur (inc j) in-str false))))))

(defn split-top-level-forms
  "Split a Clojure source file into top-level forms.
   Returns a vector of maps:
   [{:content    \"(defn foo ...)\"
     :start-line 1
     :end-line   5}]

   Handles:
   - Reader macros (^:meta, #', @, etc.) attached to forms
   - Comments between forms (included in the following form's chunk)
   - String literals containing parens
   - Regex literals"
  [source-text]
  (when (and
          source-text
          (not (str/blank? source-text)))
    (let [^String src source-text
          lines       (str/split-lines src)
          line-count  (count lines)]
      (loop [i              0
             depth          0
             in-string      false
             in-comment     false
             in-regex       false
             escaped        false
             form-start     nil
             pre-form-start nil
             forms          (transient [])
             line-num       1
             col            0]
        (if (>= i (count src))
          ;; End of file — flush any remaining form
          (let [result (persistent! forms)]
            (if (and
                  form-start
                  (> depth 0))
              ;; Unclosed form — include it anyway
              (conj
                result
                {:content    (subs src (or pre-form-start form-start))
                 :start-line (inc (count (filter #(= % \newline) (subs src 0 (or pre-form-start form-start)))))
                 :end-line   line-num})
              result))
          (let [ch (.charAt src i)]
            (cond
              ;; Newline handling
              (= ch \newline)
              (recur (inc i) depth in-string false in-regex false form-start pre-form-start forms (inc line-num) 0)

              ;; In comment — skip to end of line
              in-comment
              (recur
                (inc i)
                depth
                in-string
                in-comment
                in-regex
                escaped
                form-start
                pre-form-start
                forms
                line-num
                (inc col))

              ;; Escaped character inside string
              (and
                escaped
                in-string)
              (recur
                (inc i)
                depth
                in-string
                in-comment
                in-regex
                false
                form-start
                pre-form-start
                forms
                line-num
                (inc col))

              ;; Escape character inside string
              (and
                in-string
                (= ch \\))
              (recur
                (inc i)
                depth
                in-string
                in-comment
                in-regex
                true
                form-start
                pre-form-start
                forms
                line-num
                (inc col))

              ;; End of string
              (and
                in-string
                (= ch \"))
              (recur (inc i) depth false in-comment in-regex false form-start pre-form-start forms line-num (inc col))

              ;; Inside string — skip
              in-string
              (recur
                (inc i)
                depth
                in-string
                in-comment
                in-regex
                false
                form-start
                pre-form-start
                forms
                line-num
                (inc col))

              ;; In regex — handle escapes
              (and
                in-regex
                (= ch \\))
              (recur
                (+ i 2)
                depth
                in-string
                in-comment
                in-regex
                false
                form-start
                pre-form-start
                forms
                line-num
                (+ col 2))

              ;; End of regex
              (and
                in-regex
                (= ch \"))
              (recur (inc i) depth in-string in-comment false false form-start pre-form-start forms line-num (inc col))

              ;; Inside regex — skip
              in-regex
              (recur
                (inc i)
                depth
                in-string
                in-comment
                in-regex
                false
                form-start
                pre-form-start
                forms
                line-num
                (inc col))

              ;; Start of comment
              (= ch \;)
              (recur (inc i) depth in-string true in-regex false form-start pre-form-start forms line-num (inc col))

              ;; Start of regex literal #"..."
              (and
                (= ch \#)
                (< (inc i) (count src))
                (= (.charAt src (inc i)) \"))
              (recur (+ i 2) depth in-string in-comment true false form-start pre-form-start forms line-num (+ col 2))

              ;; Start of string
              (= ch \")
              (recur (inc i) depth true in-comment in-regex false form-start pre-form-start forms line-num (inc col))

              ;; Opening delimiter
              (or (= ch \() (= ch \[) (= ch \{))
              (let [new-depth      (inc depth)
                    ;; If this is a new top-level form at depth 0
                    new-form-start (if (zero? depth) i form-start)
                    new-pre        (if (and
                                         (zero? depth)
                                         (nil? form-start))
                                     ;; Look back for reader macros/metadata on same line or preceding lines
                                     (let [start (or pre-form-start i)]
                                       start)
                                     pre-form-start)]
                (recur
                  (inc i)
                  new-depth
                  in-string
                  in-comment
                  in-regex
                  false
                  new-form-start
                  new-pre
                  forms
                  line-num
                  (inc col)))

              ;; Closing delimiter
              (or (= ch \)) (= ch \]) (= ch \}))
              (let [new-depth (max 0 (dec depth))]
                (if (zero? new-depth)
                  ;; Form complete — extract it
                  (let [form-end     (inc i)
                        actual-start (or pre-form-start form-start i)
                        content      (subs src actual-start form-end)
                        start-ln     (inc (count (filter #(= % \newline) (subs src 0 actual-start))))
                        end-ln       line-num]
                    (recur
                      (inc i)
                      0
                      in-string
                      in-comment
                      in-regex
                      false
                      nil
                      nil
                      (conj!
                        forms
                        {:content    content
                         :start-line start-ln
                         :end-line   end-ln})
                      line-num
                      (inc col)))
                  (recur
                    (inc i)
                    new-depth
                    in-string
                    in-comment
                    in-regex
                    false
                    form-start
                    pre-form-start
                    forms
                    line-num
                    (inc col))))

              ;; Any other character
              :else
              (recur
                (inc i)
                depth
                in-string
                in-comment
                in-regex
                false
                form-start
                pre-form-start
                forms
                line-num
                (inc col)))))))))

(defn correlate-chunks-with-symbols
  "Given chunks (from split-top-level-forms) and symbols (from clj-kondo),
   attach symbol metadata to each chunk by matching line ranges.
   A chunk may contain 0 symbols (kind='top-level-form') or 1 symbol."
  [chunks symbols]
  (mapv
    (fn [chunk]
      (let [matching-symbol (first
                              (filter
                                (fn [sym]
                                  (and
                                    (<= (:start-line chunk) (:start-line sym))
                                    (>= (:end-line chunk) (:start-line sym))))
                                symbols))]
        (if matching-symbol
          (merge
            chunk
            (select-keys
              matching-symbol
              [:qualified-name :namespace :symbol-name
               :kind :arity :docstring :private?]))
          (assoc chunk :kind "top-level-form"))))
    chunks))
