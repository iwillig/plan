(ns plan.main-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.db :as db]
   [plan.main :as main]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest create-schema-test
  (testing "creates all tables, indexes, and FTS structures"
    (main/create-schema! helper/*conn*)
    (let [tables (db/execute! helper/*conn* {:select [:name] :from :sqlite_master :where [:= :type "table"] :order-by [:name]})
          indexes (db/execute! helper/*conn* {:select [:name] :from :sqlite_master :where [:and [:= :type "index"] [:like :name "idx_%"]] :order-by [:name]})
          triggers (db/execute! helper/*conn* {:select [:name] :from :sqlite_master :where [:= :type "trigger"] :order-by [:name]})
          table-names (set (map :name tables))]
      ;; Check main tables exist
      (is (contains? table-names "plans"))
      (is (contains? table-names "tasks"))
      (is (contains? table-names "facts"))
      ;; Check FTS tables exist
      (is (contains? table-names "plans_fts"))
      (is (contains? table-names "tasks_fts"))
      (is (contains? table-names "facts_fts"))
      ;; Check indexes (includes unique constraints for name fields)
      (is (= ["idx_facts_plan_id" "idx_facts_plan_name" "idx_tasks_parent_id" "idx_tasks_plan_id" "idx_tasks_plan_name"]
             (map :name indexes)))
      ;; Check triggers exist
      (is (= ["facts_ad" "facts_ai" "facts_au" "plans_ad" "plans_ai" "plans_au" "tasks_ad" "tasks_ai" "tasks_au"]
             (map :name triggers))))))

(deftest schema-is-isolated-test
  (testing "each test gets a fresh database"
    (let [tables (db/execute! helper/*conn* {:select [:name] :from :sqlite_master :where [:= :type "table"]})]
      (is (empty? (filter #(not= "sqlite_sequence" %) (map :name tables)))))))

(deftest fts-search-test
  (testing "FTS search works with triggers"
    (main/create-schema! helper/*conn*)
    ;; Insert test data using model functions
    (let [plan (plan/create helper/*conn* "test-plan" "Test plan" "This is a test plan content")]
      (task/create helper/*conn* (:id plan) "test-task" "Test task" "This is a test task" nil)
      (fact/create helper/*conn* (:id plan) "test-fact" "Test fact" "This is a test fact"))
    ;; Search should find results
    (let [plan-results (db/search-plans helper/*conn* "test")
          task-results (db/search-tasks helper/*conn* "test")
          fact-results (db/search-facts helper/*conn* "test")]
      (is (= 1 (count plan-results)))
      (is (= 1 (count task-results)))
      (is (= 1 (count fact-results)))
      (is (= "test-plan" (:name (first plan-results))))
      (is (= "test-task" (:name (first task-results))))
      (is (= "test-fact" (:name (first fact-results)))))))

(deftest fts-highlight-test
  (testing "FTS highlighting works"
    (main/create-schema! helper/*conn*)
    (plan/create helper/*conn* "project-planning" "Project planning" "Content about planning")
    (let [highlighted (db/highlight-plans helper/*conn* "plan")]
      (is (= 1 (count highlighted)))
      (is (str/includes? (:description_highlight (first highlighted)) "<b>"))
      (is (str/includes? (:description_highlight (first highlighted)) "</b>")))))

(deftest fts-prefix-matching-test
  (testing "FTS prefix matching works"
    (main/create-schema! helper/*conn*)
    (plan/create helper/*conn* "planning-project" "Planning project" "Content")
    ;; Search for "plan" should match "planning"
    (let [results (db/search-plans helper/*conn* "plan")]
      (is (= 1 (count results)))
      (is (= "planning-project" (:name (first results)))))))

;; Command function tests

(deftest plan-create-test
  (testing "plan/create creates a plan and returns it"
    (main/create-schema! helper/*conn*)
    (let [result (plan/create helper/*conn* "test-plan" "Test plan" "Test content")]
      (is (some? result))
      (is (= "test-plan" (:name result)))
      (is (= "Test plan" (:description result)))
      (is (= "Test content" (:content result)))
      (is (number? (:id result)))
      (is (= false (:completed result))))))

(deftest plan-create-with-nil-content-test
  (testing "plan/create handles nil description and content"
    (main/create-schema! helper/*conn*)
    (let [result (plan/create helper/*conn* "test-plan" nil nil)]
      (is (some? result))
      (is (= "test-plan" (:name result)))
      (is (nil? (:description result)))
      (is (nil? (:content result))))))

(deftest task-create-test
  (testing "task/create creates a task and returns it"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan)
          result (task/create helper/*conn* plan-id "test-task" "Test task" "Task content" nil)]
      (is (some? result))
      (is (= "test-task" (:name result)))
      (is (= "Test task" (:description result)))
      (is (= "Task content" (:content result)))
      (is (= plan-id (:plan_id result)))
      (is (nil? (:parent_id result)))
      (is (= false (:completed result))))))

(deftest task-create-with-parent-test
  (testing "task/create creates a task with parent"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan)
          parent (task/create helper/*conn* plan-id "parent-task" "Parent task" nil nil)
          parent-id (:id parent)
          child (task/create helper/*conn* plan-id "child-task" "Child task" nil parent-id)]
      (is (= parent-id (:parent_id child))))))

(deftest plan-show-test
  (testing "plan-show returns plan with tasks and facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan" "Test plan" "Plan content")
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "task-1" "Task 1" "Task content" nil)
      (task/create helper/*conn* plan-id "task-2" "Task 2" nil nil)
      (fact/create helper/*conn* plan-id "fact-1" "Fact 1" "Fact content")
      ;; Test using model functions to retrieve data
      (let [result-plan (plan/get-by-id helper/*conn* plan-id)
            result-tasks (task/get-by-plan helper/*conn* plan-id)
            result-facts (fact/get-by-plan helper/*conn* plan-id)]
        (is (some? result-plan))
        (is (= 2 (count result-tasks)))
        (is (= 1 (count result-facts)))))))

(deftest plan-update-test
  (testing "plan-update updates plan fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "original" "Original" "Original content")
          plan-id (:id plan)]
      ;; Update description
      (plan/update helper/*conn* plan-id {:description "Updated"})
      (let [updated (plan/get-by-id helper/*conn* plan-id)]
        (is (= "Updated" (:description updated)))
        (is (= "Original content" (:content updated))))
      ;; Update completed status
      (plan/update helper/*conn* plan-id {:completed true})
      (let [updated (plan/get-by-id helper/*conn* plan-id)]
        (is (= true (:completed updated)))))))

(deftest task-update-test
  (testing "task-update updates task fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan)
          task-item (task/create helper/*conn* plan-id "original-task" "Original task" "Original content" nil)
          task-id (:id task-item)]
      ;; Update description
      (task/update helper/*conn* task-id {:description "Updated task"})
      (let [updated (task/get-by-id helper/*conn* task-id)]
        (is (= "Updated task" (:description updated))))
      ;; Update completed status
      (task/update helper/*conn* task-id {:completed true})
      (let [updated (task/get-by-id helper/*conn* task-id)]
        (is (= true (:completed updated))))
      ;; Move to different plan
      (let [plan2 (plan/create helper/*conn* "another-plan" "Another plan" nil)
            plan2-id (:id plan2)]
        (task/update helper/*conn* task-id {:plan_id plan2-id})
        (let [updated (task/get-by-id helper/*conn* task-id)]
          (is (= plan2-id (:plan_id updated))))))))

(deftest plan-delete-test
  (testing "plan-delete removes plan and associated tasks/facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "plan-to-delete" "Plan to delete" nil)
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "task-1" "Task 1" nil nil)
      (task/create helper/*conn* plan-id "task-2" "Task 2" nil nil)
      (fact/create helper/*conn* plan-id "fact-to-delete" "Fact to delete" nil)
      ;; Delete using model functions
      (task/delete-by-plan helper/*conn* plan-id)
      (fact/delete-by-plan helper/*conn* plan-id)
      (plan/delete helper/*conn* plan-id)
      ;; Verify deletion
      (is (nil? (plan/get-by-id helper/*conn* plan-id)))
      (is (empty? (task/get-by-plan helper/*conn* plan-id)))
      (is (empty? (fact/get-by-plan helper/*conn* plan-id))))))

(deftest task-delete-test
  (testing "task-delete removes task"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan)
          task-item (task/create helper/*conn* plan-id "task-to-delete" "Task to delete" nil nil)
          task-id (:id task-item)]
      ;; Delete using model function
      (task/delete helper/*conn* task-id)
      ;; Verify deletion
      (is (nil? (task/get-by-id helper/*conn* task-id))))))

(deftest plan-list-test
  (testing "plan-list returns all plans ordered by created_at desc, then id desc"
    (main/create-schema! helper/*conn*)
    (plan/create helper/*conn* "plan-1" "Plan 1" nil)
    (Thread/sleep 10) ;; Note: SQLite datetime has 1-second resolution
    (plan/create helper/*conn* "plan-2" "Plan 2" nil)
    (Thread/sleep 10)
    (plan/create helper/*conn* "plan-3" "Plan 3" nil)
    (let [plans (plan/get-all helper/*conn*)]
      (is (= 3 (count plans)))
      (is (= "plan-3" (:name (first plans))))
      (is (= "plan-1" (:name (last plans)))))))

(deftest task-list-test
  (testing "task-list returns tasks for a plan ordered by created_at desc, then id desc"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "task-1" "Task 1" nil nil)
      (Thread/sleep 10) ;; Note: SQLite datetime has 1-second resolution
      (task/create helper/*conn* plan-id "task-2" "Task 2" nil nil)
      (Thread/sleep 10)
      (task/create helper/*conn* plan-id "task-3" "Task 3" nil nil)
      (let [tasks (task/get-by-plan helper/*conn* plan-id)]
        (is (= 3 (count tasks)))
        (is (= "task-3" (:name (first tasks))))
        (is (= "task-1" (:name (last tasks)))))))

(deftest search-test
  (testing "search finds matches across plans, tasks, and facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "searchable-plan" "Searchable plan" "Plan content")]
      (task/create helper/*conn* (:id plan) "searchable-task" "Searchable task" "Task content" nil)
      (fact/create helper/*conn* (:id plan) "searchable-fact" "Searchable fact" "Fact content"))
    ;; Search for "searchable" should find all three
    (let [plan-results (db/search-plans helper/*conn* "searchable")
          task-results (db/search-tasks helper/*conn* "searchable")
          fact-results (db/search-facts helper/*conn* "searchable")]
      (is (= 1 (count plan-results)))
      (is (= 1 (count task-results)))
      (is (= 1 (count fact-results))))))

(deftest cli-definition-test
  (testing "cli-definition has correct structure"
    (is (= "plan" (:app-name main/cli-definition)))
    (is (= "0.1.0" (:version main/cli-definition)))
    (is (some? (:description main/cli-definition)))
    (is (seq (:subcommands main/cli-definition)))
    ;; Check for expected top-level commands
    (let [commands (set (map :command (:subcommands main/cli-definition)))]
      (is (contains? commands "init"))
      (is (contains? commands "plan"))
      (is (contains? commands "task"))
      (is (contains? commands "search"))
      (is (contains? commands "new")))))

(deftest new-plan-file-test
  (testing "new-plan-file creates a markdown file with template"
    (let [temp-file (str "/tmp/test-new-plan-" (System/currentTimeMillis) ".md")]
      (try
        ;; Create the file
        (main/new-plan-file {:name "my-test-plan" :file temp-file :description "Test plan description"})
        ;; Verify file was created
        (is (.exists (java.io.File. temp-file)))
        ;; Verify content
        (let [content (slurp temp-file)]
          (is (str/includes? content "# my-test-plan"))
          (is (str/includes? content "Test plan description"))
          (is (str/includes? content "## Overview"))
          (is (str/includes? content "## Goals"))
          (is (str/includes? content "## Context"))
          (is (str/includes? content "## Approach"))
          (is (str/includes? content "## Discussion"))
          (is (str/includes? content "### Initial Request"))
          (is (str/includes? content "### Response")))
        (finally
          ;; Cleanup
          (.delete (java.io.File. temp-file))))))
  (testing "new-plan-file uses defaults when options not provided"
    (let [temp-file (str "/tmp/test-new-plan-default-" (System/currentTimeMillis) ".md")]
      (try
        ;; Create the file with minimal args
        (main/new-plan-file {:file temp-file})
        ;; Verify file was created with default name
        (is (.exists (java.io.File. temp-file)))
        (let [content (slurp temp-file)]
          (is (str/includes? content "# new-plan"))
          (is (str/includes? content "A plan for LLM collaboration")))
        (finally
          ;; Cleanup
          (.delete (java.io.File. temp-file)))))))
)
