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
                     :parent_id nil
                     :description "Test"
                     :content "Content"
                     :completed false
                     :created_at "2024-01-01"
                     :updated_at "2024-01-01"})))
  (testing "Task schema allows parent_id"
    (is (m/validate task/Task
                    {:id 1
                     :plan_id 1
                     :parent_id 2
                     :description "Test"
                     :content nil
                     :completed true
                     :created_at nil
                     :updated_at nil}))))

(deftest create-test
  (testing "create returns a task with generated fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test plan" nil)
          result (task/create helper/*conn* (:id plan) "Test task" "Content" nil)]
      (is (some? result))
      (is (= "Test task" (:description result)))
      (is (= "Content" (:content result)))
      (is (= (:id plan) (:plan_id result)))
      (is (nil? (:parent_id result)))
      (is (number? (:id result)))
      (is (= false (:completed result)))))

  (testing "create with parent_id"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test plan" nil)
          parent (task/create helper/*conn* (:id plan) "Parent" nil nil)
          child (task/create helper/*conn* (:id plan) "Child" nil (:id parent))]
      (is (= (:id parent) (:parent_id child))))))

(deftest get-by-id-test
  (testing "get-by-id returns task when found"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          created (task/create helper/*conn* (:id plan) "Test task" nil nil)
          fetched (task/get-by-id helper/*conn* (:id created))]
      (is (= (:id created) (:id fetched)))
      (is (= "Test task" (:description fetched)))))

  (testing "get-by-id returns nil when not found"
    (main/create-schema! helper/*conn*)
    (is (nil? (task/get-by-id helper/*conn* 999)))))

(deftest get-by-plan-test
  (testing "get-by-plan returns tasks for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "Task 1" nil nil)
      (task/create helper/*conn* plan-id "Task 2" nil nil)
      (let [tasks (task/get-by-plan helper/*conn* plan-id)]
        (is (= 2 (count tasks))))))

  (testing "get-by-plan returns empty for plan with no tasks"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)]
      (is (empty? (task/get-by-plan helper/*conn* (:id plan)))))))

(deftest get-children-test
  (testing "get-children returns child tasks"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          parent (task/create helper/*conn* (:id plan) "Parent" nil nil)
          _ (task/create helper/*conn* (:id plan) "Child 1" nil (:id parent))
          _ (task/create helper/*conn* (:id plan) "Child 2" nil (:id parent))
          children (task/get-children helper/*conn* (:id parent))]
      (is (= 2 (count children)))
      (is (= #{"Child 1" "Child 2"} (set (map :description children)))))))

(deftest get-all-test
  (testing "get-all returns all tasks"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)]
      (task/create helper/*conn* (:id plan) "Task 1" nil nil)
      (task/create helper/*conn* (:id plan) "Task 2" nil nil)
      (is (= 2 (count (task/get-all helper/*conn*)))))))

(deftest update-test
  (testing "update modifies task fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          created (task/create helper/*conn* (:id plan) "Original" "Content" nil)
          updated (task/update helper/*conn* (:id created) {:description "Updated"})]
      (is (= "Updated" (:description updated)))
      (is (= "Content" (:content updated)))))

  (testing "update can move task to different plan"
    (main/create-schema! helper/*conn*)
    (let [plan1 (plan/create helper/*conn* "Plan 1" nil)
          plan2 (plan/create helper/*conn* "Plan 2" nil)
          task (task/create helper/*conn* (:id plan1) "Task" nil nil)
          updated (task/update helper/*conn* (:id task) {:plan_id (:id plan2)})]
      (is (= (:id plan2) (:plan_id updated)))))

  (testing "update can change parent"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          parent1 (task/create helper/*conn* (:id plan) "Parent 1" nil nil)
          parent2 (task/create helper/*conn* (:id plan) "Parent 2" nil nil)
          child (task/create helper/*conn* (:id plan) "Child" nil (:id parent1))
          updated (task/update helper/*conn* (:id child) {:parent_id (:id parent2)})]
      (is (= (:id parent2) (:parent_id updated)))))

  (testing "update returns nil for non-existent task"
    (main/create-schema! helper/*conn*)
    (is (nil? (task/update helper/*conn* 999 {:description "Test"})))))

(deftest delete-test
  (testing "delete removes task and returns true"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          created (task/create helper/*conn* (:id plan) "To delete" nil nil)]
      (is (task/delete helper/*conn* (:id created)))
      (is (nil? (task/get-by-id helper/*conn* (:id created))))))

  (testing "delete returns false for non-existent task"
    (main/create-schema! helper/*conn*)
    (is (false? (task/delete helper/*conn* 999)))))

(deftest delete-by-plan-test
  (testing "delete-by-plan removes all tasks for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "Task 1" nil nil)
      (task/create helper/*conn* plan-id "Task 2" nil nil)
      (is (= 2 (task/delete-by-plan helper/*conn* plan-id)))
      (is (empty? (task/get-by-plan helper/*conn* plan-id))))))

(deftest mark-completed-test
  (testing "mark-completed sets completed status"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          created (task/create helper/*conn* (:id plan) "Test" nil nil)
          completed (task/mark-completed helper/*conn* (:id created) true)]
      (is (= true (:completed completed))))))

(deftest search-test
  (testing "search finds matching tasks"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)]
      (task/create helper/*conn* (:id plan) "Important task" "Content" nil)
      (let [results (task/search helper/*conn* "task")]
        (is (= 1 (count results)))
        (is (= "Important task" (:description (first results))))))))


