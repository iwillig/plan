(ns plan.operations.fact-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [failjure.core :as f]
   [plan.main :as main]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.operations.fact :as ops]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

;; -----------------------------------------------------------------------------
;; list-facts Tests
;; -----------------------------------------------------------------------------

(deftest list-facts-test
  (main/create-schema! helper/*conn*)

  (testing "returns facts for existing plan"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)]
      (fact/create helper/*conn* (:id p) "fact-1" "Desc 1" "Content 1")
      (fact/create helper/*conn* (:id p) "fact-2" "Desc 2" "Content 2")
      (let [result (ops/list-facts helper/*conn* (:id p))]
        (is (f/ok? result))
        (is (= 2 (count result)))
        (is (= #{"fact-1" "fact-2"} (set (map :name result)))))))

  (testing "returns empty vector for plan with no facts"
    (let [p (plan/create helper/*conn* "empty-plan" "Test" nil)
          result (ops/list-facts helper/*conn* (:id p))]
      (is (f/ok? result))
      (is (empty? result))))

  (testing "returns failure for non-existent plan"
    (let [result (ops/list-facts helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; get-fact Tests
;; -----------------------------------------------------------------------------

(deftest get-fact-test
  (main/create-schema! helper/*conn*)

  (testing "returns fact when found"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          created (fact/create helper/*conn* (:id p) "my-fact" "Description" "Content")
          result (ops/get-fact helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (= "my-fact" (:name result)))
      (is (= "Content" (:content result)))))

  (testing "returns failure when not found"
    (let [result (ops/get-fact helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Fact not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; create-fact Tests
;; -----------------------------------------------------------------------------

(deftest create-fact-test
  (main/create-schema! helper/*conn*)

  (testing "creates fact with all fields"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          result (ops/create-fact helper/*conn*
                                  {:plan_id (:id p)
                                   :name "new-fact"
                                   :description "A description"
                                   :content "The content"})]
      (is (f/ok? result))
      (is (number? (:id result)))
      (is (= "new-fact" (:name result)))
      (is (= "A description" (:description result)))
      (is (= "The content" (:content result)))))

  (testing "creates fact with optional description nil"
    (let [p (plan/create helper/*conn* "test-plan-2" "Test" nil)
          result (ops/create-fact helper/*conn*
                                  {:plan_id (:id p)
                                   :name "minimal-fact"
                                   :content "Just content"})]
      (is (f/ok? result))
      (is (= "minimal-fact" (:name result)))))

  (testing "fails when plan_id missing"
    (let [result (ops/create-fact helper/*conn*
                                  {:name "test" :content "Content"})]
      (is (f/failed? result))
      (is (re-find #"(?i)plan" (f/message result)))))

  (testing "fails when name missing"
    (let [p (plan/create helper/*conn* "test-plan-3" "Test" nil)
          result (ops/create-fact helper/*conn*
                                  {:plan_id (:id p) :content "Content"})]
      (is (f/failed? result))
      (is (re-find #"(?i)name" (f/message result)))))

  (testing "fails when content missing"
    (let [p (plan/create helper/*conn* "test-plan-4" "Test" nil)
          result (ops/create-fact helper/*conn*
                                  {:plan_id (:id p) :name "test"})]
      (is (f/failed? result))
      (is (re-find #"(?i)content" (f/message result)))))

  (testing "fails when plan does not exist"
    (let [result (ops/create-fact helper/*conn*
                                  {:plan_id 999
                                   :name "test"
                                   :content "Content"})]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; update-fact Tests
;; -----------------------------------------------------------------------------

(deftest update-fact-test
  (main/create-schema! helper/*conn*)

  (testing "updates single field"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          created (fact/create helper/*conn* (:id p) "original" "Original desc" "Original content")
          result (ops/update-fact helper/*conn* (:id created) {:content "Updated content"})]
      (is (f/ok? result))
      (is (= "Updated content" (:content result)))
      (is (= "original" (:name result)))))

  (testing "updates multiple fields"
    (let [p (plan/create helper/*conn* "test-plan-2" "Test" nil)
          created (fact/create helper/*conn* (:id p) "original" "Original" "Original")
          result (ops/update-fact helper/*conn* (:id created)
                                  {:name "updated-name"
                                   :description "New desc"
                                   :content "New content"})]
      (is (f/ok? result))
      (is (= "updated-name" (:name result)))
      (is (= "New desc" (:description result)))
      (is (= "New content" (:content result)))))

  (testing "fails when fact not found"
    (let [result (ops/update-fact helper/*conn* 999 {:content "New"})]
      (is (f/failed? result))
      (is (re-find #"Fact not found" (f/message result)))))

  (testing "fails when no update fields provided"
    (let [p (plan/create helper/*conn* "test-plan-3" "Test" nil)
          created (fact/create helper/*conn* (:id p) "test" "Test" "Content")
          result (ops/update-fact helper/*conn* (:id created) {})]
      (is (f/failed? result))
      (is (re-find #"No fields to update" (f/message result)))))

  (testing "ignores invalid update fields"
    (let [p (plan/create helper/*conn* "test-plan-4" "Test" nil)
          created (fact/create helper/*conn* (:id p) "test" "Test" "Content")
          result (ops/update-fact helper/*conn* (:id created) {:invalid-field "value"})]
      (is (f/failed? result))
      (is (re-find #"No fields to update" (f/message result))))))

;; -----------------------------------------------------------------------------
;; delete-fact Tests
;; -----------------------------------------------------------------------------

(deftest delete-fact-test
  (main/create-schema! helper/*conn*)

  (testing "deletes existing fact"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          created (fact/create helper/*conn* (:id p) "to-delete" "Desc" "Content")
          result (ops/delete-fact helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (true? (:deleted result)))
      (is (= (:id created) (:fact_id result)))
      ;; Verify actually deleted
      (is (nil? (fact/get-by-id helper/*conn* (:id created))))))

  (testing "fails when fact not found"
    (let [result (ops/delete-fact helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Fact not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; search-facts Tests
;; -----------------------------------------------------------------------------

(deftest search-facts-test
  (main/create-schema! helper/*conn*)

  (testing "finds matching facts"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)]
      (fact/create helper/*conn* (:id p) "important-fact" "Key information" "Critical content")
      (let [result (ops/search-facts helper/*conn* "important")]
        (is (f/ok? result))
        (is (= 1 (count result)))
        (is (= "important-fact" (:name (first result)))))))

  (testing "returns empty for no matches"
    (let [p (plan/create helper/*conn* "test-plan-2" "Test" nil)]
      (fact/create helper/*conn* (:id p) "fact" "Desc" "Content")
      (let [result (ops/search-facts helper/*conn* "nonexistent")]
        (is (f/ok? result))
        (is (empty? result)))))

  (testing "fails on empty query"
    (let [result (ops/search-facts helper/*conn* "")]
      (is (f/failed? result))
      (is (re-find #"cannot be empty" (f/message result)))))

  (testing "fails on blank query"
    (let [result (ops/search-facts helper/*conn* "   ")]
      (is (f/failed? result))
      (is (re-find #"cannot be empty" (f/message result))))))
