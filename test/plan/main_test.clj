(ns plan.main-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.db :as db]
   [plan.main :as main]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest create-schema-test
  (testing "creates all tables, indexes, and FTS structures"
    (main/create-schema! helper/*conn*)
    (let [tables (db/execute! helper/*conn* {:select [:name] :from :sqlite_master :where [:= :type "table"] :order-by [:name]})
          indexes (db/execute! helper/*conn* {:select [:name] :from :sqlite_master :where [:and [:= :type "index"] [:like :name "idx_%"]] :order-by [:name]})
          triggers (db/execute! helper/*conn* {:select [:name] :from :sqlite_master :where [:= :type "trigger"] :order-by [:name]})
          table-names (set (map :sqlite_master/name tables))]
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
             (map :sqlite_master/name indexes)))
      ;; Check triggers exist
      (is (= ["facts_ad" "facts_ai" "facts_au" "plans_ad" "plans_ai" "plans_au" "tasks_ad" "tasks_ai" "tasks_au"]
             (map :sqlite_master/name triggers))))))

(deftest schema-is-isolated-test
  (testing "each test gets a fresh database"
    (let [tables (db/execute! helper/*conn* {:select [:name] :from :sqlite_master :where [:= :type "table"]})]
      (is (empty? (filter #(not= "sqlite_sequence" %) (map :sqlite_master/name tables)))))))

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
      (is (= "Test plan" (:plans/description (first plan-results))))
      (is (= "Test task" (:tasks/description (first task-results))))
      (is (= "Test fact" (:facts/description (first fact-results)))))))

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
      (is (= "Planning project" (:plans/description (first results)))))))
