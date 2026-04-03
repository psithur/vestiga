(ns vestiga.ast.applier
  (:require
    [clojure.java.io :as io]
    [malli.core :as m]
    [rewrite-clj.node :as n]
    [rewrite-clj.zip :as z]
    [vestiga.ast.editor :as editor]
    [vestiga.ast.resolver :as resolver]))

(def Operation
  [:map
   [:operation
    [:enum "replace_form" "replace_body"
     "add_form_before" "add_form_after" "delete_form"
     "add_require" "replace_ns" "append_to_ns"
     "replace_defmethod"]]
   [:target {:optional true} :string]
   [:content {:optional true} :string]
   [:file {:optional true} :string]])

(def EditRequest [:sequential Operation])

(defn- parse-defmethod-target
  "Split 'my.ns/dispatch :http' into ['dispatch' ':http']."
  [target]
  (let [space-idx (.indexOf ^String target " ")]
    (if (pos? space-idx) [(subs target 0 space-idx) (subs target (inc space-idx))] [target nil])))

(defn- resolve-file-for-op
  "Determine which file an operation targets.
   Returns a file path string or {:error ...}."
  [kondo-analysis project-root op]
  (if (:file op)
    ;; Explicit file path provided
    (let [path (if (.isAbsolute (java.io.File. ^String (:file op))) (:file op) (str project-root "/" (:file op)))]
      (if (.exists (io/file path)) path {:error (str "File not found: " (:file op))}))
    ;; Resolve from target
    (case (:operation op)
      ("replace_form" "replace_body" "add_form_before" "add_form_after" "delete_form")
      (let [qname (:target op)
            slash (.indexOf ^String qname "/")]
        (if (neg? slash)
          {:error (str "Invalid qualified name: " qname)}
          (let [ns-name  (subs qname 0 slash)
                resolved (resolver/resolve-ns-to-file kondo-analysis ns-name)]
            (if (:error resolved)
              resolved
              (let [path (if (.isAbsolute (java.io.File. ^String resolved)) resolved (str project-root "/" resolved))]
                path)))))

      "replace_defmethod"
      (let [[qname _] (parse-defmethod-target (:target op))
            slash     (.indexOf ^String qname "/")]
        (if (neg? slash)
          {:error (str "Invalid qualified name: " qname)}
          (let [ns-name  (subs qname 0 slash)
                resolved (resolver/resolve-ns-to-file kondo-analysis ns-name)]
            (if (:error resolved)
              resolved
              (let [path (if (.isAbsolute (java.io.File. ^String resolved)) resolved (str project-root "/" resolved))]
                path)))))

      ("add_require" "replace_ns")
      ;; These ops need a file — must be provided via :file or inferred from context
      (if-let [file (:file op)]
        (str project-root "/" file)
        {:error (str "Operation '" (:operation op) "' requires a :file parameter or a target namespace")})

      "append_to_ns"
      (let [ns-name  (:target op)
            resolved (resolver/resolve-ns-to-file kondo-analysis ns-name)]
        (if (:error resolved)
          resolved
          (let [path (if (.isAbsolute (java.io.File. ^String resolved)) resolved (str project-root "/" resolved))]
            path)))

      {:error (str "Unknown operation: " (:operation op))})))

(defn- dispatch-operation
  "Dispatch a single operation on a zipper.

   root-zloc - current file zipper (at first top-level form)
   op        - operation map

   Returns:
     {:root-node <new root node>
      :old-text  <replaced text>}
   or:
     {:error <message>}"
  [root-zloc op]
  (case (:operation op)
    "replace_form"      (let [sym-name (:target op)
                              resolved (resolver/find-defn-by-name root-zloc sym-name)]
                          (if (:error resolved)
                            resolved
                            {:old-text  (z/string resolved)
                             :root-node (editor/replace-form resolved (:content op))}))

    "replace_body"      (let [resolved (resolver/find-defn-by-name root-zloc (:target op))]
                          (if (:error resolved)
                            resolved
                            (let [result (editor/replace-body resolved (:content op))]
                              (if (:error result)
                                result
                                {:old-text  (z/string resolved)
                                 :root-node result}))))

    "add_form_before"   (let [resolved (resolver/find-defn-by-name root-zloc (:target op))]
                          (if (:error resolved)
                            resolved
                            {:old-text  nil
                             :root-node (editor/add-form-before resolved (:content op))}))

    "add_form_after"    (let [resolved (resolver/find-defn-by-name root-zloc (:target op))]
                          (if (:error resolved)
                            resolved
                            {:old-text  nil
                             :root-node (editor/add-form-after resolved (:content op))}))

    "delete_form"       (let [resolved (resolver/find-defn-by-name root-zloc (:target op))]
                          (if (:error resolved)
                            resolved
                            {:old-text  (z/string resolved)
                             :root-node (editor/delete-form resolved)}))

    "add_require"       (let [ns-zloc (resolver/find-ns-form root-zloc)]
                          (if (:error ns-zloc)
                            ns-zloc
                            {:old-text  (z/string ns-zloc)
                             :root-node (editor/add-require ns-zloc (:target op))}))

    "replace_ns"        (let [ns-zloc (resolver/find-ns-form root-zloc)]
                          (if (:error ns-zloc)
                            ns-zloc
                            {:old-text  (z/string ns-zloc)
                             :root-node (editor/replace-ns ns-zloc (:content op))}))

    "append_to_ns"      {:old-text  nil
                         :root-node (editor/append-to-file root-zloc (:content op))}

    "replace_defmethod" (let [[qname dispatch-val] (parse-defmethod-target (:target op))
                              ;; Extract just the symbol name from qualified name
                              sym-name (let [slash (.indexOf ^String qname "/")]
                                         (if (pos? slash) (subs qname (inc slash)) qname))
                              resolved (resolver/find-defmethod-by-dispatch root-zloc sym-name dispatch-val)]
                          (if (:error resolved)
                            resolved
                            {:old-text  (z/string resolved)
                             :root-node (editor/replace-form resolved (:content op))}))

    {:error (str "Unknown operation: " (:operation op))}))

(defn- node->zloc
  "Convert a root node back to a zipper for further operations."
  [root-node]
  (z/of-string (n/string root-node)))

(defn apply-edits!
  "Apply a sequence of AST edit operations.

   Parameters:
     kondo-analysis - current clj-kondo analysis map
     project-root   - project root directory path
     operations     - vector of operation maps (per §3.3 schema)

   Returns:
     {:success  true/false
      :results  [{:operation  <op-map>
                  :status     :ok | :error
                  :message    <string, present on error>
                  :file       <path>
                  :old-text   <original form text, for undo>}
                 ...]}"
  [kondo-analysis project-root operations]
  (if-not (m/validate EditRequest operations)
    {:success false
     :results [{:status  :error
                :message (str "Invalid operations: " (pr-str (m/explain EditRequest operations)))}]}
    ;; Group operations by resolved file
    (let [ops-with-files (mapv
                           (fn [op]
                             (let [file (resolve-file-for-op kondo-analysis project-root op)]
                               (assoc op ::resolved-file file)))
                           operations)
          ;; Check for file resolution errors
          file-errors    (filterv #(:error (::resolved-file %)) ops-with-files)]
      (if (seq file-errors)
        {:success false
         :results (mapv
                    (fn [op]
                      {:operation op
                       :status    :error
                       :message   (:error (::resolved-file op))})
                    file-errors)}
        ;; Group by file and apply
        (let [grouped (group-by ::resolved-file ops-with-files)
              results (atom [])
              success (atom true)]
          (doseq [[file-path file-ops] grouped]
            (try
              (let [root-zloc (z/of-file file-path {:track-position? true})]
                ;; Apply operations sequentially to this file's zipper
                (let [final-root (reduce
                                   (fn [current-zloc op]
                                     (let [result (dispatch-operation current-zloc op)]
                                       (if (:error result)
                                         (do (reset! success false)
                                             (swap! results conj
                                               {:operation (dissoc op ::resolved-file)
                                                :status    :error
                                                :message   (:error result)
                                                :file      file-path})
                                             ;; Continue with unchanged zipper
                                             current-zloc)
                                         (do (swap! results conj
                                               {:operation (dissoc op ::resolved-file)
                                                :status    :ok
                                                :file      file-path
                                                :old-text  (:old-text result)})
                                             ;; Convert new root node back to zipper for next op
                                             (node->zloc (:root-node result))))))
                                   root-zloc
                                   file-ops)]
                  ;; Write the final result back to disk
                  (spit file-path (z/root-string final-root))))
              (catch Exception e
                (reset! success false)
                (doseq [op file-ops]
                  (swap! results conj
                    {:operation (dissoc op ::resolved-file)
                     :status    :error
                     :message   (str "Failed to process " file-path ": " (.getMessage e))
                     :file      file-path})))))
          {:success @success
           :results @results})))))
