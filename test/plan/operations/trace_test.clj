(ns plan.operations.trace-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [failjure.core :as f]
   [plan.main :as main]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.operations.trace :as ops]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

;; -----------------------------------------------------------------------------
;; add-trace Tests
;; -----------------------------------------------------------------------------

(deftest add-trace-test
  (main/create-schema! helper/*conn*)

  (testing "creates trace with valid params"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          result (ops/add-trace helper/*conn*
                                {:task-id (:id t)
                                 :trace-type "thought"
                                 :content "Thinking about the problem"})]
      (is (f/ok? result))
      (is (number? (:id result)))
      (is (= "thought" (:trace_type result)))
      (is (= 1 (:sequence_num result)))))

  (testing "increments sequence number"
    (let [p (plan/create helper/*conn* "seq-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)]
      (ops/add-trace helper/*conn* {:task-id (:id t)
                                    :trace-type "thought"
                                    :content "First"})
      (let [result (ops/add-trace helper/*conn*
                                  {:task-id (:id t)
                                   :trace-type "action"
                                   :content "Second"})]
        (is (= 2 (:sequence_num result))))))

  (testing "accepts all valid trace types"
    (let [p (plan/create helper/*conn* "types-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)]
      (doseq [trace-type ["thought" "action" "observation" "reflection"]]
        (let [result (ops/add-trace helper/*conn*
                                    {:task-id (:id t)
                                     :trace-type trace-type
                                     :content (str "Content for " trace-type)})]
          (is (f/ok? result) (str "Failed for type: " trace-type))))))

  (testing "fails with invalid trace type"
    (let [p (plan/create helper/*conn* "invalid-type-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          result (ops/add-trace helper/*conn*
                                {:task-id (:id t)
                                 :trace-type "invalid"
                                 :content "Content"})]
      (is (f/failed? result))
      (is (re-find #"Invalid trace type" (f/message result)))))

  (testing "fails when task-id missing"
    (let [result (ops/add-trace helper/*conn*
                                {:trace-type "thought"
                                 :content "Content"})]
      (is (f/failed? result))
      (is (re-find #"Missing required parameters" (f/message result)))))

  (testing "fails when trace-type missing"
    (let [p (plan/create helper/*conn* "missing-type-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          result (ops/add-trace helper/*conn*
                                {:task-id (:id t)
                                 :content "Content"})]
      (is (f/failed? result))
      (is (re-find #"Missing required parameters" (f/message result)))))

  (testing "fails when content missing"
    (let [p (plan/create helper/*conn* "missing-content-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          result (ops/add-trace helper/*conn*
                                {:task-id (:id t)
                                 :trace-type "thought"})]
      (is (f/failed? result))
      (is (re-find #"Missing required parameters" (f/message result)))))

  (testing "fails when task does not exist"
    (let [result (ops/add-trace helper/*conn*
                                {:task-id 999
                                 :trace-type "thought"
                                 :content "Content"})]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; get-task-traces Tests
;; -----------------------------------------------------------------------------

(deftest get-task-traces-test
  (main/create-schema! helper/*conn*)

  (testing "returns traces for task"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)]
      (ops/add-trace helper/*conn* {:task-id (:id t) :trace-type "thought" :content "First"})
      (ops/add-trace helper/*conn* {:task-id (:id t) :trace-type "action" :content "Second"})
      (let [result (ops/get-task-traces helper/*conn* (:id t))]
        (is (f/ok? result))
        (is (= 2 (count result)))
        (is (= ["thought" "action"] (map :trace_type result))))))

  (testing "returns empty for task with no traces"
    (let [p (plan/create helper/*conn* "empty-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          result (ops/get-task-traces helper/*conn* (:id t))]
      (is (f/ok? result))
      (is (empty? result))))

  (testing "fails when task does not exist"
    (let [result (ops/get-task-traces helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; get-plan-traces Tests
;; -----------------------------------------------------------------------------

(deftest get-plan-traces-test
  (main/create-schema! helper/*conn*)

  (testing "returns all traces for plan"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          t1 (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "task-2" nil nil nil)]
      (ops/add-trace helper/*conn* {:task-id (:id t1) :trace-type "thought" :content "T1"})
      (ops/add-trace helper/*conn* {:task-id (:id t2) :trace-type "action" :content "T2"})
      (let [result (ops/get-plan-traces helper/*conn* (:id p))]
        (is (f/ok? result))
        (is (= 2 (count result))))))

  (testing "fails when plan does not exist"
    (let [result (ops/get-plan-traces helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; delete-task-traces Tests
;; -----------------------------------------------------------------------------

(deftest delete-task-traces-test
  (main/create-schema! helper/*conn*)

  (testing "deletes traces for task"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)]
      (ops/add-trace helper/*conn* {:task-id (:id t) :trace-type "thought" :content "First"})
      (ops/add-trace helper/*conn* {:task-id (:id t) :trace-type "action" :content "Second"})
      (let [result (ops/delete-task-traces helper/*conn* (:id t))]
        (is (f/ok? result))
        (is (true? (:deleted result)))
        ;; Verify deleted
        (is (empty? (ops/get-task-traces helper/*conn* (:id t)))))))

  (testing "fails when task does not exist"
    (let [result (ops/delete-task-traces helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; delete-plan-traces Tests
;; -----------------------------------------------------------------------------

(deftest delete-plan-traces-test
  (main/create-schema! helper/*conn*)

  (testing "deletes all traces for plan"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)]
      (ops/add-trace helper/*conn* {:task-id (:id t) :trace-type "thought" :content "First"})
      (let [result (ops/delete-plan-traces helper/*conn* (:id p))]
        (is (f/ok? result))
        (is (true? (:deleted result)))
        ;; Verify deleted
        (is (empty? (ops/get-plan-traces helper/*conn* (:id p)))))))

  (testing "fails when plan does not exist"
    (let [result (ops/delete-plan-traces helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))
