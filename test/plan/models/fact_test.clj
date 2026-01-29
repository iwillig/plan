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
                     :description "Test"
                     :content "Content"
                     :created_at "2024-01-01"
                     :updated_at "2024-01-01"})))
  (testing "Fact schema allows nil for optional fields"
    (is (malli.core/validate fact/Fact
                             {:id nil
                              :plan_id 1
                              :description "Test"
                              :content nil
                              :created_at nil
                              :updated_at nil}))))

(deftest create-test
  (testing "create returns a fact with generated fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test plan" nil)
          result (fact/create helper/*conn* (:id plan) "Test fact" "Content")]
      (is (some? result))
      (is (= "Test fact" (:description result)))
      (is (= "Content" (:content result)))
      (is (= (:id plan) (:plan_id result)))
      (is (number? (:id result)))
      (is (some? (:created_at result)))))

  (testing "create handles nil content"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          result (fact/create helper/*conn* (:id plan) "Fact with no content" nil)]
      (is (= "Fact with no content" (:description result)))
      (is (nil? (:content result))))))

(deftest get-by-id-test
  (testing "get-by-id returns fact when found"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          created (fact/create helper/*conn* (:id plan) "Test fact" nil)
          fetched (fact/get-by-id helper/*conn* (:id created))]
      (is (= (:id created) (:id fetched)))
      (is (= "Test fact" (:description fetched)))))

  (testing "get-by-id returns nil when not found"
    (main/create-schema! helper/*conn*)
    (is (nil? (fact/get-by-id helper/*conn* 999)))))

(deftest get-by-plan-test
  (testing "get-by-plan returns facts for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          plan-id (:id plan)]
      (fact/create helper/*conn* plan-id "Fact 1" nil)
      (fact/create helper/*conn* plan-id "Fact 2" nil)
      (let [facts (fact/get-by-plan helper/*conn* plan-id)]
        (is (= 2 (count facts))))))

  (testing "get-by-plan returns empty for plan with no facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)]
      (is (empty? (fact/get-by-plan helper/*conn* (:id plan)))))))

(deftest get-all-test
  (testing "get-all returns all facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)]
      (fact/create helper/*conn* (:id plan) "Fact 1" nil)
      (fact/create helper/*conn* (:id plan) "Fact 2" nil)
      (is (= 2 (count (fact/get-all helper/*conn*)))))))

(deftest update-test
  (testing "update modifies fact fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          created (fact/create helper/*conn* (:id plan) "Original" "Content")
          updated (fact/update helper/*conn* (:id created) {:description "Updated"})]
      (is (= "Updated" (:description updated)))
      (is (= "Content" (:content updated)))))

  (testing "update can modify multiple fields"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          created (fact/create helper/*conn* (:id plan) "Original" "Original content")
          updated (fact/update helper/*conn* (:id created) {:description "New" :content "New content"})]
      (is (= "New" (:description updated)))
      (is (= "New content" (:content updated)))))

  (testing "update returns nil for non-existent fact"
    (main/create-schema! helper/*conn*)
    (is (nil? (fact/update helper/*conn* 999 {:description "Test"}))))

  (testing "update returns nil for empty updates"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          created (fact/create helper/*conn* (:id plan) "Test" nil)]
      (is (nil? (fact/update helper/*conn* (:id created) {}))))))

(deftest delete-test
  (testing "delete removes fact and returns true"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          created (fact/create helper/*conn* (:id plan) "To delete" nil)]
      (is (fact/delete helper/*conn* (:id created)))
      (is (nil? (fact/get-by-id helper/*conn* (:id created))))))

  (testing "delete returns false for non-existent fact"
    (main/create-schema! helper/*conn*)
    (is (false? (fact/delete helper/*conn* 999)))))

(deftest delete-by-plan-test
  (testing "delete-by-plan removes all facts for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)
          plan-id (:id plan)]
      (fact/create helper/*conn* plan-id "Fact 1" nil)
      (fact/create helper/*conn* plan-id "Fact 2" nil)
      (is (= 2 (fact/delete-by-plan helper/*conn* plan-id)))
      (is (empty? (fact/get-by-plan helper/*conn* plan-id))))))

(deftest search-test
  (testing "search finds matching facts"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "Test" nil)]
      (fact/create helper/*conn* (:id plan) "Key fact" "Content")
      (let [results (fact/search helper/*conn* "fact")]
        (is (= 1 (count results)))
        (is (= "Key fact" (:description (first results))))))))


