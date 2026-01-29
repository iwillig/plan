(ns plan.main-export-import-test
  "Tests for plan export and import functionality"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.main :as main]
   [plan.models.fact :as fact]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.serializers.markdown :as md-serializer]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest test-plan-export-serialization
  (testing "export creates valid markdown file"
    (main/create-schema! helper/*conn*)
    (let [created-plan (plan/create helper/*conn* "Export Test" "Test description" "# Test Content")
          plan-id (:id created-plan)
          _ (task/create helper/*conn* plan-id "Task 1" "First task" nil nil)
          _ (fact/create helper/*conn* plan-id "Fact 1" "A fact" "Fact content")
          tmp-file (str "/tmp/test-export-" (System/currentTimeMillis) ".md")]
      (try
        ;; Export using the serializer directly
        (let [plan-data (plan/get-by-id helper/*conn* plan-id)
              tasks (task/get-by-plan helper/*conn* plan-id)
              facts (fact/get-by-plan helper/*conn* plan-id)]
          (md-serializer/write-plan-to-file tmp-file plan-data tasks facts))
        ;; Verify file was created
        (is (.exists (io/file tmp-file)))
        ;; Verify it's valid markdown
        (is (md-serializer/valid-plan-markdown? (slurp tmp-file)))
        ;; Verify content
        (let [data (md-serializer/read-plan-from-file tmp-file)]
          (is (= "Export Test" (get-in data [:plan :name])))
          (is (= 1 (count (:tasks data))))
          (is (= "Task 1" (:name (first (:tasks data)))))
          (is (= 1 (count (:facts data))))
          (is (= "Fact 1" (:name (first (:facts data))))))
        (finally
          (when (.exists (io/file tmp-file))
            (.delete (io/file tmp-file))))))))

(deftest test-plan-import-deserialization
  (testing "import creates plan with tasks and facts"
    (main/create-schema! helper/*conn*)
    (let [markdown "---
plan_name: Import Test
plan_description: Imported description
plan_completed: true
task_0_name: Imported Task
task_0_description: Task desc
task_0_completed: true
fact_0_name: Imported Fact
fact_0_description: Fact desc
fact_0_content: Fact content
---

# Imported Content

This was imported from markdown."
          tmp-file (str "/tmp/test-import-" (System/currentTimeMillis) ".md")]
      (try
        ;; Write the markdown file
        (spit tmp-file markdown)
        ;; Parse and import manually
        (let [data (md-serializer/read-plan-from-file tmp-file)
              plan-data (:plan data)]
          (is (md-serializer/valid-plan-markdown? (slurp tmp-file)))
          ;; Create the plan
          (let [created-plan (plan/create helper/*conn*
                                          (:name plan-data)
                                          (:description plan-data)
                                          (:content plan-data))]
            (is (= "Import Test" (:name created-plan)))
            (is (= "Imported description" (:description created-plan)))
            ;; Create tasks
            (doseq [task-data (:tasks data)]
              (task/create helper/*conn*
                           (:id created-plan)
                           (:name task-data)
                           (:description task-data)
                           (:content task-data)
                           (:parent_id task-data)))
            ;; Create facts
            (doseq [fact-data (:facts data)]
              (fact/create helper/*conn*
                           (:id created-plan)
                           (:name fact-data)
                           (:description fact-data)
                           (:content fact-data)))
            ;; Verify
            (let [tasks (task/get-by-plan helper/*conn* (:id created-plan))
                  facts (fact/get-by-plan helper/*conn* (:id created-plan))]
              (is (= 1 (count tasks)))
              (is (= "Imported Task" (:name (first tasks))))
              (is (= 1 (count facts)))
              (is (= "Imported Fact" (:name (first facts)))))))
        (finally
          (when (.exists (io/file tmp-file))
            (.delete (io/file tmp-file))))))))

(deftest test-export-import-round-trip
  (testing "export then import preserves data"
    (main/create-schema! helper/*conn*)
    (let [original-plan (plan/create helper/*conn* "Round Trip" "Test" "# Content")
          plan-id (:id original-plan)
          _ (task/create helper/*conn* plan-id "Task A" "Desc A" "Content A" nil)
          _ (fact/create helper/*conn* plan-id "Fact X" "Desc X" "Content X")
          tmp-file (str "/tmp/test-roundtrip-" (System/currentTimeMillis) ".md")]
      (try
        ;; Export
        (let [plan-data (plan/get-by-id helper/*conn* plan-id)
              tasks (task/get-by-plan helper/*conn* plan-id)
              facts (fact/get-by-plan helper/*conn* plan-id)]
          (md-serializer/write-plan-to-file tmp-file plan-data tasks facts))
        ;; Delete original
        (task/delete-by-plan helper/*conn* plan-id)
        (fact/delete-by-plan helper/*conn* plan-id)
        (plan/delete helper/*conn* plan-id)
        ;; Import
        (let [data (md-serializer/read-plan-from-file tmp-file)
              plan-data (:plan data)
              imported-plan (plan/create helper/*conn*
                                         (:name plan-data)
                                         (:description plan-data)
                                         (:content plan-data))]
          (doseq [task-data (:tasks data)]
            (task/create helper/*conn*
                         (:id imported-plan)
                         (:name task-data)
                         (:description task-data)
                         (:content task-data)
                         (:parent_id task-data)))
          (doseq [fact-data (:facts data)]
            (fact/create helper/*conn*
                         (:id imported-plan)
                         (:name fact-data)
                         (:description fact-data)
                         (:content fact-data)))
          ;; Verify
          (let [plans (plan/get-all helper/*conn*)
                tasks (task/get-by-plan helper/*conn* (:id imported-plan))
                facts (fact/get-by-plan helper/*conn* (:id imported-plan))]
            (is (= 1 (count plans)))
            (is (= "Round Trip" (:name (first plans))))
            (is (= 1 (count tasks)))
            (is (= "Task A" (:name (first tasks))))
            (is (= 1 (count facts)))
            (is (= "Fact X" (:name (first facts))))))
        (finally
          (when (.exists (io/file tmp-file))
            (.delete (io/file tmp-file))))))))

(deftest test-invalid-markdown-validation
  (testing "invalid markdown is detected"
    (let [invalid-markdown "# Just markdown\n\nNo front matter here."]
      (is (not (md-serializer/valid-plan-markdown? invalid-markdown)))))
  (testing "markdown without plan name is invalid"
    (let [no-name-markdown "---
plan_description: Just a description
---

Content."]
      (is (not (md-serializer/valid-plan-markdown? no-name-markdown))))))

(deftest test-multiline-content-preservation
  (testing "multiline content is preserved through round-trip"
    (main/create-schema! helper/*conn*)
    (let [multiline-content "# Heading\n\nParagraph 1\n\n- Item 1\n- Item 2\n\n```clojure\n(defn hello []\n  (println \"Hello\"))\n```"
          plan-data (plan/create helper/*conn* "Multiline Test" "Desc" multiline-content)
          plan-id (:id plan-data)
          tmp-file (str "/tmp/test-multiline-" (System/currentTimeMillis) ".md")]
      (try
        ;; Export
        (md-serializer/write-plan-to-file tmp-file
                                          (plan/get-by-id helper/*conn* plan-id)
                                          []
                                          [])
        ;; Import
        (let [data (md-serializer/read-plan-from-file tmp-file)]
          (is (str/includes? (get-in data [:plan :content]) "# Heading"))
          (is (str/includes? (get-in data [:plan :content]) "Paragraph 1"))
          (is (str/includes? (get-in data [:plan :content]) "- Item 1"))
          (is (str/includes? (get-in data [:plan :content]) "```clojure")))
        (finally
          (when (.exists (io/file tmp-file))
            (.delete (io/file tmp-file))))))))
