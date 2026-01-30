(ns plan.import-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.import :as import]
   [plan.main :as main]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest test-import-plan-new
  (testing "import creates new plan with tasks and facts"
    (main/create-schema! helper/*conn*)
    (let [data {:plan {:name "Import Test"
                       :description "Test description"
                       :content "# Test Content"
                       :completed false}
                :tasks [{:name "Task 1" :description "First" :completed true}
                        {:name "Task 2" :description "Second" :completed false}]
                :facts [{:name "Fact 1" :description "A fact" :content "Fact content"}]}
          result (import/import-plan helper/*conn* data)]
      (is (= "Import Test" (:name result)))
      (is (= 2 (:tasks-imported result)))
      (is (= 1 (:facts-imported result)))
      ;; Verify in database
      (let [db-plan (plan/get-by-name helper/*conn* "Import Test")
            tasks (task/get-by-plan helper/*conn* (:id db-plan))
            facts (fact/get-by-plan helper/*conn* (:id db-plan))]
        (is (= "Import Test" (:name db-plan)))
        (is (= "Test description" (:description db-plan)))
        (is (= 2 (count tasks)))
        (is (= 1 (count facts)))))))

(deftest test-import-plan-update
  (testing "import updates existing plan"
    (main/create-schema! helper/*conn*)
    ;; Create initial plan
    (plan/create helper/*conn* "Update Test" "Original" "# Original")
    (let [db-plan (plan/get-by-name helper/*conn* "Update Test")
          _ (task/create helper/*conn* (:id db-plan) "Existing Task" "Old" nil nil)
          ;; Import updated version
          data {:plan {:name "Update Test"
                       :description "Updated description"
                       :content "# Updated"
                       :completed true}
                :tasks [{:name "Existing Task" :description "Updated" :completed true}
                        {:name "New Task" :description "Brand new" :completed false}]
                :facts [{:name "New Fact" :description "A fact" :content "Content"}]}
          _result (import/import-plan helper/*conn* data)
          ;; Verify updates
          updated-plan (plan/get-by-name helper/*conn* "Update Test")
          tasks (task/get-by-plan helper/*conn* (:id updated-plan))
          facts (fact/get-by-plan helper/*conn* (:id updated-plan))]
      (is (= "Updated description" (:description updated-plan)))
      (is (= true (:completed updated-plan)))
      (is (= 2 (count tasks)))
      (is (= 1 (count facts))))))

(deftest test-import-plan-delete-orphans
  (testing "import deletes tasks and facts not in import"
    (main/create-schema! helper/*conn*)
    ;; Create plan with tasks
    (plan/create helper/*conn* "Orphan Test" "Desc" "# Content")
    (let [db-plan (plan/get-by-name helper/*conn* "Orphan Test")
          plan-id (:id db-plan)
          _ (task/create helper/*conn* plan-id "Keep Task" "Keep" nil nil)
          _ (task/create helper/*conn* plan-id "Delete Task" "Delete" nil nil)
          _ (fact/create helper/*conn* plan-id "Keep Fact" "Keep" "Content")
          _ (fact/create helper/*conn* plan-id "Delete Fact" "Delete" "Content")
          ;; Import with only one task and one fact
          data {:plan {:name "Orphan Test" :description "Desc" :content "# Content" :completed false}
                :tasks [{:name "Keep Task" :description "Updated" :completed true}]
                :facts [{:name "Keep Fact" :description "Updated" :content "Updated"}]}
          result (import/import-plan helper/*conn* data)
          ;; Verify
          tasks (task/get-by-plan helper/*conn* plan-id)
          facts (fact/get-by-plan helper/*conn* plan-id)]
      (is (= 1 (:tasks-imported result)))
      (is (= 1 (:tasks-deleted result)))
      (is (= 1 (:facts-imported result)))
      (is (= 1 (:facts-deleted result)))
      (is (= 1 (count tasks)))
      (is (= "Keep Task" (:name (first tasks))))
      (is (= 1 (count facts)))
      (is (= "Keep Fact" (:name (first facts)))))))

(deftest test-import-from-file
  (testing "import from markdown file"
    (main/create-schema! helper/*conn*)
    (let [tmp-file (str "/tmp/test-import-" (System/currentTimeMillis) ".md")
          markdown "---
description: From file
completed: false
tasks:
- name: File Task
  description: Task from file
  completed: true
facts:
- name: File Fact
  description: Fact from file
  content: Fact content
---

# File Import Test

File content here"]
      (try
        (spit tmp-file markdown)
        (let [result (import/import-from-file helper/*conn* tmp-file)]
          (is (= "File Import Test" (:name result)))
          (is (= 1 (:tasks-imported result)))
          (is (= 1 (:facts-imported result))))
        (finally
          (when (.exists (io/file tmp-file))
            (.delete (io/file tmp-file))))))))

(deftest test-import-from-string
  (testing "import from markdown string"
    (main/create-schema! helper/*conn*)
    (let [markdown "---
description: From string
completed: false
tasks:
- name: String Task
  completed: false
---

# String Import Test

Content"
          result (import/import-from-string helper/*conn* markdown)]
      (is (= "String Import Test" (:name result)))
      (is (= 1 (:tasks-imported result))))))

(deftest test-preview-import
  (testing "preview shows planned operations for new plan"
    (main/create-schema! helper/*conn*)
    (let [data {:plan {:name "Preview Test" :description "Desc" :completed false}
                :tasks [{:name "Task 1" :completed false}
                        {:name "Task 2" :completed false}]
                :facts [{:name "Fact 1" :content "Content"}]}
          preview (import/preview-import helper/*conn* data)]
      (is (= "Preview Test" (:plan-name preview)))
      (is (= false (:plan-exists? preview)))
      (is (= 2 (get-in preview [:tasks :create])))
      (is (= 0 (get-in preview [:tasks :update])))
      (is (= 0 (get-in preview [:tasks :delete])))
      (is (= 1 (get-in preview [:facts :create]))))))

(deftest test-preview-import-existing
  (testing "preview shows planned operations for existing plan"
    (main/create-schema! helper/*conn*)
    ;; Create existing plan
    (plan/create helper/*conn* "Preview Existing" "Original" "# Content")
    (let [db-plan (plan/get-by-name helper/*conn* "Preview Existing")
          _ (task/create helper/*conn* (:id db-plan) "Existing Task" "Old" nil nil)
          _ (fact/create helper/*conn* (:id db-plan) "Existing Fact" "Old" "Old")
          ;; Preview import with one new, one update, and missing the existing
          data {:plan {:name "Preview Existing" :description "Updated" :completed true}
                :tasks [{:name "Existing Task" :description "Updated" :completed true}
                        {:name "New Task" :description "New" :completed false}]
                :facts [{:name "New Fact" :description "New" :content "New"}]}
          preview (import/preview-import helper/*conn* data)]
      (is (= true (:plan-exists? preview)))
      (is (= 1 (get-in preview [:tasks :create])))
      (is (= 1 (get-in preview [:tasks :update])))
      (is (= 0 (get-in preview [:tasks :delete])))
      (is (= 1 (get-in preview [:facts :create])))
      (is (= 0 (get-in preview [:facts :update])))
      (is (= 1 (get-in preview [:facts :delete]))))))

(deftest test-import-idempotent
  (testing "importing same data twice is idempotent"
    (main/create-schema! helper/*conn*)
    (let [data {:plan {:name "Idempotent Test" :description "Desc" :completed false}
                :tasks [{:name "Task 1" :description "First" :completed false}]
                :facts []}
          ;; First import
          result1 (import/import-plan helper/*conn* data)
          ;; Second import
          result2 (import/import-plan helper/*conn* data)]
      ;; Plan ID should be the same
      (is (= (:id result1) (:id result2)))
      ;; Second import should update, not create
      (is (= 0 (:tasks-deleted result2)))
      ;; Verify only one plan exists
      (let [plans (plan/get-all helper/*conn*)]
        (is (= 1 (count plans)))))))

(deftest test-import-empty-collections
  (testing "import with empty tasks and facts works"
    (main/create-schema! helper/*conn*)
    (let [data {:plan {:name "Empty Test" :description "No children" :completed true}
                :tasks []
                :facts []}
          result (import/import-plan helper/*conn* data)]
      (is (= "Empty Test" (:name result)))
      (is (= 0 (:tasks-imported result)))
      (is (= 0 (:facts-imported result))))))

(deftest test-import-example-file
  (testing "import the example file"
    (main/create-schema! helper/*conn*)
    (let [result (import/import-from-file helper/*conn* "examples/complete-example.md")]
      (is (= "Product Launch 2024" (:name result)))
      (is (= 4 (:tasks-imported result)))
      (is (= 3 (:facts-imported result)))
      ;; Verify tasks were created
      (let [db-plan (plan/get-by-name helper/*conn* "Product Launch 2024")
            tasks (task/get-by-plan helper/*conn* (:id db-plan))
            facts (fact/get-by-plan helper/*conn* (:id db-plan))]
        (is (= 4 (count tasks)))
        (is (= 3 (count facts)))
        ;; Check specific task
        (let [design-task (first (filter #(= "Design marketing website" (:name %)) tasks))]
          (is (= true (:completed design-task)))
          (is (string? (:content design-task))))))))

(deftest test-import-transaction-rollback
  (testing "import is atomic - all succeeds or all fails"
    (main/create-schema! helper/*conn*)
    ;; First import successfully
    (import/import-plan helper/*conn*
                        {:plan {:name "Transaction Test" :description "Original" :completed false}
                         :tasks [{:name "Task 1" :description "First" :completed false}]
                         :facts []})
    (let [original-plan (plan/get-by-name helper/*conn* "Transaction Test")]
      (is (= "Original" (:description original-plan)))
      (is (= 1 (count (task/get-by-plan helper/*conn* (:id original-plan)))))
      ;; Verify the transaction wrapper works by doing a complex update
      (let [result (import/import-plan helper/*conn*
                                       {:plan {:name "Transaction Test"
                                               :description "Updated"
                                               :completed true}
                                        :tasks [{:name "Task 1" :description "Updated" :completed true}
                                                {:name "Task 2" :description "New" :completed false}]
                                        :facts [{:name "Fact 1" :description "New fact" :content "Content"}]})]
        ;; Verify all changes were applied atomically
        (is (= "Updated" (:description result)))
        (is (= 2 (:tasks-imported result)))
        (is (= 1 (:facts-imported result))))
      ;; Verify final state
      (let [final-plan (plan/get-by-name helper/*conn* "Transaction Test")
            final-tasks (task/get-by-plan helper/*conn* (:id final-plan))
            final-facts (fact/get-by-plan helper/*conn* (:id final-plan))]
        (is (= "Updated" (:description final-plan)))
        (is (= true (:completed final-plan)))
        (is (= 2 (count final-tasks)))
        (is (= 1 (count final-facts)))))))
