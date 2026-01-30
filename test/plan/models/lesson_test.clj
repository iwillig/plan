(ns plan.models.lesson-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.main :as main]
   [plan.models.lesson :as lesson]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest create-test
  (testing "create returns a lesson with default confidence"
    (main/create-schema! helper/*conn*)
    (let [result (lesson/create helper/*conn* {:lesson_type "technique"
                                               :lesson_content "Test lesson"})]
      (is (some? result))
      (is (= "technique" (:lesson_type result)))
      (is (= "Test lesson" (:lesson_content result)))
      (is (= 0.5 (:confidence result)))
      (is (= 0 (:times_validated result)))
      (is (number? (:id result)))))

  (testing "create accepts custom confidence"
    (main/create-schema! helper/*conn*)
    (let [result (lesson/create helper/*conn* {:lesson_type "constraint"
                                               :lesson_content "Test constraint"
                                               :confidence 0.8})]
      (is (= 0.8 (:confidence result)))))

  (testing "create with plan and task associations"
    (main/create-schema! helper/*conn*)
    (let [plan-data (plan/create helper/*conn* "test-plan" "Test plan" nil)
          plan-id (:id plan-data)
          task (task/create helper/*conn* plan-id "test-task" "Test task" nil nil)
          result (lesson/create helper/*conn* {:plan_id plan-id
                                               :task_id (:id task)
                                               :lesson_type "success_pattern"
                                               :trigger_condition "testing"
                                               :lesson_content "What worked"})]
      (is (= plan-id (:plan_id result)))
      (is (= (:id task) (:task_id result)))
      (is (= "success_pattern" (:lesson_type result)))
      (is (= "testing" (:trigger_condition result))))))

(deftest get-by-id-test
  (testing "get-by-id returns lesson when found"
    (main/create-schema! helper/*conn*)
    (let [created (lesson/create helper/*conn* {:lesson_type "technique"
                                                :lesson_content "Test"})
          fetched (lesson/get-by-id helper/*conn* (:id created))]
      (is (= (:id created) (:id fetched)))
      (is (= "technique" (:lesson_type fetched)))))

  (testing "get-by-id returns nil when not found"
    (main/create-schema! helper/*conn*)
    (is (nil? (lesson/get-by-id helper/*conn* 999)))))

(deftest get-all-test
  (testing "get-all returns lessons ordered by confidence desc"
    (main/create-schema! helper/*conn*)
    (lesson/create helper/*conn* {:lesson_type "technique" :lesson_content "Low confidence" :confidence 0.3})
    (lesson/create helper/*conn* {:lesson_type "technique" :lesson_content "High confidence" :confidence 0.9})
    (let [lessons (lesson/get-all helper/*conn*)]
      (is (= 2 (count lessons)))
      (is (= "High confidence" (:lesson_content (first lessons))))
      (is (= "Low confidence" (:lesson_content (second lessons)))))))

(deftest get-by-plan-test
  (testing "get-by-plan returns only lessons for that plan"
    (main/create-schema! helper/*conn*)
    (let [plan1 (plan/create helper/*conn* "plan-1" "Plan 1" nil)
          plan2 (plan/create helper/*conn* "plan-2" "Plan 2" nil)]
      (lesson/create helper/*conn* {:plan_id (:id plan1) :lesson_type "technique" :lesson_content "Plan 1 lesson"})
      (lesson/create helper/*conn* {:plan_id (:id plan2) :lesson_type "technique" :lesson_content "Plan 2 lesson"})
      (let [lessons (lesson/get-by-plan helper/*conn* (:id plan1))]
        (is (= 1 (count lessons)))
        (is (= "Plan 1 lesson" (:lesson_content (first lessons))))))))

(deftest search-test
  (testing "search finds lessons by content"
    (main/create-schema! helper/*conn*)
    (lesson/create helper/*conn* {:lesson_type "technique" :lesson_content "Use Docker for containers"})
    (lesson/create helper/*conn* {:lesson_type "constraint" :lesson_content "API rate limits apply"})
    (let [results (lesson/search helper/*conn* "Docker")]
      (is (= 1 (count results)))
      (is (= "Use Docker for containers" (:lesson_content (first results)))))))

(deftest validate-test
  (testing "validate increases confidence and times_validated"
    (main/create-schema! helper/*conn*)
    (let [created (lesson/create helper/*conn* {:lesson_type "technique" :lesson_content "Test"})
          _ (lesson/validate helper/*conn* (:id created))
          validated (lesson/get-by-id helper/*conn* (:id created))]
      (is (>= (:confidence validated) 0.6))
      (is (= 1 (:times_validated validated)))))

  (testing "validate caps at 1.0"
    (main/create-schema! helper/*conn*)
    (let [created (lesson/create helper/*conn* {:lesson_type "technique" :lesson_content "Test" :confidence 0.95})]
      (lesson/validate helper/*conn* (:id created))
      (let [validated (lesson/get-by-id helper/*conn* (:id created))]
        (is (= 1.0 (:confidence validated)))))))

(deftest invalidate-test
  (testing "invalidate decreases confidence"
    (main/create-schema! helper/*conn*)
    (let [created (lesson/create helper/*conn* {:lesson_type "technique" :lesson_content "Test" :confidence 0.5})
          _ (lesson/invalidate helper/*conn* (:id created))
          invalidated (lesson/get-by-id helper/*conn* (:id created))]
      (is (<= (:confidence invalidated) 0.4))))

  (testing "invalidate floors at 0.0"
    (main/create-schema! helper/*conn*)
    (let [created (lesson/create helper/*conn* {:lesson_type "technique" :lesson_content "Test" :confidence 0.05})]
      (lesson/invalidate helper/*conn* (:id created))
      (let [invalidated (lesson/get-by-id helper/*conn* (:id created))]
        (is (= 0.0 (:confidence invalidated)))))))

(deftest delete-test
  (testing "delete removes lesson"
    (main/create-schema! helper/*conn*)
    (let [created (lesson/create helper/*conn* {:lesson_type "technique" :lesson_content "Test"})]
      (lesson/delete helper/*conn* (:id created))
      (is (nil? (lesson/get-by-id helper/*conn* (:id created)))))))

(deftest delete-by-plan-test
  (testing "delete-by-plan removes all lessons for a plan"
    (main/create-schema! helper/*conn*)
    (let [plan1 (plan/create helper/*conn* "plan-1" "Plan 1" nil)
          plan2 (plan/create helper/*conn* "plan-2" "Plan 2" nil)]
      (lesson/create helper/*conn* {:plan_id (:id plan1) :lesson_type "technique" :lesson_content "Plan 1"})
      (lesson/create helper/*conn* {:plan_id (:id plan1) :lesson_type "technique" :lesson_content "Plan 1 again"})
      (lesson/create helper/*conn* {:plan_id (:id plan2) :lesson_type "technique" :lesson_content "Plan 2"})
      (lesson/delete-by-plan helper/*conn* (:id plan1))
      (is (empty? (lesson/get-by-plan helper/*conn* (:id plan1))))
      (is (= 1 (count (lesson/get-by-plan helper/*conn* (:id plan2))))))))

(deftest delete-by-task-test
  (testing "delete-by-task removes all lessons for a task"
    (main/create-schema! helper/*conn*)
    (let [plan (plan/create helper/*conn* "test-plan" "Test plan" nil)
          task1 (task/create helper/*conn* (:id plan) "task-1" "Task 1" nil nil)
          task2 (task/create helper/*conn* (:id plan) "task-2" "Task 2" nil nil)]
      (lesson/create helper/*conn* {:plan_id (:id plan) :task_id (:id task1) :lesson_type "technique" :lesson_content "Task 1"})
      (lesson/create helper/*conn* {:plan_id (:id plan) :task_id (:id task2) :lesson_type "technique" :lesson_content "Task 2"})
      (lesson/delete-by-task helper/*conn* (:id task1))
      (is (empty? (filter #(= (:task_id %) (:id task1)) (lesson/get-all helper/*conn*)))))))

