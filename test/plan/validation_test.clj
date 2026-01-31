(ns plan.validation-test
  "Tests for the Malli-Failjure bridge"
  (:require
   [clojure.test :refer [deftest is testing]]
   [failjure.core :as f]
   [plan.validation :as v]))

(def User
  "Test schema for a user"
  [:map
   [:name [:string {:min 3}]]
   [:age [:int {:min 0 :max 150}]]])

(deftest validate-or-fail-test
  (testing "returns data unchanged when validation succeeds"
    (let [valid-data {:name "Alice" :age 30}
          result (v/validate-or-fail User valid-data)]
      (is (= valid-data result))
      (is (not (f/failed? result)))))

  (testing "returns Failure when validation fails"
    (let [invalid-data {:name "Al" :age 200}
          result (v/validate-or-fail User invalid-data)]
      (is (f/failed? result))
      (is (string? (f/message result)))
      (is (contains? result :errors))))

  (testing "includes humanized errors in Failure"
    (let [result (v/validate-or-fail User {:name "Al" :age 200})]
      (is (= {:name ["should be at least 3 characters"]
              :age ["should be at most 150"]}
             (:errors result)))))

  (testing "message describes which fields failed"
    (let [result (v/validate-or-fail User {:name "Al" :age 200})]
      (is (= "Validation failed for fields: name, age"
             (f/message result)))))

  (testing "handles nil data"
    (let [result (v/validate-or-fail User nil)]
      (is (f/failed? result))
      (is (= ["invalid type"] (:errors result)))))

  (testing "handles missing required fields"
    (let [result (v/validate-or-fail User {})]
      (is (f/failed? result))
      (is (= {:name ["missing required key"]
              :age ["missing required key"]}
             (:errors result)))))

  (testing "handles partially invalid data"
    (let [result (v/validate-or-fail User {:name "Bob"})]
      (is (f/failed? result))
      (is (= {:age ["missing required key"]}
             (:errors result))))))

(deftest validation-errors-test
  (testing "extracts errors from a validation failure"
    (let [result (v/validate-or-fail User {:name "Al" :age 200})
          errors (v/validation-errors result)]
      (is (= {:name ["should be at least 3 characters"]
              :age ["should be at most 150"]}
             errors))))

  (testing "returns nil for valid data"
    (let [result (v/validate-or-fail User {:name "Alice" :age 30})
          errors (v/validation-errors result)]
      (is (nil? errors))))

  (testing "returns nil for non-failure values"
    (is (nil? (v/validation-errors {:some :data})))
    (is (nil? (v/validation-errors nil)))
    (is (nil? (v/validation-errors "string")))))

(deftest validation-failed?-test
  (testing "returns true for validation failures"
    (let [result (v/validate-or-fail User {:name "Al" :age 200})]
      (is (v/validation-failed? result))))

  (testing "returns false for valid data"
    (let [result (v/validate-or-fail User {:name "Alice" :age 30})]
      (is (not (v/validation-failed? result)))))

  (testing "returns false for non-validation failures"
    (let [other-failure (f/fail "Some other error")]
      (is (not (v/validation-failed? other-failure)))))

  (testing "returns false for non-failure values"
    (is (not (v/validation-failed? {:some :data})))
    (is (not (v/validation-failed? nil)))
    (is (not (v/validation-failed? "string")))))

(deftest integration-with-attempt-all-test
  (testing "works seamlessly with f/attempt-all"
    (let [create-user (fn [data]
                        (f/attempt-all
                         [validated (v/validate-or-fail User data)]
                         {:success true :user validated}))]

      ;; Valid data flows through
      (is (= {:success true :user {:name "Alice" :age 30}}
             (create-user {:name "Alice" :age 30})))

      ;; Invalid data short-circuits
      (let [result (create-user {:name "Al" :age 200})]
        (is (f/failed? result))
        (is (v/validation-failed? result)))))

  (testing "can chain multiple validations"
    (let [process (fn [user-data task-data]
                    (f/attempt-all
                     [user (v/validate-or-fail User user-data)
                      task (v/validate-or-fail
                            [:map [:title [:string {:min 1}]]]
                            task-data)]
                     {:user user :task task}))]

      ;; Both valid
      (is (= {:user {:name "Alice" :age 30}
              :task {:title "Do something"}}
             (process {:name "Alice" :age 30}
                      {:title "Do something"})))

      ;; First fails
      (let [result (process {:name "Al" :age 200}
                            {:title "Do something"})]
        (is (f/failed? result))
        (is (= "Validation failed for fields: name, age"
               (f/message result))))

      ;; Second fails
      (let [result (process {:name "Alice" :age 30}
                            {})]
        (is (f/failed? result))
        (is (= "Validation failed for fields: title"
               (f/message result)))))))

(deftest optional-fields-test
  (testing "handles optional fields correctly"
    (let [schema [:map
                  [:name [:string {:min 3}]]
                  [:email {:optional true} [:string]]]]

      ;; With optional field
      (is (= {:name "Alice" :email "alice@example.com"}
             (v/validate-or-fail schema {:name "Alice" :email "alice@example.com"})))

      ;; Without optional field
      (is (= {:name "Alice"}
             (v/validate-or-fail schema {:name "Alice"}))))))

(deftest nested-schema-test
  (testing "validates nested schemas"
    (let [schema [:map
                  [:name [:string]]
                  [:address [:map
                             [:city [:string]]
                             [:zip [:re #"\d{5}"]]]]]
          valid-data {:name "Alice"
                      :address {:city "Boston" :zip "02101"}}
          invalid-data {:name "Alice"
                        :address {:city "Boston" :zip "ABC"}}]

      (is (= valid-data (v/validate-or-fail schema valid-data)))

      (let [result (v/validate-or-fail schema invalid-data)]
        (is (f/failed? result))
        (is (contains? (:errors result) :address))))))
