(ns sample.multimethods)

(defmulti dispatch "Dispatch on type." :type)

(defmethod dispatch :http [req] (str "HTTP: " (:url req)))

(defmethod dispatch :grpc [req] (str "GRPC: " (:service req)))

(defmethod dispatch :default [req] (str "Unknown: " (:type req)))
