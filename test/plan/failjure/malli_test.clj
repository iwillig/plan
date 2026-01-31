(ns plan.failjure.malli-test
  "Tests for Malli schemas for failjure types"
  (:require
   [clojure.test :refer [deftest is testing]]
   [failjure.core :as f]
   [malli.core :as m]
   [plan.failjure.malli :as fm]))

(deftest failure-schema-test
  (testing "validates actual Failure instances"
    (is (m/validate fm/Failure (f/fail "error")))
    (is (m/validate fm/Failure (f/fail "formatted %s" "error"))))

  (testing "validates Failures with additional fields"
    (let [failure (-> (f/fail "error")
                      (assoc :code 404)
                      (assoc :details {:info "extra"}))]
      (is (m/validate fm/Failure failure))))

  (testing "rejects non-Failure maps"
    (is (not (m/validate fm/Failure {:message "error"})))
    (is (not (m/validate fm/Failure {}))))

  (testing "rejects non-string messages"
    (is (not (m/validate fm/Failure (assoc (f/fail "test") :message 123)))))

  (testing "rejects non-maps"
    (is (not (m/validate fm/Failure "string")))
    (is (not (m/validate fm/Failure nil)))
    (is (not (m/validate fm/Failure 42)))))

(deftest value-schema-test
  (testing "validates Failure instances"
    (is (m/validate fm/Value (f/fail "error"))))

  (testing "validates success values"
    (is (m/validate fm/Value {:data "success"}))
    (is (m/validate fm/Value "string"))
    (is (m/validate fm/Value 42))
    (is (m/validate fm/Value nil))
    (is (m/validate fm/Value [])))

  (testing "validates any arbitrary value"
    (is (m/validate fm/Value {:complex {:nested {:data :structure}}}))))

(deftest helper-functions-test
  (testing "failure? predicate"
    (is (fm/failure? (f/fail "error")))
    (is (not (fm/failure? {:data :value})))
    (is (not (fm/failure? nil))))

  (testing "ok? predicate"
    (is (fm/ok? {:data :value}))
    (is (fm/ok? nil))
    (is (fm/ok? "string"))
    (is (not (fm/ok? (f/fail "error"))))))

(deftest schema-composition-test
  (testing "can use Failure in composed schemas"
    (let [ErrorResponse [:map
                         [:status :int]
                         [:error fm/Failure]]]
      (is (m/validate ErrorResponse
                      {:status 400
                       :error (f/fail "Bad request")}))
      (is (not (m/validate ErrorResponse
                           {:status 400
                            :error {:message "not a real failure"}})))))

  (testing "can use Value in composed schemas"
    (let [APIResponse [:map
                       [:status :int]
                       [:body fm/Value]]]
      ;; Success response
      (is (m/validate APIResponse
                      {:status 200
                       :body {:user "Alice"}}))
      ;; Error response
      (is (m/validate APIResponse
                      {:status 500
                       :body (f/fail "Server error")})))))

(deftest registry-test
  (testing "registry contains all expected keys"
    (is (contains? fm/failjure-registry :failjure/Failure))
    (is (contains? fm/failjure-registry :failjure/Value))
    (is (contains? fm/failjure-registry :failjure/fail))
    (is (contains? fm/failjure-registry :failjure/failed?))
    (is (contains? fm/failjure-registry :failjure/message)))

  (testing "can use registry with schema references"
    (let [my-registry (merge (m/default-schemas) fm/failjure-registry)
          schema [:map
                  [:result :failjure/Value]
                  [:error {:optional true} :failjure/Failure]]]
      ;; Success case
      (is (m/validate schema
                      {:result {:data "success"}}
                      {:registry my-registry}))
      ;; Error case
      (is (m/validate schema
                      {:result (f/fail "error")
                       :error (f/fail "details")}
                      {:registry my-registry})))))

(deftest real-world-usage-test
  (testing "validating API responses"
    (let [OperationResult [:map
                           [:success :boolean]
                           [:data fm/Value]
                           [:timestamp :string]]]
      ;; Successful operation
      (is (m/validate OperationResult
                      {:success true
                       :data {:id 123 :name "Task"}
                       :timestamp "2024-01-01T00:00:00Z"}))
      
      ;; Failed operation
      (is (m/validate OperationResult
                      {:success false
                       :data (f/fail "Database connection failed")
                       :timestamp "2024-01-01T00:00:00Z"}))))

  (testing "validating operation results with optional errors"
    (let [TaskResult [:map
                      [:task [:maybe :map]]
                      [:error {:optional true} fm/Failure]]]
      ;; Success
      (is (m/validate TaskResult
                      {:task {:id 1 :name "Test"}}))
      
      ;; Failure with error
      (is (m/validate TaskResult
                      {:task nil
                       :error (f/fail "Task not found")}))
      
      ;; Failure without error field (still valid)
      (is (m/validate TaskResult
                      {:task nil}))))

  (testing "validating nested failures"
    (let [BatchResult [:map
                       [:results [:vector fm/Value]]]]
      (is (m/validate BatchResult
                      {:results [{:id 1 :status "ok"}
                                 (f/fail "Failed to process")
                                 {:id 3 :status "ok"}]})))))

(deftest error-messages-test
  (testing "Failure schema provides helpful error messages"
    (let [result (m/explain fm/Failure {:message 123})]
      (is (some? result))
      (is (contains? result :errors))))

  (testing "Value schema accepts anything"
    ;; Should never fail validation
    (is (nil? (m/explain fm/Value "anything")))
    (is (nil? (m/explain fm/Value (f/fail "error"))))))

(deftest function-schema-test
  (testing "fail-schema documents function signature"
    (is (some? fm/fail-schema))
    (is (vector? fm/fail-schema))
    (is (= :function (first fm/fail-schema))))

  (testing "failed?-schema documents function signature"
    (is (some? fm/failed?-schema))
    (is (vector? fm/failed?-schema))
    (is (= :=> (first fm/failed?-schema))))

  (testing "all function schemas are valid schema vectors"
    (doseq [[k schema] fm/failjure-registry
            :when (re-find #"^:failjure/(fail|ok|when|if|attempt)" (str k))]
      (is (vector? schema)
          (str "Schema for " k " should be a vector"))
      (is (contains? #{:=> :function} (first schema))
          (str "Schema for " k " should be a function schema")))))
