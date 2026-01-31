(ns plan.schemas-test
  "Tests for the centralized Malli schemas."
  (:require
   [clojure.test :refer [deftest is testing]]
   [plan.schemas :as schemas]))

;; -----------------------------------------------------------------------------
;; Plan Schema Tests
;; -----------------------------------------------------------------------------

(deftest plan-create-schema-test
  (testing "valid plan create"
    (is (schemas/valid-plan-create? {:name "test-plan"}))
    (is (schemas/valid-plan-create? {:name "test-plan"
                                     :description "A description"
                                     :content "Some content"})))

  (testing "invalid plan create - missing name"
    (is (not (schemas/valid-plan-create? {})))
    (is (= {:name ["missing required key"]}
           (schemas/explain-plan-create {}))))

  (testing "invalid plan create - empty name"
    (is (not (schemas/valid-plan-create? {:name ""})))
    (is (= {:name ["should be at least 1 character"]}
           (schemas/explain-plan-create {:name ""})))))

(deftest plan-update-schema-test
  (testing "valid plan update"
    (is (schemas/valid-plan-update? {:name "new-name"}))
    (is (schemas/valid-plan-update? {:description "New description"}))
    (is (schemas/valid-plan-update? {:completed true}))
    (is (schemas/valid-plan-update? {:name "new"
                                     :description "New"
                                     :content "New content"
                                     :completed true})))

  (testing "empty update is valid (business logic handles this)"
    (is (schemas/valid-plan-update? {}))))

;; -----------------------------------------------------------------------------
;; Task Schema Tests
;; -----------------------------------------------------------------------------

(deftest task-create-schema-test
  (testing "valid task create"
    (is (schemas/valid-task-create? {:plan_id 1 :name "task-1"}))
    (is (schemas/valid-task-create? {:plan_id 1
                                     :name "task-1"
                                     :description "Description"
                                     :status "pending"
                                     :priority 50})))

  (testing "invalid task create - missing required fields"
    (is (not (schemas/valid-task-create? {:name "task-1"})))
    (is (not (schemas/valid-task-create? {:plan_id 1}))))

  (testing "invalid task create - invalid status"
    (is (not (schemas/valid-task-create? {:plan_id 1
                                          :name "task-1"
                                          :status "invalid"})))))

;; -----------------------------------------------------------------------------
;; Fact Schema Tests
;; -----------------------------------------------------------------------------

(deftest fact-create-schema-test
  (testing "valid fact create"
    (is (schemas/valid-fact-create? {:plan_id 1
                                     :name "fact-1"
                                     :content "Some content"}))
    (is (schemas/valid-fact-create? {:plan_id 1
                                     :name "fact-1"
                                     :description "Description"
                                     :content "Content"})))

  (testing "invalid fact create - missing required fields"
    (is (not (schemas/valid-fact-create? {:plan_id 1 :name "fact-1"})))
    (is (not (schemas/valid-fact-create? {:plan_id 1 :content "Content"})))
    (is (not (schemas/valid-fact-create? {:name "fact-1" :content "Content"}))))

  (testing "invalid fact create - empty content"
    (is (not (schemas/valid-fact-create? {:plan_id 1
                                          :name "fact-1"
                                          :content ""})))))

;; -----------------------------------------------------------------------------
;; Trace Schema Tests
;; -----------------------------------------------------------------------------

(deftest trace-create-schema-test
  (testing "valid trace create"
    (is (schemas/valid-trace-create? {:task-id 1
                                      :trace-type "thought"
                                      :content "Thinking about the problem"})))

  (testing "all valid trace types"
    (doseq [trace-type ["thought" "action" "observation" "reflection"]]
      (is (schemas/valid-trace-create? {:task-id 1
                                        :trace-type trace-type
                                        :content "Content"})
          (str "Failed for type: " trace-type))))

  (testing "invalid trace type"
    (is (not (schemas/valid-trace-create? {:task-id 1
                                           :trace-type "invalid"
                                           :content "Content"})))))

;; -----------------------------------------------------------------------------
;; Lesson Schema Tests
;; -----------------------------------------------------------------------------

(deftest lesson-create-schema-test
  (testing "valid lesson create"
    (is (schemas/valid-lesson-create? {:lesson-type "success_pattern"
                                       :lesson-content "Always test edge cases"})))

  (testing "all valid lesson types"
    (doseq [lesson-type ["success_pattern" "failure_pattern" "constraint" "technique"]]
      (is (schemas/valid-lesson-create? {:lesson-type lesson-type
                                         :lesson-content "Content"})
          (str "Failed for type: " lesson-type))))

  (testing "invalid lesson type"
    (is (not (schemas/valid-lesson-create? {:lesson-type "invalid"
                                            :lesson-content "Content"}))))

  (testing "with optional fields"
    (is (schemas/valid-lesson-create? {:plan-id 1
                                       :task-id 2
                                       :lesson-type "constraint"
                                       :trigger-condition "When X happens"
                                       :lesson-content "Do Y"
                                       :confidence 0.8}))))

;; -----------------------------------------------------------------------------
;; Validation Helper Tests
;; -----------------------------------------------------------------------------

(deftest validate-helper-test
  (testing "validate returns data when valid"
    (let [data {:name "test"}
          result (schemas/validate schemas/PlanCreate data)]
      (is (= data result))
      (is (not (schemas/validation-failed? result)))))

  (testing "validate returns errors when invalid"
    (let [result (schemas/validate schemas/PlanCreate {:name ""})]
      (is (schemas/validation-failed? result))
      (is (= {:name ["should be at least 1 character"]}
             (schemas/validation-errors result)))))

  (testing "validation-errors returns nil for valid data"
    (let [result (schemas/validate schemas/PlanCreate {:name "test"})]
      (is (nil? (schemas/validation-errors result))))))

;; -----------------------------------------------------------------------------
;; Compiled Validator Performance Test
;; -----------------------------------------------------------------------------

(deftest compiled-validator-test
  (testing "compiled validators are functions"
    (is (fn? schemas/valid-plan-create?))
    (is (fn? schemas/valid-task-create?))
    (is (fn? schemas/valid-fact-create?))
    (is (fn? schemas/valid-trace-create?))
    (is (fn? schemas/valid-lesson-create?)))

  (testing "compiled validators work correctly"
    (is (true? (schemas/valid-plan-create? {:name "test"})))
    (is (false? (schemas/valid-plan-create? {:name ""})))))
