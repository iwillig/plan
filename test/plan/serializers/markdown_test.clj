(ns plan.serializers.markdown-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.serializers.markdown :as md-serializer]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest test-yaml-escape-string
  (testing "simple strings are not escaped"
    (is (= "hello" (#'md-serializer/yaml-escape-string "hello")))
    (is (= "simple name" (#'md-serializer/yaml-escape-string "simple name"))))

  (testing "strings with special characters are quoted"
    (is (= "\"hello: world\"" (#'md-serializer/yaml-escape-string "hello: world")))
    (is (= "\"value, with comma\"" (#'md-serializer/yaml-escape-string "value, with comma"))))

  (testing "multiline strings use literal block"
    (is (str/starts-with? (#'md-serializer/yaml-escape-string "line1\nline2") "|\n"))))

(deftest test-encode-plan-fields
  (testing "plan fields get plan_ prefix"
    (let [plan {:id 1 :name "Test" :description "Desc" :completed true}
          result (#'md-serializer/encode-plan-fields plan)]
      (is (= 1 (:plan_id result)))
      (is (= "Test" (:plan_name result)))
      (is (= "Desc" (:plan_description result)))
      (is (= true (:plan_completed result))))))

(deftest test-encode-task-fields
  (testing "task fields get task_N_ prefix"
    (let [task {:id 10 :name "Task 1" :completed false}
          result (#'md-serializer/encode-task-fields 0 task)]
      (is (= 10 (:task_0_id result)))
      (is (= "Task 1" (:task_0_name result)))
      (is (= false (:task_0_completed result))))

    (testing "different indices get different prefixes"
      (let [task {:name "Task 2"}
            result (#'md-serializer/encode-task-fields 3 task)]
        (is (= "Task 2" (:task_3_name result)))))))

(deftest test-decode-plan-fields
  (testing "decodes plan fields from flat keys"
    (let [data {:plan_id "1" :plan_name "Test" :plan_description "Desc" :plan_completed "true"}
          result (#'md-serializer/decode-plan-fields data)]
      (is (= "1" (:id result)))
      (is (= "Test" (:name result)))
      (is (= "Desc" (:description result)))
      (is (= true (:completed result)))))

  (testing "ignores non-plan fields"
    (let [data {:plan_name "Test" :task_0_name "Task" :other "value"}
          result (#'md-serializer/decode-plan-fields data)]
      (is (= "Test" (:name result)))
      (is (nil? (:task_0_name result)))
      (is (nil? (:other result))))))

(deftest test-decode-task-fields
  (testing "decodes task fields from flat keys"
    (let [data {:task_0_name "Task 1" :task_0_completed "true"
                :task_1_name "Task 2" :task_1_completed "false"}
          result (#'md-serializer/decode-task-fields data)]
      (is (= 2 (count result)))
      (is (= "Task 1" (:name (nth result 0))))
      (is (= true (:completed (nth result 0))))
      (is (= "Task 2" (:name (nth result 1))))
      (is (= false (:completed (nth result 1))))))

  (testing "returns empty vector when no tasks"
    (let [data {:plan_name "Test"}
          result (#'md-serializer/decode-task-fields data)]
      (is (= [] result)))))

(deftest test-decode-fact-fields
  (testing "decodes fact fields from flat keys"
    (let [data {:fact_0_name "Fact 1" :fact_0_description "Desc 1"
                :fact_1_name "Fact 2" :fact_1_description "Desc 2"}
          result (#'md-serializer/decode-fact-fields data)]
      (is (= 2 (count result)))
      (is (= "Fact 1" (:name (nth result 0))))
      (is (= "Fact 2" (:name (nth result 1))))))

  (testing "returns empty vector when no facts"
    (let [data {:plan_name "Test"}
          result (#'md-serializer/decode-fact-fields data)]
      (is (= [] result)))))

(deftest test-plan->markdown
  (testing "basic plan serialization"
    (let [plan {:name "Test Plan"
                :description "A test plan"
                :content "# Test Plan\n\nThis is the content."
                :completed false}
          tasks []
          facts []
          result (md-serializer/plan->markdown plan tasks facts)]
      (is (str/includes? result "---"))
      (is (str/includes? result "plan_name: Test Plan"))
      (is (str/includes? result "plan_description: A test plan"))
      (is (str/includes? result "plan_completed: false"))
      (is (str/includes? result "# Test Plan"))
      (is (str/includes? result "This is the content."))))

  (testing "plan with tasks"
    (let [plan {:name "Test Plan" :description nil :content "" :completed false}
          tasks [{:name "Task 1" :description "First task" :completed true :content nil}
                 {:name "Task 2" :description nil :completed false :content "Task content"}]
          facts []
          result (md-serializer/plan->markdown plan tasks facts)]
      (is (str/includes? result "task_0_name: Task 1"))
      (is (str/includes? result "task_0_completed: true"))
      (is (str/includes? result "task_1_name: Task 2"))
      (is (str/includes? result "task_1_completed: false"))))

  (testing "plan with facts"
    (let [plan {:name "Test Plan" :description nil :content "" :completed false}
          tasks []
          facts [{:name "Fact 1" :description "Important fact" :content "Fact details"}]
          result (md-serializer/plan->markdown plan tasks facts)]
      (is (str/includes? result "fact_0_name: Fact 1"))
      (is (str/includes? result "fact_0_description: Important fact"))))

  (testing "multiline content is preserved"
    (let [plan {:name "Test Plan"
                :description nil
                :content "# Heading\n\nParagraph 1\n\nParagraph 2"
                :completed false}
          result (md-serializer/plan->markdown plan [] [])]
      (is (str/includes? result "# Heading"))
      (is (str/includes? result "Paragraph 1"))
      (is (str/includes? result "Paragraph 2"))))

  (testing "plan with no content omits body"
    (let [plan {:name "Test Plan" :description nil :content "" :completed false}
          result (md-serializer/plan->markdown plan [] [])]
      (is (str/ends-with? result "---")))))

(deftest test-markdown->plan
  (testing "basic plan deserialization"
    (let [markdown "---
plan_name: Test Plan
plan_description: A test plan
plan_completed: false
---

# Test Plan

This is the content."
          result (md-serializer/markdown->plan markdown)]
      (is (= "Test Plan" (get-in result [:plan :name])))
      (is (= "A test plan" (get-in result [:plan :description])))
      (is (= false (get-in result [:plan :completed])))
      (is (str/includes? (get-in result [:plan :content]) "# Test Plan"))
      (is (str/includes? (get-in result [:plan :content]) "This is the content."))))

  (testing "deserialization with tasks"
    (let [markdown "---
plan_name: Test Plan
plan_completed: false
task_0_name: Task 1
task_0_description: First task
task_0_completed: true
task_1_name: Task 2
task_1_completed: false
---

Content here."
          result (md-serializer/markdown->plan markdown)]
      (is (= 2 (count (:tasks result))))
      (is (= "Task 1" (get-in result [:tasks 0 :name])))
      (is (= "First task" (get-in result [:tasks 0 :description])))
      (is (= true (get-in result [:tasks 0 :completed])))
      (is (= "Task 2" (get-in result [:tasks 1 :name])))
      (is (= false (get-in result [:tasks 1 :completed])))))

  (testing "deserialization with facts"
    (let [markdown "---
plan_name: Test Plan
plan_completed: false
fact_0_name: Fact 1
fact_0_description: Important fact
fact_0_content: Fact details here
---

Content."
          result (md-serializer/markdown->plan markdown)]
      (is (= 1 (count (:facts result))))
      (is (= "Fact 1" (get-in result [:facts 0 :name])))
      (is (= "Important fact" (get-in result [:facts 0 :description])))
      (is (= "Fact details here" (get-in result [:facts 0 :content])))))

  (testing "round-trip serialization preserves data"
    (let [original-plan {:name "Round Trip Plan"
                         :description "Testing round trip"
                         :content "# Original Content\n\nWith multiple paragraphs."
                         :completed true}
          original-tasks [{:name "Task A" :description "Desc A" :completed true :content nil}
                          {:name "Task B" :description nil :completed false :content "Task body"}]
          original-facts [{:name "Fact X" :description "Fact desc" :content "Fact body"}]
          markdown (md-serializer/plan->markdown original-plan original-tasks original-facts)
          result (md-serializer/markdown->plan markdown)]
      (is (= (:name original-plan) (get-in result [:plan :name])))
      (is (= (:description original-plan) (get-in result [:plan :description])))
      (is (= true (get-in result [:plan :completed])))
      (is (str/includes? (get-in result [:plan :content]) "# Original Content"))
      (is (= 2 (count (:tasks result))))
      (is (= "Task A" (get-in result [:tasks 0 :name])))
      (is (= "Task B" (get-in result [:tasks 1 :name])))
      (is (= 1 (count (:facts result))))
      (is (= "Fact X" (get-in result [:facts 0 :name]))))))

(deftest test-valid-plan-markdown?
  (testing "valid markdown passes validation"
    (let [markdown "---
plan_name: Valid Plan
plan_completed: false
---

Content."]
      (is (md-serializer/valid-plan-markdown? markdown))))

  (testing "missing plan name fails validation"
    (let [markdown "---
plan_description: No name
---

Content."]
      (is (not (md-serializer/valid-plan-markdown? markdown)))))

  (testing "missing front matter fails validation"
    (let [markdown "# Just Markdown\n\nNo front matter here."]
      (is (not (md-serializer/valid-plan-markdown? markdown)))))

  (testing "empty plan name fails validation"
    (let [markdown "---
plan_name:
---

Content."]
      (is (not (md-serializer/valid-plan-markdown? markdown))))))

(deftest test-file-io
  (testing "write and read round-trip"
    (let [tmp-file (str "/tmp/test-plan-" (System/currentTimeMillis) ".md")
          plan {:name "File Test Plan"
                :description "Testing file I/O"
                :content "# File Content"
                :completed false}
          tasks [{:name "File Task" :description nil :completed false :content nil}]
          facts []]
      (try
        (md-serializer/write-plan-to-file tmp-file plan tasks facts)
        (let [result (md-serializer/read-plan-from-file tmp-file)]
          (is (= "File Test Plan" (get-in result [:plan :name])))
          (is (= "Testing file I/O" (get-in result [:plan :description])))
          (is (= 1 (count (:tasks result))))
          (is (= "File Task" (get-in result [:tasks 0 :name]))))
        (finally
          (when (.exists (java.io.File. tmp-file))
            (.delete (java.io.File. tmp-file))))))))

(deftest test-metadata-helpers
  (testing "get-plan-metadata extracts plan without tasks/facts"
    (let [markdown "---
plan_name: My Plan
plan_description: Desc
task_0_name: Task 1
fact_0_name: Fact 1
---

Content here."
          result (md-serializer/get-plan-metadata markdown)]
      (is (= "My Plan" (:name result)))
      (is (= "Desc" (:description result)))
      (is (= "Content here." (:content result)))
      (is (nil? (:task_0_name result)))
      (is (nil? (:fact_0_name result)))))

  (testing "count-tasks returns correct count"
    (let [markdown "---
plan_name: Test
task_0_name: Task 1
task_1_name: Task 2
---

Content"]
      (is (= 2 (md-serializer/count-tasks markdown)))))

  (testing "count-facts returns correct count"
    (let [markdown "---
plan_name: Test
fact_0_name: Fact 1
fact_1_name: Fact 2
fact_2_name: Fact 3
---

Content"]
      (is (= 3 (md-serializer/count-facts markdown))))))
