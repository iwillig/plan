(ns plan.main-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.db :as db]
   [plan.main :as main]
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
      ;; Check indexes
      (is (= ["idx_facts_plan_id" "idx_tasks_parent_id" "idx_tasks_plan_id"]
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
    ;; Insert test data
    (db/execute! helper/*conn* {:insert-into :plans :columns [:description :content] :values [["Test plan" "This is a test plan content"]]})
    (db/execute! helper/*conn* {:insert-into :tasks :columns [:plan_id :description :content] :values [[1 "Test task" "This is a test task"]]})
    (db/execute! helper/*conn* {:insert-into :facts :columns [:plan_id :description :content] :values [[1 "Test fact" "This is a test fact"]]})
    ;; Search should find results
    (let [plan-results (db/search-plans helper/*conn* "test")
          task-results (db/search-tasks helper/*conn* "test")
          fact-results (db/search-facts helper/*conn* "test")]
      (is (= 1 (count plan-results)))
      (is (= 1 (count task-results)))
      (is (= 1 (count fact-results)))
      (is (= "Test plan" (:description (first plan-results))))
      (is (= "Test task" (:description (first task-results))))
      (is (= "Test fact" (:description (first fact-results)))))))

(deftest fts-highlight-test
  (testing "FTS highlighting works"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans :columns [:description :content] :values [["Project planning" "Content about planning"]]})
    (let [highlighted (db/highlight-plans helper/*conn* "plan")]
      (is (= 1 (count highlighted)))
      (is (str/includes? (:description_highlight (first highlighted)) "<b>"))
      (is (str/includes? (:description_highlight (first highlighted)) "</b>")))))

(deftest fts-prefix-matching-test
  (testing "FTS prefix matching works"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans :columns [:description :content] :values [["Planning project" "Content"]]})
    ;; Search for "plan" should match "planning"
    (let [results (db/search-plans helper/*conn* "plan")]
      (is (= 1 (count results)))
      (is (= "Planning project" (:description (first results)))))))

;; Command function tests

(deftest plan-create-test
  (testing "plan/create creates a plan and returns it"
    (main/create-schema! helper/*conn*)
    (let [result (plan/create helper/*conn* "Test plan" "Test content")]
      (is (some? result))
      (is (= "Test plan" (:description result)))
      (is (= "Test content" (:content result)))
      (is (number? (:id result)))
      (is (= false (:completed result))))))

(deftest plan-create-with-nil-content-test
  (testing "plan/create handles nil content"
    (main/create-schema! helper/*conn*)
    (let [result (plan/create helper/*conn* "Plan with no content" nil)]
      (is (some? result))
      (is (= "Plan with no content" (:description result)))
      (is (nil? (:content result))))))

(deftest task-create-test
  (testing "task/create creates a task and returns it"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test plan" nil)
          plan-id (:id plan)
          result (task/create helper/*conn* plan-id "Test task" "Task content" nil)]
      (is (some? result))
      (is (= "Test task" (:description result)))
      (is (= "Task content" (:content result)))
      (is (= plan-id (:plan_id result)))
      (is (nil? (:parent_id result)))
      (is (= false (:completed result))))))

(deftest task-create-with-parent-test
  (testing "task/create creates a task with parent"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test plan" nil)
          plan-id (:id plan)
          parent (task/create helper/*conn* plan-id "Parent task" nil nil)
          parent-id (:id parent)
          child (task/create helper/*conn* plan-id "Child task" nil parent-id)]
      (is (= parent-id (:parent_id child))))))

(deftest plan-show-test
  (testing "plan-show returns plan with tasks and facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test plan" "Plan content")
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "Task 1" "Task content" nil)
      (task/create helper/*conn* plan-id "Task 2" nil nil)
      (db/execute! helper/*conn* {:insert-into :facts
                                  :columns [:plan_id :description :content]
                                  :values [[plan-id "Fact 1" "Fact content"]]})
      ;; We can't easily test the CLI output, but we can test the underlying query
      (let [result-plan (db/execute-one! helper/*conn* {:select [:*] :from [:plans] :where [:= :id plan-id]})
            result-tasks (db/execute! helper/*conn* {:select [:*] :from [:tasks] :where [:= :plan_id plan-id]})
            result-facts (db/execute! helper/*conn* {:select [:*] :from [:facts] :where [:= :plan_id plan-id]})]
        (is (some? result-plan))
        (is (= 2 (count result-tasks)))
        (is (= 1 (count result-facts)))))))

(deftest plan-update-test
  (testing "plan-update updates plan fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Original" "Original content")
          plan-id (:id plan)]
      ;; Update description
      (db/execute! helper/*conn* {:update :plans
                                  :set {:description "Updated"}
                                  :where [:= :id plan-id]})
      (let [updated (db/execute-one! helper/*conn* {:select [:*] :from [:plans] :where [:= :id plan-id]})]
        (is (= "Updated" (:description updated)))
        (is (= "Original content" (:content updated))))
      ;; Update completed status
      (db/execute! helper/*conn* {:update :plans
                                  :set {:completed true}
                                  :where [:= :id plan-id]})
      (let [updated (db/execute-one! helper/*conn* {:select [:*] :from [:plans] :where [:= :id plan-id]})]
        (is (= 1 (:completed updated)))))))

(deftest task-update-test
  (testing "task-update updates task fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test plan" nil)
          plan-id (:id plan)
          task (task/create helper/*conn* plan-id "Original task" "Original content" nil)
          task-id (:id task)]
      ;; Update description
      (db/execute! helper/*conn* {:update :tasks
                                  :set {:description "Updated task"}
                                  :where [:= :id task-id]})
      (let [updated (db/execute-one! helper/*conn* {:select [:*] :from [:tasks] :where [:= :id task-id]})]
        (is (= "Updated task" (:description updated))))
      ;; Update completed status
      (db/execute! helper/*conn* {:update :tasks
                                  :set {:completed true}
                                  :where [:= :id task-id]})
      (let [updated (db/execute-one! helper/*conn* {:select [:*] :from [:tasks] :where [:= :id task-id]})]
        (is (= 1 (:completed updated))))
      ;; Move to different plan
      (let [plan2 (plan/create helper/*conn* "Another plan" nil)
            plan2-id (:id plan2)]
        (db/execute! helper/*conn* {:update :tasks
                                    :set {:plan_id plan2-id}
                                    :where [:= :id task-id]})
        (let [updated (db/execute-one! helper/*conn* {:select [:*] :from [:tasks] :where [:= :id task-id]})]
          (is (= plan2-id (:plan_id updated))))))))

(deftest plan-delete-test
  (testing "plan-delete removes plan and associated tasks/facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Plan to delete" nil)
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "Task 1" nil nil)
      (task/create helper/*conn* plan-id "Task 2" nil nil)
      (db/execute! helper/*conn* {:insert-into :facts
                                  :columns [:plan_id :description]
                                  :values [[plan-id "Fact to delete"]]})
      ;; Delete the plan
      (db/execute! helper/*conn* {:delete-from :tasks :where [:= :plan_id plan-id]})
      (db/execute! helper/*conn* {:delete-from :facts :where [:= :plan_id plan-id]})
      (db/execute! helper/*conn* {:delete-from :plans :where [:= :id plan-id]})
      ;; Verify deletion
      (is (nil? (db/execute-one! helper/*conn* {:select [:*] :from [:plans] :where [:= :id plan-id]})))
      (is (empty? (db/execute! helper/*conn* {:select [:*] :from [:tasks] :where [:= :plan_id plan-id]})))
      (is (empty? (db/execute! helper/*conn* {:select [:*] :from [:facts] :where [:= :plan_id plan-id]}))))))

(deftest task-delete-test
  (testing "task-delete removes task"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test plan" nil)
          plan-id (:id plan)
          task (task/create helper/*conn* plan-id "Task to delete" nil nil)
          task-id (:id task)]
      ;; Delete the task
      (db/execute! helper/*conn* {:delete-from :tasks :where [:= :id task-id]})
      ;; Verify deletion
      (is (nil? (db/execute-one! helper/*conn* {:select [:*] :from [:tasks] :where [:= :id task-id]}))))))

(deftest plan-list-test
  (testing "plan-list returns all plans ordered by created_at desc, then id desc"
    (main/create-schema! helper/*conn*)
    (plan/create helper/*conn* "Plan 1" nil)
    (Thread/sleep 10) ;; Note: SQLite datetime has 1-second resolution
    (plan/create helper/*conn* "Plan 2" nil)
    (Thread/sleep 10)
    (plan/create helper/*conn* "Plan 3" nil)
    (let [plans (db/execute! helper/*conn* {:select [:*] :from [:plans] :order-by [[:created_at :desc] [:id :desc]]})]
      (is (= 3 (count plans)))
      (is (= "Plan 3" (:description (first plans))))
      (is (= "Plan 1" (:description (last plans)))))))

(deftest task-list-test
  (testing "task-list returns tasks for a plan ordered by created_at desc, then id desc"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test plan" nil)
          plan-id (:id plan)]
      (task/create helper/*conn* plan-id "Task 1" nil nil)
      (Thread/sleep 10) ;; Note: SQLite datetime has 1-second resolution
      (task/create helper/*conn* plan-id "Task 2" nil nil)
      (Thread/sleep 10)
      (task/create helper/*conn* plan-id "Task 3" nil nil)
      (let [tasks (db/execute! helper/*conn* {:select [:*] :from [:tasks] :where [:= :plan_id plan-id] :order-by [[:created_at :desc] [:id :desc]]})]
        (is (= 3 (count tasks)))
        (is (= "Task 3" (:description (first tasks))))
        (is (= "Task 1" (:description (last tasks))))))))

(deftest search-test
  (testing "search finds matches across plans, tasks, and facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Searchable plan" "Plan content")]
      (db/execute! helper/*conn* {:insert-into :tasks
                                  :columns [:plan_id :description :content]
                                  :values [[(:id plan) "Searchable task" "Task content"]]})
      (db/execute! helper/*conn* {:insert-into :facts
                                  :columns [:plan_id :description :content]
                                  :values [[(:id plan) "Searchable fact" "Fact content"]]}))
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
      (is (contains? commands "search")))))
