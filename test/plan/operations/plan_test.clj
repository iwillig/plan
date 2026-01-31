(ns plan.operations.plan-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [failjure.core :as f]
   [plan.main :as main]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.operations.plan :as ops]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

;; -----------------------------------------------------------------------------
;; list-plans Tests
;; -----------------------------------------------------------------------------

(deftest list-plans-test
  (main/create-schema! helper/*conn*)

  (testing "returns all plans"
    (plan/create helper/*conn* "plan-1" "First" nil)
    (plan/create helper/*conn* "plan-2" "Second" nil)
    (let [result (ops/list-plans helper/*conn*)]
      (is (= 2 (count result)))
      (is (= #{"plan-1" "plan-2"} (set (map :name result)))))))

(deftest list-plans-empty-test
  (main/create-schema! helper/*conn*)

  (testing "returns empty when no plans"
    (let [result (ops/list-plans helper/*conn*)]
      (is (empty? result)))))

;; -----------------------------------------------------------------------------
;; get-plan Tests
;; -----------------------------------------------------------------------------

(deftest get-plan-test
  (main/create-schema! helper/*conn*)

  (testing "returns plan when found"
    (let [created (plan/create helper/*conn* "test-plan" "Description" "Content")
          result (ops/get-plan helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (= "test-plan" (:name result)))))

  (testing "returns failure when not found"
    (let [result (ops/get-plan helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; show-plan Tests
;; -----------------------------------------------------------------------------

(deftest show-plan-test
  (main/create-schema! helper/*conn*)

  (testing "returns plan with tasks and facts"
    (let [p (plan/create helper/*conn* "test-plan" "Description" nil)]
      (task/create helper/*conn* (:id p) "task-1" nil nil nil)
      (task/create helper/*conn* (:id p) "task-2" nil nil nil)
      (fact/create helper/*conn* (:id p) "fact-1" "Desc" "Content")
      (let [result (ops/show-plan helper/*conn* (:id p))]
        (is (f/ok? result))
        (is (= "test-plan" (get-in result [:plan :name])))
        (is (= 2 (count (:tasks result))))
        (is (= 1 (count (:facts result)))))))

  (testing "returns failure when plan not found"
    (let [result (ops/show-plan helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; create-plan Tests
;; -----------------------------------------------------------------------------

(deftest create-plan-test
  (main/create-schema! helper/*conn*)

  (testing "creates plan with all fields"
    (let [result (ops/create-plan helper/*conn*
                                  {:name "new-plan"
                                   :description "A description"
                                   :content "The content"})]
      (is (f/ok? result))
      (is (number? (:id result)))
      (is (= "new-plan" (:name result)))
      (is (= "A description" (:description result)))
      (is (= "The content" (:content result)))))

  (testing "creates plan with only name"
    (let [result (ops/create-plan helper/*conn* {:name "minimal-plan"})]
      (is (f/ok? result))
      (is (= "minimal-plan" (:name result)))))

  (testing "fails when name missing"
    (let [result (ops/create-plan helper/*conn* {:description "No name"})]
      (is (f/failed? result))
      (is (re-find #"Missing required parameters" (f/message result))))))

;; -----------------------------------------------------------------------------
;; update-plan Tests
;; -----------------------------------------------------------------------------

(deftest update-plan-test
  (main/create-schema! helper/*conn*)

  (testing "updates single field"
    (let [created (plan/create helper/*conn* "update-single-field" "Original desc" nil)
          result (ops/update-plan helper/*conn* (:id created) {:description "Updated"})]
      (is (f/ok? result))
      (is (= "Updated" (:description result)))
      (is (= "update-single-field" (:name result)))))

  (testing "updates multiple fields"
    (let [created (plan/create helper/*conn* "update-multiple-fields" "Original" nil)
          result (ops/update-plan helper/*conn* (:id created)
                                  {:name "updated-name"
                                   :description "New desc"
                                   :content "New content"})]
      (is (f/ok? result))
      (is (= "updated-name" (:name result)))
      (is (= "New desc" (:description result)))
      (is (= "New content" (:content result)))))

  (testing "updates completed status"
    (let [created (plan/create helper/*conn* "update-completed" "Test" nil)
          result (ops/update-plan helper/*conn* (:id created) {:completed true})]
      (is (f/ok? result))
      (is (true? (:completed result)))))

  (testing "fails when plan not found"
    (let [result (ops/update-plan helper/*conn* 999 {:name "New"})]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result)))))

  (testing "fails when no update fields provided"
    (let [created (plan/create helper/*conn* "update-no-fields" "Test" nil)
          result (ops/update-plan helper/*conn* (:id created) {})]
      (is (f/failed? result))
      (is (re-find #"No fields to update" (f/message result))))))

;; -----------------------------------------------------------------------------
;; delete-plan Tests
;; -----------------------------------------------------------------------------

(deftest delete-plan-test
  (main/create-schema! helper/*conn*)

  (testing "deletes plan with tasks and facts"
    (let [p (plan/create helper/*conn* "to-delete" "Test" nil)]
      (task/create helper/*conn* (:id p) "task-1" nil nil nil)
      (fact/create helper/*conn* (:id p) "fact-1" nil "Content")
      (let [result (ops/delete-plan helper/*conn* (:id p))]
        (is (f/ok? result))
        (is (true? (:deleted result)))
        (is (= 1 (:tasks-deleted result)))
        (is (= 1 (:facts-deleted result)))
        ;; Verify actually deleted
        (is (nil? (plan/get-by-id helper/*conn* (:id p)))))))

  (testing "fails when plan not found"
    (let [result (ops/delete-plan helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; search-plans Tests
;; -----------------------------------------------------------------------------

(deftest search-plans-test
  (main/create-schema! helper/*conn*)

  (testing "finds matching plans"
    (plan/create helper/*conn* "important-plan" "Key information" nil)
    (let [result (ops/search-plans helper/*conn* "important")]
      (is (f/ok? result))
      (is (= 1 (count result)))
      (is (= "important-plan" (:name (first result))))))

  (testing "returns empty for no matches"
    (plan/create helper/*conn* "test-plan" "Test" nil)
    (let [result (ops/search-plans helper/*conn* "nonexistent")]
      (is (f/ok? result))
      (is (empty? result))))

  (testing "fails on empty query"
    (let [result (ops/search-plans helper/*conn* "")]
      (is (f/failed? result))
      (is (re-find #"cannot be empty" (f/message result)))))

  (testing "fails on blank query"
    (let [result (ops/search-plans helper/*conn* "   ")]
      (is (f/failed? result))
      (is (re-find #"cannot be empty" (f/message result))))))
