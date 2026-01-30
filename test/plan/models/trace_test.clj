(ns plan.models.trace-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.main :as main]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.models.trace :as trace]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest create-test
  (testing "create returns a trace with generated fields"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan-data)
          task (task/create helper/*conn* plan-id "test-task" "Test task" nil nil)
          result (trace/create helper/*conn* {:plan-id plan-id
                                               :task-id (:id task)
                                               :trace-type "thought"
                                               :sequence-num 1
                                               :content "Test thought"})]
      (is (some? result))
      (is (= "thought" (:trace_type result)))
      (is (= "Test thought" (:content result)))
      (is (= 1 (:sequence_num result)))
      (is (= plan-id (:plan_id result)))
      (is (= (:id task) (:task_id result)))
      (is (number? (:id result))))))

(deftest get-next-sequence-test
  (testing "get-next-sequence returns 1 for empty plan"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "seq-test-plan" "Test plan" nil)]
      (is (= 1 (trace/get-next-sequence helper/*conn* (:id plan-data))))))

  (testing "get-next-sequence increments correctly"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "seq-increment-plan" "Test plan" nil)
          plan-id (:id plan-data)
          task (task/create helper/*conn* plan-id "test-task" "Test task" nil nil)]
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task) :trace-type "thought" :sequence-num 1 :content "First"})
      (is (= 2 (trace/get-next-sequence helper/*conn* plan-id)))
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task) :trace-type "action" :sequence-num 2 :content "Second"})
      (is (= 3 (trace/get-next-sequence helper/*conn* plan-id))))))

(deftest get-by-plan-test
  (testing "get-by-plan returns traces ordered by sequence"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan-data)
          task (task/create helper/*conn* plan-id "test-task" "Test task" nil nil)]
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task) :trace-type "thought" :sequence-num 2 :content "Second"})
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task) :trace-type "thought" :sequence-num 1 :content "First"})
      (let [traces (trace/get-by-plan helper/*conn* plan-id)]
        (is (= 2 (count traces)))
        (is (= "First" (:content (first traces))))
        (is (= "Second" (:content (second traces))))
        (is (= 1 (:sequence_num (first traces))))))))

(deftest get-by-task-test
  (testing "get-by-task returns traces for specific task"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan-data)
          task1 (task/create helper/*conn* plan-id "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* plan-id "task-2" "Task 2" nil nil)]
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task1) :trace-type "thought" :sequence-num 1 :content "Task 1 thought"})
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task2) :trace-type "thought" :sequence-num 1 :content "Task 2 thought"})
      (let [traces (trace/get-by-task helper/*conn* (:id task1))]
        (is (= 1 (count traces)))
        (is (= "Task 1 thought" (:content (first traces))))))))

(deftest delete-by-plan-test
  (testing "delete-by-plan removes all traces for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan-data)
          task (task/create helper/*conn* plan-id "test-task" "Test task" nil nil)]
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task) :trace-type "thought" :sequence-num 1 :content "First"})
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task) :trace-type "thought" :sequence-num 2 :content "Second"})
      (trace/delete-by-plan helper/*conn* plan-id)
      (is (empty? (trace/get-by-plan helper/*conn* plan-id))))))

(deftest delete-by-task-test
  (testing "delete-by-task removes all traces for a task"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan-data)
          task1 (task/create helper/*conn* plan-id "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* plan-id "task-2" "Task 2" nil nil)]
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task1) :trace-type "thought" :sequence-num 1 :content "Task 1"})
      (trace/create helper/*conn* {:plan-id plan-id :task-id (:id task2) :trace-type "thought" :sequence-num 1 :content "Task 2"})
      (trace/delete-by-task helper/*conn* (:id task1))
      (is (empty? (trace/get-by-task helper/*conn* (:id task1))))
      (is (= 1 (count (trace/get-by-task helper/*conn* (:id task2))))))))