(ns plan.operations.lesson-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [failjure.core :as f]
   [plan.main :as main]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.operations.lesson :as ops]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

;; -----------------------------------------------------------------------------
;; create-lesson Tests
;; -----------------------------------------------------------------------------

(deftest create-lesson-test
  (main/create-schema! helper/*conn*)

  (testing "creates lesson with required params"
    (let [result (ops/create-lesson helper/*conn*
                                    {:lesson-type "success_pattern"
                                     :lesson-content "Always test edge cases"})]
      (is (f/ok? result))
      (is (number? (:id result)))
      (is (= "success_pattern" (:lesson_type result)))
      (is (= 0.5 (:confidence result)))))

  (testing "creates lesson with plan-id"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          result (ops/create-lesson helper/*conn*
                                    {:plan-id (:id p)
                                     :lesson-type "constraint"
                                     :lesson-content "Max 100 items per batch"})]
      (is (f/ok? result))
      (is (= (:id p) (:plan_id result)))))

  (testing "creates lesson with task-id"
    (let [p (plan/create helper/*conn* "task-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          result (ops/create-lesson helper/*conn*
                                    {:task-id (:id t)
                                     :lesson-type "technique"
                                     :lesson-content "Use parallel processing"})]
      (is (f/ok? result))
      (is (= (:id t) (:task_id result)))))

  (testing "creates lesson with all fields"
    (let [p (plan/create helper/*conn* "full-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          result (ops/create-lesson helper/*conn*
                                    {:plan-id (:id p)
                                     :task-id (:id t)
                                     :lesson-type "failure_pattern"
                                     :trigger-condition "When API returns 429"
                                     :lesson-content "Implement exponential backoff"
                                     :confidence 0.8})]
      (is (f/ok? result))
      (is (= "When API returns 429" (:trigger_condition result)))
      (is (= 0.8 (:confidence result)))))

  (testing "accepts all valid lesson types"
    (doseq [lesson-type ["success_pattern" "failure_pattern" "constraint" "technique"]]
      (let [result (ops/create-lesson helper/*conn*
                                      {:lesson-type lesson-type
                                       :lesson-content (str "Content for " lesson-type)})]
        (is (f/ok? result) (str "Failed for type: " lesson-type)))))

  (testing "fails with invalid lesson type"
    (let [result (ops/create-lesson helper/*conn*
                                    {:lesson-type "invalid"
                                     :lesson-content "Content"})]
      (is (f/failed? result))
      (is (re-find #"Invalid lesson type" (f/message result)))))

  (testing "fails when lesson-type missing"
    (let [result (ops/create-lesson helper/*conn*
                                    {:lesson-content "Content"})]
      (is (f/failed? result))
      (is (re-find #"Missing required parameters" (f/message result)))))

  (testing "fails when lesson-content missing"
    (let [result (ops/create-lesson helper/*conn*
                                    {:lesson-type "constraint"})]
      (is (f/failed? result))
      (is (re-find #"Missing required parameters" (f/message result)))))

  (testing "fails when plan does not exist"
    (let [result (ops/create-lesson helper/*conn*
                                    {:plan-id 999
                                     :lesson-type "constraint"
                                     :lesson-content "Content"})]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result)))))

  (testing "fails when task does not exist"
    (let [result (ops/create-lesson helper/*conn*
                                    {:task-id 999
                                     :lesson-type "constraint"
                                     :lesson-content "Content"})]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; get-lesson Tests
;; -----------------------------------------------------------------------------

(deftest get-lesson-test
  (main/create-schema! helper/*conn*)

  (testing "returns lesson when found"
    (let [created (ops/create-lesson helper/*conn*
                                     {:lesson-type "constraint"
                                      :lesson-content "Test content"})
          result (ops/get-lesson helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (= "constraint" (:lesson_type result)))))

  (testing "fails when lesson not found"
    (let [result (ops/get-lesson helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Lesson not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; list-all-lessons Tests
;; -----------------------------------------------------------------------------

(deftest list-all-lessons-test
  (main/create-schema! helper/*conn*)

  (testing "returns all lessons"
    (ops/create-lesson helper/*conn* {:lesson-type "constraint" :lesson-content "L1"})
    (ops/create-lesson helper/*conn* {:lesson-type "technique" :lesson-content "L2"})
    (let [result (ops/list-all-lessons helper/*conn* {})]
      (is (= 2 (count result)))))

  (testing "filters by min-confidence"
    (ops/create-lesson helper/*conn* {:lesson-type "constraint" :lesson-content "Low" :confidence 0.2})
    (ops/create-lesson helper/*conn* {:lesson-type "technique" :lesson-content "High" :confidence 0.9})
    (let [result (ops/list-all-lessons helper/*conn* {:min-confidence 0.5})]
      ;; At least the high confidence one, plus previous tests created some
      (is (every? #(>= (:confidence %) 0.5) result))))

  (testing "filters by max-confidence"
    (let [result (ops/list-all-lessons helper/*conn* {:max-confidence 0.3})]
      (is (every? #(<= (:confidence %) 0.3) result))))

  (testing "filters by lesson-type"
    (let [result (ops/list-all-lessons helper/*conn* {:lesson-type "constraint"})]
      (is (every? #(= "constraint" (:lesson_type %)) result)))))

;; -----------------------------------------------------------------------------
;; list-plan-lessons Tests
;; -----------------------------------------------------------------------------

(deftest list-plan-lessons-test
  (main/create-schema! helper/*conn*)

  (testing "returns lessons for plan"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)]
      (ops/create-lesson helper/*conn* {:plan-id (:id p)
                                        :lesson-type "constraint"
                                        :lesson-content "Plan lesson"})
      (let [result (ops/list-plan-lessons helper/*conn* (:id p) {})]
        (is (f/ok? result))
        (is (= 1 (count result))))))

  (testing "fails when plan does not exist"
    (let [result (ops/list-plan-lessons helper/*conn* 999 {})]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; list-task-lessons Tests
;; -----------------------------------------------------------------------------

(deftest list-task-lessons-test
  (main/create-schema! helper/*conn*)

  (testing "returns lessons for task"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)]
      (ops/create-lesson helper/*conn* {:task-id (:id t)
                                        :lesson-type "technique"
                                        :lesson-content "Task lesson"})
      (let [result (ops/list-task-lessons helper/*conn* (:id t))]
        (is (f/ok? result))
        (is (= 1 (count result))))))

  (testing "fails when task does not exist"
    (let [result (ops/list-task-lessons helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; search-lessons Tests
;; -----------------------------------------------------------------------------

(deftest search-lessons-test
  (main/create-schema! helper/*conn*)

  (testing "finds matching lessons"
    (ops/create-lesson helper/*conn* {:lesson-type "constraint"
                                      :lesson-content "Database connection limit is 100"})
    (let [result (ops/search-lessons helper/*conn* "database")]
      (is (f/ok? result))
      (is (>= (count result) 1))))

  (testing "returns empty for no matches"
    (let [result (ops/search-lessons helper/*conn* "xyznonexistent")]
      (is (f/ok? result))
      (is (empty? result))))

  (testing "fails on empty query"
    (let [result (ops/search-lessons helper/*conn* "")]
      (is (f/failed? result))
      (is (re-find #"cannot be empty" (f/message result)))))

  (testing "fails on blank query"
    (let [result (ops/search-lessons helper/*conn* "   ")]
      (is (f/failed? result))
      (is (re-find #"cannot be empty" (f/message result))))))

;; -----------------------------------------------------------------------------
;; validate-lesson Tests
;; -----------------------------------------------------------------------------

(deftest validate-lesson-test
  (main/create-schema! helper/*conn*)

  (testing "increases confidence"
    (let [created (ops/create-lesson helper/*conn*
                                     {:lesson-type "constraint"
                                      :lesson-content "Test"
                                      :confidence 0.5})
          result (ops/validate-lesson helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (> (:confidence result) 0.5))
      (is (= 1 (:times_validated result)))))

  (testing "fails when lesson not found"
    (let [result (ops/validate-lesson helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Lesson not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; invalidate-lesson Tests
;; -----------------------------------------------------------------------------

(deftest invalidate-lesson-test
  (main/create-schema! helper/*conn*)

  (testing "decreases confidence"
    (let [created (ops/create-lesson helper/*conn*
                                     {:lesson-type "constraint"
                                      :lesson-content "Test"
                                      :confidence 0.5})
          result (ops/invalidate-lesson helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (< (:confidence result) 0.5))))

  (testing "fails when lesson not found"
    (let [result (ops/invalidate-lesson helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Lesson not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; delete-lesson Tests
;; -----------------------------------------------------------------------------

(deftest delete-lesson-test
  (main/create-schema! helper/*conn*)

  (testing "deletes existing lesson"
    (let [created (ops/create-lesson helper/*conn*
                                     {:lesson-type "constraint"
                                      :lesson-content "To delete"})
          result (ops/delete-lesson helper/*conn* (:id created))]
      (is (f/ok? result))
      (is (true? (:deleted result)))
      ;; Verify deleted
      (is (f/failed? (ops/get-lesson helper/*conn* (:id created))))))

  (testing "fails when lesson not found"
    (let [result (ops/delete-lesson helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Lesson not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; delete-plan-lessons Tests
;; -----------------------------------------------------------------------------

(deftest delete-plan-lessons-test
  (main/create-schema! helper/*conn*)

  (testing "deletes all lessons for plan"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)]
      (ops/create-lesson helper/*conn* {:plan-id (:id p)
                                        :lesson-type "constraint"
                                        :lesson-content "L1"})
      (ops/create-lesson helper/*conn* {:plan-id (:id p)
                                        :lesson-type "technique"
                                        :lesson-content "L2"})
      (let [result (ops/delete-plan-lessons helper/*conn* (:id p))]
        (is (f/ok? result))
        (is (true? (:deleted result)))
        ;; Verify deleted
        (is (empty? (ops/list-plan-lessons helper/*conn* (:id p) {}))))))

  (testing "fails when plan does not exist"
    (let [result (ops/delete-plan-lessons helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Plan not found" (f/message result))))))

;; -----------------------------------------------------------------------------
;; delete-task-lessons Tests
;; -----------------------------------------------------------------------------

(deftest delete-task-lessons-test
  (main/create-schema! helper/*conn*)

  (testing "deletes all lessons for task"
    (let [p (plan/create helper/*conn* "test-plan" "Test" nil)
          t (task/create helper/*conn* (:id p) "task-1" nil nil nil)]
      (ops/create-lesson helper/*conn* {:task-id (:id t)
                                        :lesson-type "constraint"
                                        :lesson-content "L1"})
      (let [result (ops/delete-task-lessons helper/*conn* (:id t))]
        (is (f/ok? result))
        (is (true? (:deleted result)))
        ;; Verify deleted
        (is (empty? (ops/list-task-lessons helper/*conn* (:id t)))))))

  (testing "fails when task does not exist"
    (let [result (ops/delete-task-lessons helper/*conn* 999)]
      (is (f/failed? result))
      (is (re-find #"Task not found" (f/message result))))))
