(ns plan.models.task-v3-test
  "Tests for Phase 1 task enhancements: status, priority, dependencies"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.main :as main]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

;; -----------------------------------------------------------------------------
;; Task Status Tests

(deftest task-status-pending-test
  (testing "new tasks have pending status"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-status-pending" "Test" nil)
          task-data (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)]
      (is (= "pending" (:status task-data))))))

(deftest task-status-start-test
  (testing "start-task transitions to in_progress"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-status-start" "Test" nil)
          task-data (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          started (task/start-task helper/*conn* (:id task-data))]
      (is (= "in_progress" (:status started))))))

(deftest task-status-complete-test
  (testing "complete-task transitions to completed"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-status-complete" "Test" nil)
          task-data (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          completed (task/complete-task helper/*conn* (:id task-data))]
      (is (= "completed" (:status completed)))
      (is (= true (:completed completed))))))

(deftest task-status-fail-test
  (testing "fail-task transitions to failed"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-status-fail" "Test" nil)
          task-data (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          failed (task/fail-task helper/*conn* (:id task-data))]
      (is (= "failed" (:status failed))))))

;; -----------------------------------------------------------------------------
;; Task Priority Tests

(deftest task-priority-default-test
  (testing "tasks have default priority of 100"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-priority-default" "Test" nil)
          task-data (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)]
      (is (= 100 (:priority task-data))))))

(deftest task-priority-custom-test
  (testing "can create task with custom priority"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-priority-custom" "Test" nil)
          task-data (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil
                                 {:priority 10})]
      (is (= 10 (:priority task-data))))))

(deftest task-priority-update-test
  (testing "can update task priority"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-priority-update" "Test" nil)
          task-data (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          updated (task/update helper/*conn* (:id task-data) {:priority 50})]
      (is (= 50 (:priority updated))))))

;; -----------------------------------------------------------------------------
;; Task Dependency Tests

(deftest task-dependency-add-test
  (testing "can add dependency between tasks"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-dep-add" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)]
      (is (task/add-dependency helper/*conn* (:id task1) (:id task2))))))

(deftest task-dependency-blocking-test
  (testing "get-blocking-tasks returns blockers"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-dep-blocking" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          _ (task/add-dependency helper/*conn* (:id task1) (:id task2))
          blockers (task/get-blocking-tasks helper/*conn* (:id task2))]
      (is (= 1 (count blockers)))
      (is (= "task-1" (:name (first blockers)))))))

(deftest task-dependency-blocked-test
  (testing "get-blocked-tasks returns blocked tasks"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-dep-blocked" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          _ (task/add-dependency helper/*conn* (:id task1) (:id task2))
          blocked (task/get-blocked-tasks helper/*conn* (:id task1))]
      (is (= 1 (count blocked)))
      (is (= "task-2" (:name (first blocked)))))))

(deftest task-dependency-remove-test
  (testing "can remove dependency"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-dep-remove" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          _ (task/add-dependency helper/*conn* (:id task1) (:id task2))]
      (is (task/remove-dependency helper/*conn* (:id task1) (:id task2)))
      (is (empty? (task/get-blocking-tasks helper/*conn* (:id task2)))))))

;; -----------------------------------------------------------------------------
;; Ready Tasks Tests

(deftest get-ready-tasks-no-blockers-test
  (testing "tasks with no blockers are ready"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-ready-no-blockers" "Test" nil)
          _ (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          _ (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          ready (task/get-ready-tasks helper/*conn* (:id plan-data))]
      (is (= 2 (count ready))))))

(deftest get-ready-tasks-blocked-test
  (testing "blocked tasks are not ready"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-ready-blocked" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          _ (task/add-dependency helper/*conn* (:id task1) (:id task2))
          ready (task/get-ready-tasks helper/*conn* (:id plan-data))]
      (is (= 1 (count ready)))
      (is (= "task-1" (:name (first ready)))))))

(deftest get-ready-tasks-unblock-test
  (testing "task becomes ready when blocker completes"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-ready-unblock" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          _ (task/add-dependency helper/*conn* (:id task1) (:id task2))
          _ (task/complete-task helper/*conn* (:id task1))
          ready (task/get-ready-tasks helper/*conn* (:id plan-data))]
      (is (= 1 (count ready)))
      (is (= "task-2" (:name (first ready)))))))

(deftest get-ready-tasks-priority-test
  (testing "ready tasks are ordered by priority"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-ready-priority" "Test" nil)
          _ (task/create helper/*conn* (:id plan-data) "low-priority" "Low" nil nil {:priority 100})
          _ (task/create helper/*conn* (:id plan-data) "high-priority" "High" nil nil {:priority 10})
          ready (task/get-ready-tasks helper/*conn* (:id plan-data))]
      (is (= "high-priority" (:name (first ready))))
      (is (= "low-priority" (:name (second ready)))))))

(deftest get-next-task-priority-test
  (testing "returns highest priority ready task"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-next-priority" "Test" nil)
          _ (task/create helper/*conn* (:id plan-data) "low-priority" "Low" nil nil {:priority 100})
          _ (task/create helper/*conn* (:id plan-data) "high-priority" "High" nil nil {:priority 10})
          next-task (task/get-next-task helper/*conn* (:id plan-data))]
      (is (= "high-priority" (:name next-task))))))

(deftest get-next-task-nil-test
  (testing "returns nil when no tasks ready"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-next-nil" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          _ (task/add-dependency helper/*conn* (:id task1) (:id task2))
          _ (task/start-task helper/*conn* (:id task1))
          next-task (task/get-next-task helper/*conn* (:id plan-data))]
      (is (nil? next-task)))))

;; -----------------------------------------------------------------------------
;; Cycle Detection Tests

(deftest cycle-detection-test
  (testing "direct cycle is detected"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-cycle-1" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          _ (task/add-dependency helper/*conn* (:id task1) (:id task2))]
      (is (task/has-cycle? helper/*conn* (:id task2) (:id task1)))))

  (testing "indirect cycle is detected"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-cycle-2" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          task3 (task/create helper/*conn* (:id plan-data) "task-3" "Task 3" nil nil)
          _ (task/add-dependency helper/*conn* (:id task1) (:id task2))
          _ (task/add-dependency helper/*conn* (:id task2) (:id task3))]
      (is (task/has-cycle? helper/*conn* (:id task3) (:id task1)))))

  (testing "no cycle when safe"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan-cycle-3" "Test" nil)
          task1 (task/create helper/*conn* (:id plan-data) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan-data) "task-2" "Task 2" nil nil)
          _task3 (task/create helper/*conn* (:id plan-data) "task-3" "Task 3" nil nil)]
      (is (not (task/has-cycle? helper/*conn* (:id task1) (:id task2)))))))
