(ns plan.models.task-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [malli.core :as m]
   [plan.main :as main]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest task-schema-test
  (testing "Task schema validates correct data"
    (is (m/validate task/Task
                    {:id 1
                     :plan_id 1
                     :name "test-task"
                     :parent_id nil
                     :description "Test"
                     :content "Content"
                     :completed false
                     :status "pending"
                     :priority 100
                     :acceptance_criteria nil
                     :status_changed_at nil
                     :created_at "2024-01-01"
                     :updated_at "2024-01-01"})))
  (testing "Task schema allows parent_id"
    (is (m/validate task/Task
                    {:id 1
                     :plan_id 1
                     :name "test-task"
                     :parent_id 2
                     :description nil
                     :content nil
                     :completed true
                     :status "completed"
                     :priority 50
                     :acceptance_criteria "All tests pass"
                     :status_changed_at "2024-01-01"
                     :created_at nil
                     :updated_at nil}))))

(deftest create-test
  (testing "create returns a task with generated fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan-create-1" "Test plan" nil)
          result (task/create helper/*conn* (:id plan) "test-task" "Test task" "Content" nil)]
      (is (some? result))
      (is (= "test-task" (:name result)))
      (is (= "Test task" (:description result)))
      (is (= "Content" (:content result)))
      (is (= (:id plan) (:plan_id result)))
      (is (nil? (:parent_id result)))
      (is (number? (:id result)))
      (is (= false (:completed result)))))

  (testing "create with parent_id"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan-create-2" "Test plan" nil)
          parent (task/create helper/*conn* (:id plan) "parent" "Parent" nil nil)
          child (task/create helper/*conn* (:id plan) "child" "Child" nil (:id parent))]
      (is (= (:id parent) (:parent_id child))))))

(deftest get-by-id-test
  (testing "get-by-id returns task when found"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test" "Test" nil)
          created (task/create helper/*conn* (:id plan) "test-task" "Test task" nil nil)
          fetched (task/get-by-id helper/*conn* (:id created))]
      (is (= (:id created) (:id fetched)))
      (is (= "test-task" (:name fetched)))
      (is (= "Test task" (:description fetched)))))

  (testing "get-by-id returns nil when not found"
    (main/create-schema! helper/*conn*)
    (is (nil? (task/get-by-id helper/*conn* 999)))))

(deftest get-by-plan-test
  (testing "get-by-plan returns tasks for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-with-tasks" "Test" nil)
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "task-1" "Task 1" nil nil)
      (task/create helper/*conn* plan-id "task-2" "Task 2" nil nil)
      (let [tasks (task/get-by-plan helper/*conn* plan-id)]
        (is (= 2 (count tasks))))))

  (testing "get-by-plan returns empty for plan with no tasks"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-no-tasks" "Test" nil)]
      (is (empty? (task/get-by-plan helper/*conn* (:id plan)))))))

(deftest get-children-test
  (testing "get-children returns child tasks"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-children" "Test" nil)
          parent (task/create helper/*conn* (:id plan) "parent" "Parent" nil nil)
          _ (task/create helper/*conn* (:id plan) "child-1" "Child 1" nil (:id parent))
          _ (task/create helper/*conn* (:id plan) "child-2" "Child 2" nil (:id parent))
          children (task/get-children helper/*conn* (:id parent))]
      (is (= 2 (count children)))
      (is (= #{"child-1" "child-2"} (set (map :name children)))))))

(deftest get-all-test
  (testing "get-all returns all tasks"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-all-tasks" "Test" nil)]
      (task/create helper/*conn* (:id plan) "task-1" "Task 1" nil nil)
      (task/create helper/*conn* (:id plan) "task-2" "Task 2" nil nil)
      (is (= 2 (count (task/get-all helper/*conn*)))))))

(deftest update-test
  (testing "update modifies task fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-update-1" "Test" nil)
          created (task/create helper/*conn* (:id plan) "original" "Original" "Content" nil)
          updated (task/update helper/*conn* (:id created) {:description "Updated"})]
      (is (= "Updated" (:description updated)))
      (is (= "Content" (:content updated)))))

  (testing "update can move task to different plan"
    (main/create-schema! helper/*conn*)
    (let [plan1 (plan/create helper/*conn* "plan-move-1" "Plan 1" nil)
          plan2 (plan/create helper/*conn* "plan-move-2" "Plan 2" nil)
          task-item (task/create helper/*conn* (:id plan1) "task" "Task" nil nil)
          updated (task/update helper/*conn* (:id task-item) {:plan_id (:id plan2)})]
      (is (= (:id plan2) (:plan_id updated)))))

  (testing "update can change parent"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-update-2" "Test" nil)
          parent1 (task/create helper/*conn* (:id plan) "parent-1" "Parent 1" nil nil)
          parent2 (task/create helper/*conn* (:id plan) "parent-2" "Parent 2" nil nil)
          child (task/create helper/*conn* (:id plan) "child" "Child" nil (:id parent1))
          updated (task/update helper/*conn* (:id child) {:parent_id (:id parent2)})]
      (is (= (:id parent2) (:parent_id updated)))))

  (testing "update returns nil for non-existent task"
    (main/create-schema! helper/*conn*)
    (is (nil? (task/update helper/*conn* 999 {:description "Test"})))))

(deftest delete-test
  (testing "delete removes task and returns true"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-delete" "Test" nil)
          created (task/create helper/*conn* (:id plan) "to-delete" "To delete" nil nil)]
      (is (task/delete helper/*conn* (:id created)))
      (is (nil? (task/get-by-id helper/*conn* (:id created))))))

  (testing "delete returns false for non-existent task"
    (main/create-schema! helper/*conn*)
    (is (false? (task/delete helper/*conn* 999)))))

(deftest delete-by-plan-test
  (testing "delete-by-plan removes all tasks for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-delete-by-plan" "Test" nil)
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "task-1" "Task 1" nil nil)
      (task/create helper/*conn* plan-id "task-2" "Task 2" nil nil)
      (is (= 2 (task/delete-by-plan helper/*conn* plan-id)))
      (is (empty? (task/get-by-plan helper/*conn* plan-id))))))

(deftest mark-completed-test
  (testing "mark-completed sets completed status"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-completed" "Test" nil)
          created (task/create helper/*conn* (:id plan) "test-task" "Test" nil nil)
          completed (task/mark-completed helper/*conn* (:id created) true)]
      (is (= true (:completed completed))))))

(deftest search-test
  (testing "search finds matching tasks"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-search" "Test" nil)]
      (task/create helper/*conn* (:id plan) "important-task" "Important task" "Content" nil)
      (let [results (task/search helper/*conn* "task")]
        (is (= 1 (count results)))
        (is (= "important-task" (:name (first results))))))))


