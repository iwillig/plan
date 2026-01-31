(ns plan.operations.task-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [failjure.core :as f]
   [plan.main :as main]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.operations.task :as ops]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

;; -----------------------------------------------------------------------------
;; list-tasks Tests
;; -----------------------------------------------------------------------------

(deftest list-tasks-test
  (main/create-schema! helper/*conn*)

  (testing "returns tasks for plan"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)]
      (task/create helper/*conn* (:id p) "task-1" nil nil nil)
      (task/create helper/*conn* (:id p) "task-2" nil nil nil)
      (let [result (ops/list-tasks helper/*conn* (:id p))]
        (is (f/ok? result))
        (is (= 2 (count result)))
        (is (= #{"task-1" "task-2"} (set (map :name result)))))))

  (testing "returns empty for plan with no tasks"
    (let [p (plan/create helper/*conn* "empty-plan" "Test" nil)
          result (ops/list-tasks helper/*conn* (:id p))]
      (is (f/ok? result))
      (is (empty? result))))

  (testing "returns failure for non-existent plan"
    (let [result (ops/list-tasks helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; get-task Tests
;; -----------------------------------------------------------------------------

(deftest get-task-test
  (main/create-schema! helper/*conn*)

  (testing "returns task when found"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          created (task/create helper/*conn* (:id p) "my-task" "Description" "Content" nil)
          result (ops/get-task helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (= "my-task" (:name result)))))

  (testing "returns failure when not found"
    (let [result (ops/get-task helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; show-task Tests
;; -----------------------------------------------------------------------------

(deftest show-task-test
  (main/create-schema! helper/*conn*)

  (testing "returns task with dependencies"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          t1 (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "task-2" nil nil nil)
          t3 (task/create helper/*conn* (:id p) "task-3" nil nil nil)]
      ;; t2 blocks t1, t1 blocks t3
      (task/add-dependency helper/*conn* (:id t2) (:id t1))
      (task/add-dependency helper/*conn* (:id t1) (:id t3))
      (let [result (ops/show-task helper/*conn* (:id t1))]
        (is (f/ok? result))
        (is (= "task-1" (get-in result [:task :name])))
        (is (= 1 (count (:blocked-by result))))
        (is (= 1 (count (:blocks result)))))))

  (testing "returns failure when task not found"
    (let [result (ops/show-task helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; create-task Tests
;; -----------------------------------------------------------------------------

(deftest create-task-test
  (main/create-schema! helper/*conn*)

  (testing "creates task with all fields"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          result (ops/create-task helper/*conn*
                                  {:plan_id (:id p)
                                   :name "new-task"
                                   :description "A description"
                                   :content "The content"})]
      (is (f/ok? result))
      (is (number? (:id result)))
      (is (= "new-task" (:name result)))
      (is (= "A description" (:description result)))))

  (testing "creates task with parent"
    (let [p (plan/create helper/*conn* "test-plan-2" "Test" nil)
          parent (task/create helper/*conn* (:id p) "parent" nil nil nil)
          result (ops/create-task helper/*conn*
                                  {:plan_id (:id p)
                                   :name "child"
                                   :parent_id (:id parent)})]
      (is (f/ok? result))
      (is (= (:id parent) (:parent_id result)))))

  (testing "fails when plan_id missing"
    (let [result (ops/create-task helper/*conn* {:name "test"})]
      (is (f/failed? result))
      (is (re-find #"Missing required parameters" (f/message result)))))

  (testing "fails when name missing"
    (let [p (plan/create helper/*conn* "test-plan-3" "Test" nil)
          result (ops/create-task helper/*conn* {:plan_id (:id p)})]
      (is (f/failed? result))
      (is (re-find #"Missing required parameters" (f/message result)))))

  (testing "fails when plan does not exist"
    (let [result (ops/create-task helper/*conn*
                                  {:plan_id 999
                                   :name "test"})]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; update-task Tests
;; -----------------------------------------------------------------------------

(deftest update-task-test
  (main/create-schema! helper/*conn*)

  (testing "updates single field"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          created (task/create helper/*conn* (:id p) "original" "Original" nil nil)
          result (ops/update-task helper/*conn* (:id created) {:description "Updated"})]
      (is (f/ok? result))
      (is (= "Updated" (:description result)))
      (is (= "original" (:name result)))))

  (testing "updates multiple fields"
    (let [p (plan/create helper/*conn* "test-plan-2" "Test" nil)
          created (task/create helper/*conn* (:id p) "original" nil nil nil)
          result (ops/update-task helper/*conn* (:id created)
                                  {:name "updated-name"
                                   :description "New desc"
                                   :content "New content"})]
      (is (f/ok? result))
      (is (= "updated-name" (:name result)))
      (is (= "New desc" (:description result)))))

  (testing "fails when task not found"
    (let [result (ops/update-task helper/*conn* 999 {:name "New"})]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result)))))

  (testing "fails when no update fields provided"
    (let [p (plan/create helper/*conn* "test-plan-3" "Test" nil)
          created (task/create helper/*conn* (:id p) "test" nil nil nil)
          result (ops/update-task helper/*conn* (:id created) {})]
      (is (f/failed? result))
      (is (re-find #"No fields to update" (f/message result))))))

;; -----------------------------------------------------------------------------
;; delete-task Tests
;; -----------------------------------------------------------------------------

(deftest delete-task-test
  (main/create-schema! helper/*conn*)

  (testing "deletes existing task"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          created (task/create helper/*conn* (:id p) "to-delete" nil nil nil)
          result (ops/delete-task helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (true? (:deleted result)))
      ;; Verify actually deleted
      (is (nil? (task/get-by-id helper/*conn* (:id created))))))

  (testing "fails when task not found"
    (let [result (ops/delete-task helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; start-task Tests
;; -----------------------------------------------------------------------------

(deftest start-task-test
  (main/create-schema! helper/*conn*)

  (testing "starts task successfully"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          created (task/create helper/*conn* (:id p) "to-start" nil nil nil)
          result (ops/start-task helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (= "in_progress" (:status result)))))

  (testing "fails when task not found"
    (let [result (ops/start-task helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; complete-task Tests
;; -----------------------------------------------------------------------------

(deftest complete-task-test
  (main/create-schema! helper/*conn*)

  (testing "completes task successfully"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          created (task/create helper/*conn* (:id p) "to-complete" nil nil nil)]
      (task/start-task helper/*conn* (:id created))
      (let [result (ops/complete-task helper/*conn* (:id created))]
        (is (f/ok? result))
        (is (= "completed" (:status result))))))

  (testing "fails when task not found"
    (let [result (ops/complete-task helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; fail-task Tests
;; -----------------------------------------------------------------------------

(deftest fail-task-test
  (main/create-schema! helper/*conn*)

  (testing "fails task successfully"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          created (task/create helper/*conn* (:id p) "to-fail" nil nil nil)]
      (task/start-task helper/*conn* (:id created))
      (let [result (ops/fail-task helper/*conn* (:id created))]
        (is (f/ok? result))
        (is (= "failed" (:status result))))))

  (testing "fails when task not found"
    (let [result (ops/fail-task helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; add-dependency Tests
;; -----------------------------------------------------------------------------

(deftest add-dependency-test
  (main/create-schema! helper/*conn*)

  (testing "adds dependency successfully"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          t1 (task/create helper/*conn* (:id p) "blocker" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "blocked" nil nil nil)
          result (ops/add-dependency helper/*conn* (:id t1) (:id t2))]
      (is (f/ok? result))
      (is (:success result))))

  (testing "fails when blocking task not found"
    (let [p (plan/create helper/*conn* "test-plan-2" "Test" nil)
          t (task/create helper/*conn* (:id p) "task" nil nil nil)
          result (ops/add-dependency helper/*conn* 999 (:id t))]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result)))))

  (testing "fails when blocked task not found"
    (let [p (plan/create helper/*conn* "test-plan-3" "Test" nil)
          t (task/create helper/*conn* (:id p) "task" nil nil nil)
          result (ops/add-dependency helper/*conn* (:id t) 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result)))))

  (testing "fails when would create cycle"
    (let [p (plan/create helper/*conn* "test-plan-4" "Test" nil)
          t1 (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "task-2" nil nil nil)]
      (task/add-dependency helper/*conn* (:id t1) (:id t2))
      (let [result (ops/add-dependency helper/*conn* (:id t2) (:id t1))]
        (is (f/failed? result))
        (is (re-find #"cycle" (f/message result)))))))

;; -----------------------------------------------------------------------------
;; get-ready-tasks Tests
;; -----------------------------------------------------------------------------

(deftest get-ready-tasks-test
  (main/create-schema! helper/*conn*)

  (testing "returns ready tasks"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)]
      (task/create helper/*conn* (:id p) "task-1" nil nil nil)
      (task/create helper/*conn* (:id p) "task-2" nil nil nil)
      (let [result (ops/get-ready-tasks helper/*conn* (:id p))]
        (is (f/ok? result))
        (is (= 2 (count result))))))

  (testing "filters blocked tasks"
    (let [p (plan/create helper/*conn* "test-plan-2" "Test" nil)
          t1 (task/create helper/*conn* (:id p) "blocker" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "blocked" nil nil nil)]
      (task/add-dependency helper/*conn* (:id t1) (:id t2))
      (let [result (ops/get-ready-tasks helper/*conn* (:id p))]
        (is (f/ok? result))
        (is (= 1 (count result)))
        (is (= "blocker" (:name (first result)))))))

  (testing "fails when plan not found"
    (let [result (ops/get-ready-tasks helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; get-next-task Tests
;; -----------------------------------------------------------------------------

(deftest get-next-task-test
  (main/create-schema! helper/*conn*)

  (testing "returns highest priority ready task"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)]
      (task/create helper/*conn* (:id p) "low-priority" nil nil nil)
      (let [high (task/create helper/*conn* (:id p) "high-priority" nil nil nil)]
        (task/update helper/*conn* (:id high) {:priority 1})
        (let [result (ops/get-next-task helper/*conn* (:id p))]
          (is (f/ok? result))
          (is (= "high-priority" (:name result)))))))

  (testing "returns nil when no tasks ready"
    (let [p (plan/create helper/*conn* "empty-plan" "Test" nil)
          result (ops/get-next-task helper/*conn* (:id p))]
      (is (f/ok? result))
      (is (nil? result))))

  (testing "fails when plan not found"
    (let [result (ops/get-next-task helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; search-tasks Tests
;; -----------------------------------------------------------------------------

(deftest search-tasks-test
  (main/create-schema! helper/*conn*)

  (testing "finds matching tasks"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)]
      (task/create helper/*conn* (:id p) "important-task" "Key information" nil nil)
      (let [result (ops/search-tasks helper/*conn* "important")]
        (is (f/ok? result))
        (is (= 1 (count result)))
        (is (= "important-task" (:name (first result)))))))

  (testing "returns empty for no matches"
    (let [p (plan/create helper/*conn* "test-plan-2" "Test" nil)]
      (task/create helper/*conn* (:id p) "task" "Desc" nil nil)
      (let [result (ops/search-tasks helper/*conn* "nonexistent")]
        (is (f/ok? result))
        (is (empty? result)))))

  (testing "fails on empty query"
    (let [result (ops/search-tasks helper/*conn* "")]
      (is (f/failed? result))
      (is (re-find #"cannot be empty" (f/message result))))))
