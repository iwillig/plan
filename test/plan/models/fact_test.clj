(ns plan.models.fact-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [malli.core :as m]
   [plan.main :as main]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest fact-schema-test
  (testing "Fact schema validates correct data"
    (is (m/validate fact/Fact
                    {:id 1
                     :plan_id 1
                     :name "test-fact"
                     :description "Test"
                     :content "Content"
                     :created_at "2024-01-01"
                     :updated_at "2024-01-01"})))
  (testing "Fact schema allows nil for optional fields"
    (is (malli.core/validate fact/Fact
                             {:id nil
                              :plan_id 1
                              :name "test-fact"
                              :description nil
                              :content nil
                              :created_at nil
                              :updated_at nil}))))

(deftest create-test
  (testing "create returns a fact with generated fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan" "Test plan" nil)
          result (fact/create helper/*conn* (:id plan) "test-fact" "Test fact" "Content")]
      (is (some? result))
      (is (= "test-fact" (:name result)))
      (is (= "Test fact" (:description result)))
      (is (= "Content" (:content result)))
      (is (= (:id plan) (:plan_id result)))
      (is (number? (:id result)))
      (is (some? (:created_at result)))))

  (testing "create handles nil description and content"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test" "Test" nil)
          result (fact/create helper/*conn* (:id plan) "test-fact" nil nil)]
      (is (= "test-fact" (:name result)))
      (is (nil? (:description result)))
      (is (nil? (:content result))))))

(deftest get-by-id-test
  (testing "get-by-id returns fact when found"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test" "Test" nil)
          created (fact/create helper/*conn* (:id plan) "test-fact" "Test fact" nil)
          fetched (fact/get-by-id helper/*conn* (:id created))]
      (is (= (:id created) (:id fetched)))
      (is (= "test-fact" (:name fetched)))
      (is (= "Test fact" (:description fetched)))))

  (testing "get-by-id returns nil when not found"
    (main/create-schema! helper/*conn*)
    (is (nil? (fact/get-by-id helper/*conn* 999)))))

(deftest get-by-plan-test
  (testing "get-by-plan returns facts for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-with-facts" "Test" nil)
          plan-id (:id plan)]
      (fact/create helper/*conn* plan-id "fact-1" "Fact 1" nil)
      (fact/create helper/*conn* plan-id "fact-2" "Fact 2" nil)
      (let [facts (fact/get-by-plan helper/*conn* plan-id)]
        (is (= 2 (count facts))))))

  (testing "get-by-plan returns empty for plan with no facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-no-facts" "Test" nil)]
      (is (empty? (fact/get-by-plan helper/*conn* (:id plan)))))))

(deftest get-all-test
  (testing "get-all returns all facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-all-facts" "Test" nil)]
      (fact/create helper/*conn* (:id plan) "fact-1" "Fact 1" nil)
      (fact/create helper/*conn* (:id plan) "fact-2" "Fact 2" nil)
      (is (= 2 (count (fact/get-all helper/*conn*)))))))

(deftest update-test
  (testing "update modifies fact fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-update-1" "Test" nil)
          created (fact/create helper/*conn* (:id plan) "original" "Original" "Content")
          updated (fact/update helper/*conn* (:id created) {:description "Updated"})]
      (is (= "Updated" (:description updated)))
      (is (= "Content" (:content updated)))))

  (testing "update can modify multiple fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-update-2" "Test" nil)
          created (fact/create helper/*conn* (:id plan) "original" "Original" "Original content")
          updated (fact/update helper/*conn* (:id created) {:name "updated" :description "New" :content "New content"})]
      (is (= "updated" (:name updated)))
      (is (= "New" (:description updated)))
      (is (= "New content" (:content updated)))))

  (testing "update returns nil for non-existent fact"
    (main/create-schema! helper/*conn*)
    (is (nil? (fact/update helper/*conn* 999 {:description "Test"}))))

  (testing "update returns nil for empty updates"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-update-3" "Test" nil)
          created (fact/create helper/*conn* (:id plan) "test" "Test" nil)]
      (is (nil? (fact/update helper/*conn* (:id created) {}))))))

(deftest delete-test
  (testing "delete removes fact and returns true"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-delete" "Test" nil)
          created (fact/create helper/*conn* (:id plan) "to-delete" "To delete" nil)]
      (is (fact/delete helper/*conn* (:id created)))
      (is (nil? (fact/get-by-id helper/*conn* (:id created))))))

  (testing "delete returns false for non-existent fact"
    (main/create-schema! helper/*conn*)
    (is (false? (fact/delete helper/*conn* 999)))))

(deftest delete-by-plan-test
  (testing "delete-by-plan removes all facts for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-delete-by-plan" "Test" nil)
          plan-id (:id plan)]
      (fact/create helper/*conn* plan-id "fact-1" "Fact 1" nil)
      (fact/create helper/*conn* plan-id "fact-2" "Fact 2" nil)
      (is (= 2 (fact/delete-by-plan helper/*conn* plan-id)))
      (is (empty? (fact/get-by-plan helper/*conn* plan-id))))))

(deftest get-by-name-test
  (testing "get-by-name returns fact when found"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-get-by-name" "Test" nil)
          created (fact/create helper/*conn* (:id p) "unique-fact" "Description" "Content")
          fetched (fact/get-by-name helper/*conn* (:id p) "unique-fact")]
      (is (some? fetched))
      (is (= (:id created) (:id fetched)))
      (is (= "unique-fact" (:name fetched)))
      (is (= "Description" (:description fetched)))))

  (testing "get-by-name returns nil when not found"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-get-by-name-2" "Test" nil)]
      (is (nil? (fact/get-by-name helper/*conn* (:id p) "non-existent")))))

  (testing "get-by-name is scoped to plan"
    (main/create-schema! helper/*conn*)
    (let [p1 (plan/create helper/*conn* "plan-1" "Test 1" nil)
          p2 (plan/create helper/*conn* "plan-2" "Test 2" nil)]
      (fact/create helper/*conn* (:id p1) "same-name" "In plan 1" "Content 1")
      (fact/create helper/*conn* (:id p2) "same-name" "In plan 2" "Content 2")
      (let [f1 (fact/get-by-name helper/*conn* (:id p1) "same-name")
            f2 (fact/get-by-name helper/*conn* (:id p2) "same-name")]
        (is (= "In plan 1" (:description f1)))
        (is (= "In plan 2" (:description f2)))))))

(deftest upsert-test
  (testing "upsert creates new fact when not exists"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-upsert-1" "Test" nil)
          result (fact/upsert helper/*conn* (:id p)
                              {:name "new-fact"
                               :description "Description"
                               :content "Content"})]
      (is (some? result))
      (is (= "new-fact" (:name result)))
      (is (= "Description" (:description result)))
      (is (= "Content" (:content result)))))

  (testing "upsert updates existing fact when exists"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-upsert-2" "Test" nil)
          _ (fact/create helper/*conn* (:id p) "existing" "Original desc" "Original content")
          result (fact/upsert helper/*conn* (:id p)
                              {:name "existing"
                               :description "Updated desc"
                               :content "Updated content"})]
      (is (= "existing" (:name result)))
      (is (= "Updated desc" (:description result)))
      (is (= "Updated content" (:content result)))
      ;; Verify only one fact exists with this name
      (is (= 1 (count (fact/get-by-plan helper/*conn* (:id p)))))))

  (testing "upsert preserves fact id on update"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-upsert-3" "Test" nil)
          original (fact/create helper/*conn* (:id p) "preserve-id" "Original" "Original")
          updated (fact/upsert helper/*conn* (:id p)
                               {:name "preserve-id"
                                :description "Updated"
                                :content "Updated"})]
      (is (= (:id original) (:id updated))))))

(deftest delete-orphans-test
  (testing "delete-orphans removes facts not in keep-names"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-orphans-1" "Test" nil)
          plan-id (:id p)]
      (fact/create helper/*conn* plan-id "keep-1" "Keep 1" "Content")
      (fact/create helper/*conn* plan-id "keep-2" "Keep 2" "Content")
      (fact/create helper/*conn* plan-id "orphan-1" "Orphan 1" "Content")
      (fact/create helper/*conn* plan-id "orphan-2" "Orphan 2" "Content")
      (let [deleted-count (fact/delete-orphans helper/*conn* plan-id ["keep-1" "keep-2"])
            remaining (fact/get-by-plan helper/*conn* plan-id)]
        (is (= 2 deleted-count))
        (is (= 2 (count remaining)))
        (is (= #{"keep-1" "keep-2"} (set (map :name remaining)))))))

  (testing "delete-orphans with empty keep-names deletes all"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-orphans-2" "Test" nil)
          plan-id (:id p)]
      (fact/create helper/*conn* plan-id "fact-1" "Fact 1" "Content")
      (fact/create helper/*conn* plan-id "fact-2" "Fact 2" "Content")
      (let [deleted-count (fact/delete-orphans helper/*conn* plan-id [])]
        (is (= 2 deleted-count))
        (is (empty? (fact/get-by-plan helper/*conn* plan-id))))))

  (testing "delete-orphans with nil keep-names deletes all"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-orphans-3" "Test" nil)
          plan-id (:id p)]
      (fact/create helper/*conn* plan-id "fact-1" "Fact 1" "Content")
      (let [deleted-count (fact/delete-orphans helper/*conn* plan-id nil)]
        (is (= 1 deleted-count))
        (is (empty? (fact/get-by-plan helper/*conn* plan-id))))))

  (testing "delete-orphans returns 0 when no orphans"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-orphans-4" "Test" nil)
          plan-id (:id p)]
      (fact/create helper/*conn* plan-id "fact-1" "Fact 1" "Content")
      (fact/create helper/*conn* plan-id "fact-2" "Fact 2" "Content")
      (let [deleted-count (fact/delete-orphans helper/*conn* plan-id ["fact-1" "fact-2"])]
        (is (= 0 deleted-count))
        (is (= 2 (count (fact/get-by-plan helper/*conn* plan-id)))))))

  (testing "delete-orphans with no facts returns 0"
    (main/create-schema! helper/*conn*)
    (let [p (plan/create helper/*conn* "test-orphans-5" "Test" nil)]
      (is (= 0 (fact/delete-orphans helper/*conn* (:id p) ["any-name"]))))))

(deftest search-test
  (testing "search finds matching facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-search" "Test" nil)]
      (fact/create helper/*conn* (:id plan) "key-fact" "Key fact" "Content")
      (let [results (fact/search helper/*conn* "fact")]
        (is (= 1 (count results)))
        (is (= "key-fact" (:name (first results)))))))

  (testing "search returns empty for no matches"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-search-2" "Test" nil)]
      (fact/create helper/*conn* (:id plan) "fact" "Description" "Content")
      (is (empty? (fact/search helper/*conn* "nonexistent")))))

  (testing "search matches on name, description, and content"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-search-3" "Test" nil)]
      (fact/create helper/*conn* (:id plan) "alpha" "beta" "gamma")
      (is (= 1 (count (fact/search helper/*conn* "alpha"))))
      (is (= 1 (count (fact/search helper/*conn* "beta"))))
      (is (= 1 (count (fact/search helper/*conn* "gamma")))))))


