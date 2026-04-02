(ns vestiga.mcp.server-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [vestiga.mcp.server :as server]))

(deftest test-handle-initialize
  (testing "returns protocol version and capabilities"
    (let [response (server/handle-method
                     {:method "initialize"
                      :id     1
                      :params {}})]
      (is
        (= "2024-11-05" (:protocolVersion response)))
      (is
        (some? (:capabilities response)))
      (is
        (= "vestiga" (get-in response [:serverInfo :name]))))))

(deftest test-handle-tools-list
  (testing "returns tool definitions"
    (let [response (server/handle-method
                     {:method "tools/list"
                      :id     2})]
      (is
        (vector? (:tools response)))
      (is
        (pos? (count (:tools response))))
      (is
        (some #(= "search_code" (:name %)) (:tools response))))))

(deftest test-handle-unknown-method
  (testing "returns error for unknown method"
    (let [response (server/handle-method
                     {:method "unknown/method"
                      :id     3})]
      (is
        (some? (:error response)))
      (is
        (= -32601 (get-in response [:error :code]))))))

(deftest test-handle-notification
  (testing "returns nil for notifications"
    (is
      (nil? (server/handle-method {:method "notifications/initialized"})))))
