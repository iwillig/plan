(ns plan.db-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.db :as db]
   [plan.main :as main]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

(deftest with-connection-test
  (testing "with-connection opens and closes connection properly"
    (let [db-path ":memory:"
          result (atom nil)]
      (db/with-connection db-path
        (fn [conn]
          (reset! result (db/execute! conn {:select [1]}))))
      ;; Connection should be closed after with-connection returns
      (is (some? @result)))))

(deftest execute!-test
  (testing "execute! with single argument"
    (main/create-schema! helper/*conn*)
    (let [result (db/execute! helper/*conn* {:select [:*] :from [:plans]})]
      (is (vector? result))
      (is (empty? result))))

  (testing "execute! with opts"
    (main/create-schema! helper/*conn*)
    (let [result (db/execute! helper/*conn*
                              {:select [:*] :from [:plans]}
                              {:return-keys true})]
      (is (vector? result)))))

(deftest execute-one!-test
  (testing "execute-one! with single argument"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans
                                :columns [:description :content]
                                :values [["Test" "Content"]]})
    (let [result (db/execute-one! helper/*conn* {:select [:*] :from [:plans] :where [:= :id 1]})]
      (is (some? result))
      (is (= "Test" (:description result)))))

  (testing "execute-one! with opts"
    (main/create-schema! helper/*conn*)
    (let [result (db/execute-one! helper/*conn*
                                  {:insert-into :plans
                                   :columns [:description]
                                   :values [["Test"]]
                                   :returning [:*]}
                                  {:return-keys true})]
      (is (some? result))
      (is (= "Test" (:description result))))))

(deftest format-fts-query-test
  (testing "single term gets prefix wildcard"
    (is (= "test*" (#'db/format-fts-query "test"))))

  (testing "multiple terms get prefix wildcards"
    (is (= "hello* world*" (#'db/format-fts-query "hello world"))))

  (testing "handles multiple spaces"
    (is (= "hello* world*" (#'db/format-fts-query "hello   world")))))

(deftest search-plans-test
  (testing "search finds matching plans"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans
                                :columns [:description :content]
                                :values [["Project planning" "Content about planning"]]})
    (let [results (db/search-plans helper/*conn* "plan")]
      (is (= 1 (count results)))
      (is (= "Project planning" (:description (first results))))))

  (testing "search returns empty for no matches"
    (main/create-schema! helper/*conn*)
    (let [results (db/search-plans helper/*conn* "nonexistent")]
      (is (empty? results)))))

(deftest search-tasks-test
  (testing "search finds matching tasks"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans :columns [:description] :values [["Plan"]]})
    (db/execute! helper/*conn* {:insert-into :tasks
                                :columns [:plan_id :description :content]
                                :values [[1 "Important task" "Task content"]]})
    (let [results (db/search-tasks helper/*conn* "task")]
      (is (= 1 (count results)))
      (is (= "Important task" (:description (first results)))))))

(deftest search-facts-test
  (testing "search finds matching facts"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans :columns [:description] :values [["Plan"]]})
    (db/execute! helper/*conn* {:insert-into :facts
                                :columns [:plan_id :description :content]
                                :values [[1 "Key fact" "Fact content"]]})
    (let [results (db/search-facts helper/*conn* "fact")]
      (is (= 1 (count results)))
      (is (= "Key fact" (:description (first results)))))))

(deftest highlight-plans-test
  (testing "highlight adds markup to matches"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans
                                :columns [:description :content]
                                :values [["Project planning" "Content about planning"]]})
    (let [results (db/highlight-plans helper/*conn* "plan")]
      (is (= 1 (count results)))
      (let [highlighted (:description_highlight (first results))]
        (is (str/includes? highlighted "<b>"))
        (is (str/includes? highlighted "</b>")))))

  (testing "highlight returns empty for no matches"
    (main/create-schema! helper/*conn*)
    (let [results (db/highlight-plans helper/*conn* "nonexistent")]
      (is (empty? results)))))

(deftest highlight-tasks-test
  (testing "highlight adds markup to task matches"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans :columns [:description] :values [["Plan"]]})
    (db/execute! helper/*conn* {:insert-into :tasks
                                :columns [:plan_id :description :content]
                                :values [[1 "Important task" "Task content"]]})
    (let [results (db/highlight-tasks helper/*conn* "task")]
      (is (= 1 (count results)))
      (let [highlighted (:description_highlight (first results))]
        (is (str/includes? highlighted "<b>"))
        (is (str/includes? highlighted "</b>"))))))

(deftest highlight-facts-test
  (testing "highlight adds markup to fact matches"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans :columns [:description] :values [["Plan"]]})
    (db/execute! helper/*conn* {:insert-into :facts
                                :columns [:plan_id :description :content]
                                :values [[1 "Key fact" "Fact content"]]})
    (let [results (db/highlight-facts helper/*conn* "fact")]
      (is (= 1 (count results)))
      (let [highlighted (:description_highlight (first results))]
        (is (str/includes? highlighted "<b>"))
        (is (str/includes? highlighted "</b>"))))))

(deftest fts-update-trigger-test
  (testing "FTS index is updated when plan is updated"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans
                                :columns [:description :content]
                                :values [["Oldunique description" "Content"]]})
    ;; Update the plan - change description to something completely different
    (db/execute! helper/*conn* {:update :plans
                                :set {:description "Newunique description"}
                                :where [:= :id 1]})
    ;; Search for new unique term should find it
    (let [results (db/search-plans helper/*conn* "newunique")]
      (is (= 1 (count results)))
      (is (= "Newunique description" (:description (first results)))))
    ;; Search for old unique term should not find it anymore
    (let [results (db/search-plans helper/*conn* "oldunique")]
      (is (empty? results)))))

(deftest fts-delete-trigger-test
  (testing "FTS index is updated when plan is deleted"
    (main/create-schema! helper/*conn*)
    (db/execute! helper/*conn* {:insert-into :plans
                                :columns [:description :content]
                                :values [["Delete me" "Content"]]})
    ;; Verify it exists in search
    (is (= 1 (count (db/search-plans helper/*conn* "delete"))))
    ;; Delete the plan
    (db/execute! helper/*conn* {:delete-from :plans :where [:= :id 1]})
    ;; Search should no longer find it
    (is (empty? (db/search-plans helper/*conn* "delete")))))
