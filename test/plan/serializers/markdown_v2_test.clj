(ns plan.serializers.markdown-v2-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.serializers.markdown-v2 :as md-v2]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest test-generate-yaml-front-matter
  (testing "basic plan serialization generates hierarchical YAML"
    (let [plan {:name "Test Plan"
                :description "A test plan"
                :content "Plan content here"
                :completed false}
          result (md-v2/plan->markdown plan [] [])]
      (is (str/includes? result "---"))
      (is (str/includes? result "# Test Plan"))
      (is (str/includes? result "description: A test plan"))
      (is (str/includes? result "completed: false"))
      (is (str/includes? result "Plan content here"))))

  (testing "plan with tasks generates nested task list"
    (let [plan {:name "Test Plan" :description nil :content "" :completed false}
          tasks [{:name "Task 1" :description "First task" :completed true}
                 {:name "Task 2" :description nil :completed false}]
          result (md-v2/plan->markdown plan tasks [])]
      (is (str/includes? result "tasks:"))
      (is (str/includes? result "name: Task 1"))
      (is (str/includes? result "name: Task 2"))))

  (testing "plan with facts generates nested fact list"
    (let [plan {:name "Test Plan" :description nil :content "" :completed false}
          facts [{:name "Fact 1" :description "Important fact" :content "Fact details"}]
          result (md-v2/plan->markdown plan [] facts)]
      (is (str/includes? result "facts:"))
      (is (str/includes? result "name: Fact 1"))))

  (testing "multiline content is preserved after front matter"
    (let [plan {:name "Test Plan"
                :description nil
                :content "# Heading\n\nParagraph 1\n\nParagraph 2"
                :completed false}
          result (md-v2/plan->markdown plan [] [])]
      (is (str/includes? result "# Heading"))
      (is (str/includes? result "Paragraph 1"))
      (is (str/includes? result "Paragraph 2"))))

  (testing "plan with no content still has H1 header"
    (let [plan {:name "Test Plan" :description nil :content "" :completed false}
          result (md-v2/plan->markdown plan [] [])]
      (is (str/includes? result "# Test Plan"))
      (is (str/ends-with? result "---\n\n# Test Plan\n\n")))))

(deftest test-markdown->plan-hierarchical
  (testing "basic hierarchical deserialization"
    (let [markdown "---
description: A test plan
completed: false
---

# Test Plan

This is the content."
          result (md-v2/markdown->plan markdown)]
      (is (= "Test Plan" (get-in result [:plan :name])))
      (is (= "A test plan" (get-in result [:plan :description])))
      (is (= false (get-in result [:plan :completed])))
      (is (= "This is the content." (get-in result [:plan :content])))))

  (testing "deserialization with tasks in hierarchical format"
    (let [markdown "---
completed: false
tasks:
- name: Task 1
  description: First task
  completed: true
- name: Task 2
  completed: false
---

# Test Plan

Content here."
          result (md-v2/markdown->plan markdown)]
      (is (= "Test Plan" (get-in result [:plan :name])))
      (is (= 2 (count (:tasks result))))
      (is (= "Task 1" (get-in result [:tasks 0 :name])))
      (is (= "First task" (get-in result [:tasks 0 :description])))
      (is (= true (get-in result [:tasks 0 :completed])))
      (is (= "Task 2" (get-in result [:tasks 1 :name])))
      (is (= false (get-in result [:tasks 1 :completed])))))

  (testing "deserialization with facts in hierarchical format"
    (let [markdown "---
completed: false
facts:
- name: Fact 1
  description: Important fact
  content: Fact details here
---

# Test Plan

Content."
          result (md-v2/markdown->plan markdown)]
      (is (= "Test Plan" (get-in result [:plan :name])))
      (is (= 1 (count (:facts result))))
      (is (= "Fact 1" (get-in result [:facts 0 :name])))
      (is (= "Important fact" (get-in result [:facts 0 :description])))
      (is (= "Fact details here" (get-in result [:facts 0 :content])))))

  (testing "round-trip serialization preserves data"
    (let [original-plan {:name "Round Trip Plan"
                         :description "Testing round trip"
                         :content "Original content here.\n\nWith multiple paragraphs."
                         :completed true}
          original-tasks [{:name "Task A" :description "Desc A" :completed true :content nil}
                          {:name "Task B" :description nil :completed false :content "Task body"}]
          original-facts [{:name "Fact X" :description "Fact desc" :content "Fact body"}]
          markdown (md-v2/plan->markdown original-plan original-tasks original-facts)
          result (md-v2/markdown->plan markdown)]
      (is (= (:name original-plan) (get-in result [:plan :name])))
      (is (= (:description original-plan) (get-in result [:plan :description])))
      (is (= true (get-in result [:plan :completed])))
      (is (str/includes? (get-in result [:plan :content]) "Original content here."))
      (is (= 2 (count (:tasks result))))
      (is (= "Task A" (get-in result [:tasks 0 :name])))
      (is (= "Task B" (get-in result [:tasks 1 :name])))
      (is (= 1 (count (:facts result))))
      (is (= "Fact X" (get-in result [:facts 0 :name]))))))

(deftest test-valid-plan-markdown
  (testing "valid hierarchical markdown passes validation with H1"
    (let [markdown "---
description: Valid Plan
completed: false
---

# Valid Plan\n\nContent."]
      (is (md-v2/valid-plan-markdown? markdown))))

  (testing "missing plan name in H1 fails validation"
    (let [markdown "---
description: No name
---

Content."]
      (is (not (md-v2/valid-plan-markdown? markdown)))))

  (testing "missing front matter fails validation"
    (let [markdown "# Just Markdown\n\nNo front matter."]
      (is (not (md-v2/valid-plan-markdown? markdown))))))

(deftest test-file-io
  (testing "write and read round-trip with new format"
    (let [tmp-file (str "/tmp/test-plan-v2-" (System/currentTimeMillis) ".md")
          plan {:name "File Test Plan"
                :description "Testing file I/O"
                :content "Plan content here"
                :completed false}
          tasks [{:name "File Task" :description nil :completed false :content nil}]
          facts []]
      (try
        (md-v2/write-plan-to-file tmp-file plan tasks facts)
        (let [result (md-v2/read-plan-from-file tmp-file)]
          (is (= "File Test Plan" (get-in result [:plan :name])))
          (is (= "Testing file I/O" (get-in result [:plan :description])))
          (is (= "Plan content here" (get-in result [:plan :content])))
          (is (= 1 (count (:tasks result))))
          (is (= "File Task" (get-in result [:tasks 0 :name]))))
        (finally
          (when (.exists (io/file tmp-file))
            (.delete (io/file tmp-file))))))))

(deftest test-metadata-helpers
  (testing "get-plan-metadata extracts plan without tasks/facts"
    (let [markdown "---
description: Desc
tasks:
- name: Task 1
facts:
- name: Fact 1
---

# My Plan

Content here."
          result (md-v2/get-plan-metadata markdown)]
      (is (= "My Plan" (:name result)))
      (is (= "Desc" (:description result)))
      (is (= "Content here." (:content result)))
      (is (nil? (:tasks result)))
      (is (nil? (:facts result)))))

  (testing "count-tasks returns correct count"
    (let [markdown "---
tasks:
- name: Task 1
- name: Task 2
---

# Test

Content"]
      (is (= 2 (md-v2/count-tasks markdown)))))

  (testing "count-facts returns correct count"
    (let [markdown "---
facts:
- name: Fact 1
- name: Fact 2
- name: Fact 3
---

# Test

Content"]
      (is (= 3 (md-v2/count-facts markdown))))))

(deftest test-complex-nested-structures
  (testing "tasks with all fields are preserved"
    (let [plan {:name "Complex Plan" :description nil :content "" :completed false}
          tasks [{:id 1
                  :name "Complex Task"
                  :description "A description"
                  :content "Task content body"
                  :completed true
                  :parent_id nil
                  :created_at "2024-01-15T10:00:00"
                  :updated_at "2024-01-15T11:00:00"}]
          markdown (md-v2/plan->markdown plan tasks [])
          result (md-v2/markdown->plan markdown)]
      (is (= 1 (count (:tasks result))))
      (let [task (first (:tasks result))]
        (is (= "Complex Task" (:name task)))
        (is (= "A description" (:description task)))
        (is (= "Task content body" (:content task)))
        (is (= true (:completed task))))))

  (testing "facts with all fields are preserved"
    (let [plan {:name "Complex Plan" :description nil :content "" :completed false}
          facts [{:id 1
                  :name "Complex Fact"
                  :description "Fact description"
                  :content "Fact content body"
                  :created_at "2024-01-15T10:00:00"
                  :updated_at "2024-01-15T11:00:00"}]
          markdown (md-v2/plan->markdown plan [] facts)
          result (md-v2/markdown->plan markdown)]
      (is (= 1 (count (:facts result))))
      (let [fact (first (:facts result))]
        (is (= "Complex Fact" (:name fact)))
        (is (= "Fact description" (:description fact)))
        (is (= "Fact content body" (:content fact)))))))

(deftest test-empty-collections
  (testing "empty tasks and facts are handled correctly"
    (let [plan {:name "Empty Plan" :description nil :content "" :completed false}
          markdown (md-v2/plan->markdown plan [] [])
          result (md-v2/markdown->plan markdown)]
      (is (= [] (:tasks result)))
      (is (= [] (:facts result)))))

  (testing "nil tasks and facts are handled correctly"
    (let [plan {:name "Nil Plan" :description nil :content "" :completed false}
          markdown (md-v2/plan->markdown plan nil nil)
          result (md-v2/markdown->plan markdown)]
      (is (= [] (:tasks result)))
      (is (= [] (:facts result))))))
