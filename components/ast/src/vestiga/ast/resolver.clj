(ns vestiga.ast.resolver
  (:require
    [rewrite-clj.zip :as z]))

(def ^:private def-forms
  "Set of symbols that define named top-level forms."
  #{'defn 'defn- 'def 'defonce 'defmacro 'defmulti 'defprotocol 'defrecord 'deftype 'defmethod})

(defn find-ns-form
  "Navigate to the (ns ...) form in a file's zipper.
   Returns the zipper positioned at the ns form, or {:error ...}."
  [root-zloc]
  (loop [loc root-zloc]
    (if (nil? loc)
      {:error "No (ns ...) form found in file"}
      (if (and
            (z/list? loc)
            (let [first-child (z/down loc)]
              (and
                first-child
                (= 'ns (z/sexpr first-child)))))
        loc
        (recur (z/right loc))))))

(defn find-defn-by-name
  "Navigate a root zipper to a named top-level form where the second
   element (the name) matches sym-name.

   Searches: defn, defn-, def, defonce, defmacro, defmulti,
   defprotocol, defrecord, deftype.

   Returns the zipper positioned at the form, or {:error ...}.
   For qualified names like 'my.ns/foo', extracts just the symbol part."
  [root-zloc sym-name]
  (let [;; Handle qualified names: extract symbol part after /
        target-name (let [slash-idx (.indexOf ^String sym-name "/")]
                      (if (pos? slash-idx) (subs sym-name (inc slash-idx)) sym-name))
        target-sym  (symbol target-name)]
    (loop [loc   root-zloc
           found []]
      (if (nil? loc)
        (if (empty? found)
          {:error (str "Symbol '" sym-name "' not found. " "Known top-level forms in file: " (pr-str (mapv str found)))}
          {:error (str "Symbol '" sym-name "' not found")})
        (if (and
              (z/list? loc)
              (let [fc (z/down loc)]
                (and
                  fc
                  (def-forms (z/sexpr fc))
                  ;; Skip defmethod — use find-defmethod-by-dispatch
                  (not= 'defmethod (z/sexpr fc))
                  (let [name-node (z/right fc)]
                    (and
                      name-node
                      (= target-sym (z/sexpr name-node)))))))
          loc
          (recur
            (z/right loc)
            (if (and
                  (z/list? loc)
                  (let [fc (z/down loc)]
                    (and
                      fc
                      (def-forms (z/sexpr fc))
                      (not= 'defmethod (z/sexpr fc)))))
              (conj
                found
                (some-> (z/down loc)
                        z/right
                        z/sexpr))
              found)))))))

(defn find-defmethod-by-dispatch
  "Navigate to a (defmethod name dispatch-val ...) form.

   Checks the second child matches multi-name and the third child
   (dispatch value) matches dispatch-val-str. Comparison is via
   pr-str of the sexpr to handle keywords, strings, and literals.

   Returns the zipper at the form, or {:error ...}."
  [root-zloc multi-name dispatch-val-str]
  (let [target-sym (symbol multi-name)]
    (loop [loc root-zloc]
      (if (nil? loc)
        {:error (str "defmethod '" multi-name " " dispatch-val-str "' not found")}
        (if (and
              (z/list? loc)
              (let [fc (z/down loc)]
                (and
                  fc
                  (= 'defmethod (z/sexpr fc))
                  (let [name-node (z/right fc)]
                    (and
                      name-node
                      (= target-sym (z/sexpr name-node))
                      (let [dv-node (z/right name-node)]
                        (and
                          dv-node
                          (= dispatch-val-str (pr-str (z/sexpr dv-node))))))))))
          loc
          (recur (z/right loc)))))))

(defn resolve-ns-to-file
  "Map a namespace name to a file path using clj-kondo namespace-definitions.
   Returns the filename string, or {:error ...}."
  [kondo-analysis ns-name]
  (let [ns-kw  (keyword ns-name)
        ns-def (first (filter #(= ns-kw (:name %)) (:namespace-definitions kondo-analysis)))]
    (if ns-def (:filename ns-def) {:error (str "Namespace '" ns-name "' not found in clj-kondo analysis")})))

(defn resolve-target
  "Resolve a qualified name to a file path and a zipper at the target form.

   Uses clj-kondo analysis to determine which file contains the symbol,
   then parses that file with rewrite-clj and navigates to the form.

   Parameters:
     kondo-analysis - clj-kondo analysis map
     project-root   - project root path
     qualified-name - e.g. \"my.app.core/handle-request\"

   Returns:
     {:file-path  \"src/my/app/core.clj\"
      :zloc       <zipper at the target form>
      :root-zloc  <zipper at file root>
      :form-text  \"(defn handle-request ...)\"}
   or:
     {:error \"Symbol 'my.ns/foo' not found\"}"
  [kondo-analysis project-root qualified-name]
  (let [slash-idx (.indexOf ^String qualified-name "/")]
    (if (neg? slash-idx)
      {:error (str "Invalid qualified name '" qualified-name "' — expected ns/name format")}
      (let [ns-name  (subs qualified-name 0 slash-idx)
            sym-name (subs qualified-name (inc slash-idx))
            ns-kw    (keyword ns-name)
            ;; Find the var definition in kondo analysis
            var-def  (first
                       (filter
                         #(and
                            (= ns-kw (:ns %))
                            (= (symbol sym-name) (:name %)))
                         (:var-definitions kondo-analysis)))]
        (if-not var-def
          ;; Provide helpful error with known symbols
          (let [known (->> (:var-definitions kondo-analysis)
                           (filter #(= ns-kw (:ns %)))
                           (mapv #(name (:name %))))]
            {:error (str
                      "Symbol '"
                      qualified-name
                      "' not found. "
                      (if (seq known)
                        (str "Known symbols in " ns-name ": " (pr-str known))
                        (str "Namespace " ns-name " has no var definitions")))})
          (let [file-path (:filename var-def)
                abs-path  (if (.isAbsolute (java.io.File. ^String file-path))
                            file-path
                            (str project-root "/" file-path))
                root-zloc (z/of-file abs-path {:track-position? true})
                target    (find-defn-by-name root-zloc sym-name)]
            (if (:error target)
              target
              {:file-path file-path
               :zloc      target
               :root-zloc root-zloc
               :form-text (z/string target)})))))))
