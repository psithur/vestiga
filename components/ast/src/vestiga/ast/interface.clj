(ns vestiga.ast.interface
  (:require
    [vestiga.ast.applier :as applier]
    [vestiga.ast.editor :as editor]
    [vestiga.ast.resolver :as resolver]))

(defn apply-edits!
  "Apply a sequence of AST edit operations.
   See vestiga.ast.applier/apply-edits! for full documentation."
  [kondo-analysis project-root operations]
  (applier/apply-edits! kondo-analysis project-root operations))

(defn find-defn-by-name
  "Find a named top-level form in a zipper."
  [root-zloc sym-name]
  (resolver/find-defn-by-name root-zloc sym-name))

(defn find-ns-form
  "Find the (ns ...) form in a zipper."
  [root-zloc]
  (resolver/find-ns-form root-zloc))

(defn find-defmethod-by-dispatch
  "Find a defmethod by dispatch value."
  [root-zloc multi-name dispatch-val-str]
  (resolver/find-defmethod-by-dispatch root-zloc multi-name dispatch-val-str))

(defn replace-form
  "Replace the entire form at zloc with new source."
  [zloc content]
  (editor/replace-form zloc content))

(defn replace-body
  "Replace the body of a single-arity defn."
  [zloc content]
  (editor/replace-body zloc content))

(defn add-require
  "Add a :require clause to an ns form. Idempotent."
  [ns-zloc require-spec]
  (editor/add-require ns-zloc require-spec))
