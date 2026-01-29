(ns plan.models.plan-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [malli.core :as m]
   [plan.main :as main]
   [plan.models.plan :as plan]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest plan-schema-test
  (testing "Plan schema validates correct data"
    (is (m/validate plan/Plan
                    {:id 1
                     :description "Test"
                     :content "Content"
                     :completed false
                     :created_at "2024-01-01"
                     :updated_at "2024-01-01"})))
  (testing "Plan schema allows nil for optional fields"
    (is (m/validate plan/Plan
                    {:id nil
                     :description "Test"
                     :content nil
                     :completed true
                     :created_at nil
                     :updated_at nil}))))

(deftest create-test
  (testing "create returns a plan with generated fields"
    (main/create-schema! helper/*conn*)
    (let [result (plan/create helper/*conn* "Test plan" "Test content")]
      (is (some? result))
      (is (= "Test plan" (:description result)))
      (is (= "Test content" (:content result)))
      (is (number? (:id result)))
      (is (= false (:completed result)))
      (is (some? (:created_at result)))
      (is (some? (:updated_at result)))))

  (testing "create handles nil content"
    (main/create-schema! helper/*conn*)
    (let [result (plan/create helper/*conn* "Plan with no content" nil)]
      (is (= "Plan with no content" (:description result)))
      (is (nil? (:content result))))))

(deftest get-by-id-test
  (testing "get-by-id returns plan when found"
    (main/create-schema! helper/*conn*)
    (let [created (plan/create helper/*conn* "Test" nil)
          fetched (plan/get-by-id helper/*conn* (:id created))]
      (is (= (:id created) (:id fetched)))
      (is (= "Test" (:description fetched)))))

  (testing "get-by-id returns nil when not found"
    (main/create-schema! helper/*conn*)
    (is (nil? (plan/get-by-id helper/*conn* 999)))))

(deftest get-all-test
  (testing "get-all returns all plans ordered by created_at desc"
    (main/create-schema! helper/*conn*)
    (plan/create helper/*conn* "Plan 1" nil)
    (Thread/sleep 10)
    (plan/create helper/*conn* "Plan 2" nil)
    (Thread/sleep 10)
    (plan/create helper/*conn* "Plan 3" nil)
    (let [plans (plan/get-all helper/*conn*)]
      (is (= 3 (count plans)))
      (is (= "Plan 3" (:description (first plans))))
      (is (= "Plan 1" (:description (last plans)))))))

(deftest get-all-empty-test
  (testing "get-all returns empty vector when no plans"
    (main/create-schema! helper/*conn*)
    (is (empty? (plan/get-all helper/*conn*)))))

(deftest update-test
  (testing "update modifies plan fields"
    (main/create-schema! helper/*conn*)
    (let [created (plan/create helper/*conn* "Original" "Original content")
          updated (plan/update helper/*conn* (:id created) {:description "Updated"})]
      (is (= "Updated" (:description updated)))
      (is (= "Original content" (:content updated)))))

  (testing "update can modify multiple fields"
    (main/create-schema! helper/*conn*)
    (let [created (plan/create helper/*conn* "Original" "Original content")
          updated (plan/update helper/*conn* (:id created) {:description "New" :content "New content"})]
      (is (= "New" (:description updated)))
      (is (= "New content" (:content updated)))))

  (testing "update handles completed status"
    (main/create-schema! helper/*conn*)
    (let [created (plan/create helper/*conn* "Test" nil)
          updated (plan/update helper/*conn* (:id created) {:completed true})]
      (is (= true (:completed updated)))))

  (testing "update returns nil for non-existent plan"
    (main/create-schema! helper/*conn*)
    (is (nil? (plan/update helper/*conn* 999 {:description "Test"}))))

  (testing "update returns nil for empty updates"
    (main/create-schema! helper/*conn*)
    (let [created (plan/create helper/*conn* "Test" nil)]
      (is (nil? (plan/update helper/*conn* (:id created) {}))))))

(deftest delete-test
  (testing "delete removes plan and returns true"
    (main/create-schema! helper/*conn*)
    (let [created (plan/create helper/*conn* "To delete" nil)]
      (is (plan/delete helper/*conn* (:id created)))
      (is (nil? (plan/get-by-id helper/*conn* (:id created))))))

  (testing "delete returns false for non-existent plan"
    (main/create-schema! helper/*conn*)
    (is (false? (plan/delete helper/*conn* 999)))))

(deftest mark-completed-test
  (testing "mark-completed sets completed status"
    (main/create-schema! helper/*conn*)
    (let [created (plan/create helper/*conn* "Test" nil)
          completed (plan/mark-completed helper/*conn* (:id created) true)]
      (is (= true (:completed completed)))))

  (testing "mark-completed can un-complete"
    (main/create-schema! helper/*conn*)
    (let [created (plan/create helper/*conn* "Test" nil)
          _ (plan/mark-completed helper/*conn* (:id created) true)
          uncompleted (plan/mark-completed helper/*conn* (:id created) false)]
      (is (= false (:completed uncompleted))))))

(deftest search-test
  (testing "search finds matching plans"
    (main/create-schema! helper/*conn*)
    (plan/create helper/*conn* "Project planning" "Content about planning")
    (let [results (plan/search helper/*conn* "plan")]
      (is (= 1 (count results)))
      (is (= "Project planning" (:description (first results))))))

  (testing "search returns empty for no matches"
    (main/create-schema! helper/*conn*)
    (is (empty? (plan/search helper/*conn* "nonexistent")))))


