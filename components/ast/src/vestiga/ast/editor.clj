(ns vestiga.ast.editor
  (:require
    [rewrite-clj.node :as n]
    [rewrite-clj.zip :as z]))

;; ── replace_form ──────────────────────────────────────────────

(defn replace-form
  "Replace the entire form at zloc with new source text.

   zloc: zipper positioned at the target form
   content: string of the new form

   Returns modified zipper (at root)."
  [zloc content]
  (-> zloc
      (z/replace (n/coerce (z/node (z/of-string content))))
      z/root))

;; ── replace_body ──────────────────────────────────────────────

(defn- defn-body-start
  "Navigate into a defn form and return a zloc at the first body expression.

   From the defn form:
   1. z/down → 'defn symbol
   2. z/right → name symbol
   3. z/right → docstring, metadata, or arglist
   4. Skip docstring (string?) and metadata (map?) if present
   5. Skip arglist (vector?)
   6. z/right → first body expression

   Returns zloc at first body form, or {:error ...} if:
   - Form is not a defn/defn-
   - Form is multi-arity (next element after name is a list, not a vector)"
  [defn-zloc]
  (let [first-child (z/down defn-zloc)]
    (if-not (and
              first-child
              (#{:token} (z/tag first-child))
              (#{'defn 'defn-} (z/sexpr first-child)))
      {:error "Not a defn/defn- form"}
      (let [after-name (-> first-child
                           z/right
                           z/right) ;; skip 'defn + name
            ;; Skip docstring if present
            current    (if (and
                             after-name
                             (string? (z/sexpr after-name)))
                         (z/right after-name)
                         after-name)
            ;; Skip metadata map if present
            current    (if (and
                             current
                             (= :map (z/tag current)))
                         (z/right current)
                         current)]
        (cond
          ;; Multi-arity: element after name/doc/meta is a list, not a vector
          (and
            current
            (z/list? current))
          {:error "Multi-arity defn: use replace_form instead"}

          ;; Single-arity: current should be the arglist vector
          (and
            current
            (= :vector (z/tag current)))
          (if-let [body (z/right current)]
            body
            {:error "defn has no body expressions"})

          :else
          {:error "Unexpected defn structure"})))))

(defn replace-body
  "Replace the body of a single-arity defn form.

   Removes all existing body expressions and inserts the new content
   in their place. Preserves the defn name, docstring, metadata, and
   arglist.

   zloc: zipper positioned at the defn form
   content: string of the new body (one or more expressions)

   Returns modified root node, or {:error ...}."
  [zloc content]
  (let [body-start (defn-body-start zloc)]
    (if (:error body-start)
      body-start
      ;; Strategy:
      ;; 1. From body-start, remove all siblings to the right (other body forms)
      ;; 2. Replace the remaining body form with parsed new content
      (let [;; Remove all body forms after the first
            cleared   (loop [loc body-start]
                        (if-let [right (z/right loc)]
                          (recur (z/remove right))
                          loc))
            ;; Parse new content — wrap in (do ...) to handle multiple forms
            new-nodes (-> (str "(do " content ")")
                          z/of-string
                          z/down
                          z/right)
            ;; Replace the remaining body form with first new node
            result    (z/replace cleared (z/node new-nodes))]
        ;; If content has multiple forms, insert the rest
        (loop [loc       result
               remaining (z/right new-nodes)]
          (if remaining (recur (z/insert-right loc (z/node remaining)) (z/right remaining)) (z/root loc)))))))

;; ── add_form_before / add_form_after ──────────────────────────

(defn add-form-before
  "Insert new source text as a form before the target form.

   Adds appropriate newlines for separation.
   Returns the root node."
  [zloc content]
  (-> zloc
      (z/insert-left (n/coerce (z/node (z/of-string content))))
      (z/insert-left (n/newlines 2))
      z/root))

(defn add-form-after
  "Insert new source text as a form after the target form.

   Returns the root node."
  [zloc content]
  (-> zloc
      (z/insert-right (n/newlines 2))
      (z/insert-right (n/coerce (z/node (z/of-string content))))
      z/root))

;; ── delete_form ───────────────────────────────────────────────

(defn delete-form
  "Remove the target form and its surrounding whitespace.
   Returns the root node."
  [zloc]
  (-> zloc
      z/remove
      z/root))

;; ── add_require ───────────────────────────────────────────────

(defn- parse-require-ns
  "Extract the namespace symbol from a require spec string.
   e.g. 'clojure.string :as str' → 'clojure.string'
        '[my.app.db :as db]'     → 'my.app.db'"
  [require-spec]
  (let [cleaned (-> require-spec
                    (clojure.string/replace #"^\[" "")
                    (clojure.string/replace #"\]$" "")
                    clojure.string/trim)]
    (first (clojure.string/split cleaned #"\s+"))))

(defn- require-already-present?
  "Check if a namespace is already in the :require vector."
  [require-zloc ns-str]
  (let [ns-sym (symbol ns-str)]
    (loop [child (z/down require-zloc)]
      (when child
        (let [sexpr (try (z/sexpr child) (catch Exception _ nil))]
          (cond
            ;; Bare symbol: [clojure.string]
            (= ns-sym sexpr)
            true
            ;; Vector: [clojure.string :as str]
            (and
              (vector? sexpr)
              (= ns-sym (first sexpr)))
            true
            :else
            (recur (z/right child))))))))

(defn add-require
  "Add a :require clause to the ns form.

   ns-zloc: zipper positioned at the (ns ...) form
   require-spec: string like 'clojure.string :as str' or
                 '[my.app.db :as db]'

   Idempotent: no-op if the namespace is already required.
   Returns the root node."
  [ns-zloc require-spec]
  (let [ns-str    (parse-require-ns require-spec)
        ;; Normalize the spec to always be a vector form
        spec-str  (if (clojure.string/starts-with? require-spec "[") require-spec (str "[" require-spec "]"))
        spec-node (z/node (z/of-string spec-str))]
    ;; Navigate into ns form and find :require
    (let [inner (z/down ns-zloc)]
      (loop [child (z/right inner)] ;; skip 'ns symbol
        (if (nil? child)
          ;; No :require found — insert one as last child of ns form
          (let [require-form (z/node (z/of-string (str "(:require\n    " spec-str ")")))]
            (-> ns-zloc
                (z/append-child (n/newlines 1))
                (z/append-child require-form)
                z/root))
          (if (and
                (z/list? child)
                (let [fc (z/down child)]
                  (and
                    fc
                    (= :require (z/sexpr fc)))))
            ;; Found (:require ...) form
            (if (require-already-present? child ns-str)
              ;; Already present — return unchanged
              (z/root ns-zloc)
              ;; Append new spec
              (-> child
                  (z/append-child (n/newlines 1))
                  (z/append-child (n/spaces 4))
                  (z/append-child (n/coerce spec-node))
                  z/up
                  z/root))
            (recur (z/right child))))))))

;; ── replace_ns ────────────────────────────────────────────────

(defn replace-ns
  "Replace the entire (ns ...) form.
   Returns the root node."
  [ns-zloc content]
  (-> ns-zloc
      (z/replace (n/coerce (z/node (z/of-string content))))
      z/root))

;; ── append_to_ns (append to file) ────────────────────────────

(defn append-to-file
  "Append a new form at the end of a file.

   root-zloc: zipper at file root (first top-level form)
   content: string of the new form

   Returns the root node."
  [root-zloc content]
  ;; Navigate to last top-level form
  (let [last-form (loop [loc root-zloc]
                    (if-let [right (z/right loc)]
                      (recur right)
                      loc))]
    (-> last-form
        (z/insert-right (n/newlines 2))
        (z/insert-right (n/coerce (z/node (z/of-string content))))
        z/root)))
