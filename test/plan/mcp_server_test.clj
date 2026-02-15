(ns plan.mcp-server-test
  "Tests for the MCP server implementation"
  (:require
   [clojure.data.json :as json]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.config :as config]
   [plan.db :as db]
   [plan.main :as main]
   [plan.mcp-server :as mcp]
   [plan.models.fact :as fact]
   [plan.models.lesson :as lesson]
   [plan.models.plan :as plan]
   [plan.models.task :as task]
   [plan.models.trace :as trace]
   [plan.test-helper :as helper]))

(use-fixtures :each helper/db-fixture)

;; -----------------------------------------------------------------------------
;; Helper Functions
;; -----------------------------------------------------------------------------

(defn- parse-json
  "Parse JSON string to map with keyword keys"
  [s]
  (json/read-str s :key-fn keyword))

(defn- execute-op
  "Execute an MCP operation directly against the connection.
   Returns parsed result map."
  [conn operation params]
  (#'mcp/execute-operation conn operation params))

;; -----------------------------------------------------------------------------
;; handle-fact-list Tests
;; -----------------------------------------------------------------------------

(deftest handle-fact-list-test
  (main/create-schema! helper/*conn*)

  (testing "returns facts for a plan"
    (let [p (plan/create helper/*conn* "list-plan-with-facts" "Test" nil)
          _ (fact/create helper/*conn* (:id p) "fact-1" "Desc 1" "Content 1")
          _ (fact/create helper/*conn* (:id p) "fact-2" "Desc 2" "Content 2")
          result (execute-op helper/*conn* "fact-list" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= :fact-list (:operation result)))
      (is (= 2 (:count result)))
      (is (= 2 (count (:data result))))))

  (testing "returns empty list for plan with no facts"
    (let [p (plan/create helper/*conn* "list-plan-empty" "Test" nil)
          result (execute-op helper/*conn* "fact-list" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= 0 (:count result)))
      (is (empty? (:data result)))))

  (testing "fails when plan_id missing"
    (let [result (execute-op helper/*conn* "fact-list" {})]
      (is (= :error (:status result)))
      (is (re-find #"plan_id" (:message result))))))

;; -----------------------------------------------------------------------------
;; handle-fact-get Tests
;; -----------------------------------------------------------------------------

(deftest handle-fact-get-test
  (main/create-schema! helper/*conn*)

  (testing "returns fact when found"
    (let [p (plan/create helper/*conn* "get-plan" "Test" nil)
          f (fact/create helper/*conn* (:id p) "my-fact" "Description" "Content")
          result (execute-op helper/*conn* "fact-get" {:fact_id (:id f)})]
      (is (= :success (:status result)))
      (is (= :fact-get (:operation result)))
      (is (= "my-fact" (get-in result [:data :name])))
      (is (= "Content" (get-in result [:data :content])))))

  (testing "returns error when fact not found"
    (let [result (execute-op helper/*conn* "fact-get" {:fact_id 999})]
      (is (= :error (:status result)))
      (is (= :fact-get (:operation result)))
      (is (re-find #"not found" (:message result)))))

  (testing "fails when fact_id missing"
    (let [result (execute-op helper/*conn* "fact-get" {})]
      (is (= :error (:status result)))
      (is (re-find #"fact_id" (:message result))))))

;; -----------------------------------------------------------------------------
;; handle-fact-create Tests
;; -----------------------------------------------------------------------------

(deftest handle-fact-create-test
  (main/create-schema! helper/*conn*)

  (testing "creates fact with all fields"
    (let [p (plan/create helper/*conn* "create-plan-1" "Test" nil)
          result (execute-op helper/*conn* "fact-create"
                             {:plan_id (:id p)
                              :name "new-fact"
                              :description "A description"
                              :content "The content"})]
      (is (= :success (:status result)))
      (is (= :fact-create (:operation result)))
      (is (number? (get-in result [:data :id])))
      (is (= "new-fact" (get-in result [:data :name])))
      (is (= "A description" (get-in result [:data :description])))
      (is (= "The content" (get-in result [:data :content])))))

  (testing "creates fact with optional description nil"
    (let [p (plan/create helper/*conn* "create-plan-2" "Test" nil)
          result (execute-op helper/*conn* "fact-create"
                             {:plan_id (:id p)
                              :name "minimal-fact"
                              :content "Just content"})]
      (is (= :success (:status result)))
      (is (= "minimal-fact" (get-in result [:data :name])))))

  (testing "fails when required params missing"
    (let [p (plan/create helper/*conn* "create-plan-3" "Test" nil)]
      ;; Missing name
      (let [result (execute-op helper/*conn* "fact-create"
                               {:plan_id (:id p) :content "Content"})]
        (is (= :error (:status result)))
        (is (re-find #"(?i)name" (:message result))))
      ;; Missing content
      (let [result (execute-op helper/*conn* "fact-create"
                               {:plan_id (:id p) :name "test"})]
        (is (= :error (:status result)))
        (is (re-find #"(?i)content" (:message result))))
      ;; Missing plan_id
      (let [result (execute-op helper/*conn* "fact-create"
                               {:name "test" :content "Content"})]
        (is (= :error (:status result)))
        (is (re-find #"(?i)plan" (:message result)))))))

;; -----------------------------------------------------------------------------
;; handle-fact-update Tests
;; -----------------------------------------------------------------------------

(deftest handle-fact-update-test
  (main/create-schema! helper/*conn*)

  (testing "updates single field"
    (let [p (plan/create helper/*conn* "update-plan-1" "Test" nil)
          f (fact/create helper/*conn* (:id p) "original" "Original desc" "Original content")
          result (execute-op helper/*conn* "fact-update"
                             {:fact_id (:id f)
                              :content "Updated content"})]
      (is (= :success (:status result)))
      (is (= :fact-update (:operation result)))
      (is (= "Updated content" (get-in result [:data :content])))
      ;; Other fields preserved
      (is (= "original" (get-in result [:data :name])))))

  (testing "updates multiple fields"
    (let [p (plan/create helper/*conn* "update-plan-2" "Test" nil)
          f (fact/create helper/*conn* (:id p) "original" "Original desc" "Original content")
          result (execute-op helper/*conn* "fact-update"
                             {:fact_id (:id f)
                              :name "updated-name"
                              :description "New desc"
                              :content "New content"})]
      (is (= :success (:status result)))
      (is (= "updated-name" (get-in result [:data :name])))
      (is (= "New desc" (get-in result [:data :description])))
      (is (= "New content" (get-in result [:data :content])))))

  (testing "returns error when fact not found"
    (let [result (execute-op helper/*conn* "fact-update"
                             {:fact_id 999 :content "New"})]
      (is (= :error (:status result)))
      (is (re-find #"not found" (:message result)))))

  (testing "fails when fact_id missing"
    (let [result (execute-op helper/*conn* "fact-update" {:content "New"})]
      (is (= :error (:status result)))
      (is (re-find #"fact_id" (:message result)))))

  (testing "fails when no update fields provided"
    (let [p (plan/create helper/*conn* "update-plan-3" "Test" nil)
          f (fact/create helper/*conn* (:id p) "test" "Test" "Content")
          result (execute-op helper/*conn* "fact-update" {:fact_id (:id f)})]
      (is (= :error (:status result)))
      (is (re-find #"No fields to update" (:message result))))))

;; -----------------------------------------------------------------------------
;; handle-fact-delete Tests
;; -----------------------------------------------------------------------------

(deftest handle-fact-delete-test
  (main/create-schema! helper/*conn*)

  (testing "deletes existing fact"
    (let [p (plan/create helper/*conn* "delete-plan" "Test" nil)
          f (fact/create helper/*conn* (:id p) "to-delete" "Desc" "Content")
          result (execute-op helper/*conn* "fact-delete" {:fact_id (:id f)})]
      (is (= :success (:status result)))
      (is (= :fact-delete (:operation result)))
      (is (true? (get-in result [:data :deleted])))
      (is (= (:id f) (get-in result [:data :fact_id])))
      ;; Verify actually deleted
      (is (nil? (fact/get-by-id helper/*conn* (:id f))))))

  (testing "fails when fact_id missing"
    (let [result (execute-op helper/*conn* "fact-delete" {})]
      (is (= :error (:status result)))
      (is (re-find #"fact_id" (:message result))))))

;; -----------------------------------------------------------------------------
;; execute-operation Router Tests
;; -----------------------------------------------------------------------------

(deftest execute-operation-routing-test
  (main/create-schema! helper/*conn*)

  (testing "routes to correct handler for each operation"
    (let [p (plan/create helper/*conn* "routing-plan" "Test" nil)]
      ;; Each operation type returns the correct :operation key
      (is (= :fact-list (:operation (execute-op helper/*conn* "fact-list" {:plan_id (:id p)}))))
      (is (= :fact-get (:operation (execute-op helper/*conn* "fact-get" {:fact_id 999}))))
      (is (= :fact-create (:operation (execute-op helper/*conn* "fact-create"
                                                  {:plan_id (:id p)
                                                   :name "test"
                                                   :content "content"}))))
      (let [f (fact/create helper/*conn* (:id p) "for-update" "Desc" "Content")]
        (is (= :fact-update (:operation (execute-op helper/*conn* "fact-update"
                                                    {:fact_id (:id f) :content "new"}))))
        (is (= :fact-delete (:operation (execute-op helper/*conn* "fact-delete"
                                                    {:fact_id (:id f)})))))))

  (testing "returns error for unknown operation"
    (let [result (execute-op helper/*conn* "unknown-operation" {})]
      (is (= :error (:status result)))
      (is (re-find #"Unknown operation" (:message result))))))

;; -----------------------------------------------------------------------------
;; get-capabilities-tool Tests
;; -----------------------------------------------------------------------------

(deftest get-capabilities-tool-test
  (testing "returns valid JSON"
    (let [result (mcp/get-capabilities-tool {} nil)
          parsed (parse-json result)]
      (is (map? parsed))
      (is (string? (:version parsed)))
      (is (string? (:description parsed)))))

  (testing "contains all operations"
    (let [result (mcp/get-capabilities-tool {} nil)
          parsed (parse-json result)
          ops (keys (:operations parsed))]
      (is (contains? (set ops) :fact-list))
      (is (contains? (set ops) :fact-get))
      (is (contains? (set ops) :fact-create))
      (is (contains? (set ops) :fact-update))
      (is (contains? (set ops) :fact-delete))))

  (testing "operations have required fields"
    (let [result (mcp/get-capabilities-tool {} nil)
          parsed (parse-json result)
          fact-list (get-in parsed [:operations :fact-list])]
      (is (string? (:description fact-list)))
      (is (map? (:parameters fact-list)))
      (is (map? (:returns fact-list)))))

  (testing "contains examples"
    (let [result (mcp/get-capabilities-tool {} nil)
          parsed (parse-json result)]
      (is (vector? (:examples parsed)))
      (is (pos? (count (:examples parsed)))))))

;; -----------------------------------------------------------------------------
;; execute-tool Integration Tests
;; -----------------------------------------------------------------------------

(deftest execute-tool-json-parsing-test
  (main/create-schema! helper/*conn*)

  (testing "parses valid JSON request"
    (let [p (plan/create helper/*conn* "json-test-plan" "Test" nil)
          callback-result (atom nil)
          callback (fn [results _error?]
                     (reset! callback-result (first results)))]
      ;; Need to use with-redefs to inject our test connection
      (with-redefs [config/db-path (constantly ":memory:")
                    db/with-connection (fn [_path f] (f helper/*conn*))]
        (mcp/execute-tool
         {"request" (json/write-str {:operation "fact-list" :plan_id (:id p)})}
         callback))
      (let [result (parse-json @callback-result)]
        (is (= "success" (name (:status result))))
        (is (= "fact-list" (name (:operation result)))))))

  (testing "returns error for invalid JSON"
    (let [callback-result (atom nil)
          error-flag (atom nil)
          callback (fn [results error?]
                     (reset! callback-result (first results))
                     (reset! error-flag error?))]
      (mcp/execute-tool {"request" "not valid json {"} callback)
      (is (true? @error-flag))
      (let [result (parse-json @callback-result)]
        (is (= "error" (name (:status result))))
        (is (re-find #"JSON parse error" (:message result)))))))

;; -----------------------------------------------------------------------------
;; make-tools Tests
;; -----------------------------------------------------------------------------

(deftest make-tools-test
  (testing "returns two tools"
    (let [tools (mcp/make-tools nil nil)]
      (is (= 2 (count tools)))))

  (testing "get_capabilities tool has correct structure"
    (let [tools (mcp/make-tools nil nil)
          cap-tool (first (filter #(= "get_capabilities" (:name %)) tools))]
      (is (some? cap-tool))
      (is (string? (:description cap-tool)))
      (is (map? (:schema cap-tool)))
      (is (fn? (:tool-fn cap-tool)))))

  (testing "execute tool has correct structure"
    (let [tools (mcp/make-tools nil nil)
          exec-tool (first (filter #(= "execute" (:name %)) tools))]
      (is (some? exec-tool))
      (is (string? (:description exec-tool)))
      (is (map? (:schema exec-tool)))
      (is (= ["request"] (get-in exec-tool [:schema :required])))
      (is (fn? (:tool-fn exec-tool))))))

;; -----------------------------------------------------------------------------
;; Plan Operation Tests
;; -----------------------------------------------------------------------------

(deftest handle-plan-list-test
  (main/create-schema! helper/*conn*)

  (testing "returns empty list when no plans"
    (let [result (execute-op helper/*conn* "plan-list" {})]
      (is (= :success (:status result)))
      (is (= 0 (:count result)))
      (is (empty? (:data result)))))

  (testing "returns all plans ordered by created_at desc"
    (let [_ (plan/create helper/*conn* "plan-1" "First plan" nil)
          _ (plan/create helper/*conn* "plan-2" "Second plan" nil)
          result (execute-op helper/*conn* "plan-list" {})]
      (is (= :success (:status result)))
      (is (= 2 (:count result)))
      ;; Plans ordered by created_at desc (newest first)
      (is (= "plan-2" (get-in result [:data 0 :name])))
      (is (= "plan-1" (get-in result [:data 1 :name]))))))

(deftest handle-plan-get-test
  (main/create-schema! helper/*conn*)

  (testing "returns plan when found"
    (let [p (plan/create helper/*conn* "get-plan-test" "Test description" "Test content")
          result (execute-op helper/*conn* "plan-get" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= "get-plan-test" (get-in result [:data :name])))
      (is (= "Test description" (get-in result [:data :description])))))

  (testing "returns error when plan not found"
    (let [result (execute-op helper/*conn* "plan-get" {:plan_id 999})]
      (is (= :error (:status result)))
      (is (re-find #"not found" (:message result)))))

  (testing "fails when plan_id missing"
    (let [result (execute-op helper/*conn* "plan-get" {})]
      (is (= :error (:status result)))
      (is (re-find #"plan_id" (:message result))))))

(deftest handle-plan-show-test
  (main/create-schema! helper/*conn*)

  (testing "returns plan with tasks and facts"
    (let [p (plan/create helper/*conn* "show-plan" "Test" nil)
          _ (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          _ (fact/create helper/*conn* (:id p) "fact-1" "Desc" "Content")
          result (execute-op helper/*conn* "plan-show" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= "show-plan" (get-in result [:data :plan :name])))
      (is (= 1 (count (get-in result [:data :tasks]))))
      (is (= 1 (count (get-in result [:data :facts]))))))

  (testing "returns error when plan not found"
    (let [result (execute-op helper/*conn* "plan-show" {:plan_id 999})]
      (is (= :error (:status result)))
      (is (re-find #"not found" (:message result))))))

(deftest handle-plan-create-test
  (main/create-schema! helper/*conn*)

  (testing "creates plan with all fields"
    (let [result (execute-op helper/*conn* "plan-create"
                             {:name "new-plan"
                              :description "A description"
                              :content "The content"})]
      (is (= :success (:status result)))
      (is (number? (get-in result [:data :id])))
      (is (= "new-plan" (get-in result [:data :name])))
      (is (= "A description" (get-in result [:data :description])))))

  (testing "creates plan with only name"
    (let [result (execute-op helper/*conn* "plan-create" {:name "minimal-plan"})]
      (is (= :success (:status result)))
      (is (= "minimal-plan" (get-in result [:data :name])))))

  (testing "fails when name missing"
    (let [result (execute-op helper/*conn* "plan-create" {:description "Missing name"})]
      (is (= :error (:status result)))
      (is (re-find #"name" (:message result))))))

(deftest handle-plan-update-test
  (main/create-schema! helper/*conn*)

  (testing "updates single field"
    (let [p (plan/create helper/*conn* "update-test" "Original" nil)
          result (execute-op helper/*conn* "plan-update"
                             {:plan_id (:id p) :description "Updated"})]
      (is (= :success (:status result)))
      (is (= "Updated" (get-in result [:data :description])))
      (is (= "update-test" (get-in result [:data :name])))))

  (testing "updates completed status"
    (let [p (plan/create helper/*conn* "complete-test" nil nil)
          result (execute-op helper/*conn* "plan-update"
                             {:plan_id (:id p) :completed true})]
      (is (= :success (:status result)))
      (is (true? (get-in result [:data :completed])))))

  (testing "fails when plan not found"
    (let [result (execute-op helper/*conn* "plan-update"
                             {:plan_id 999 :name "New"})]
      (is (= :error (:status result)))
      (is (re-find #"not found" (:message result)))))

  (testing "fails when plan_id missing"
    (let [result (execute-op helper/*conn* "plan-update" {:name "New"})]
      (is (= :error (:status result)))
      (is (re-find #"plan_id" (:message result)))))

  (testing "fails when no update fields"
    (let [p (plan/create helper/*conn* "no-update" nil nil)
          result (execute-op helper/*conn* "plan-update" {:plan_id (:id p)})]
      (is (= :error (:status result)))
      (is (re-find #"No fields" (:message result))))))

(deftest handle-plan-delete-test
  (main/create-schema! helper/*conn*)

  (testing "deletes plan with tasks and facts"
    (let [p (plan/create helper/*conn* "delete-me" nil nil)
          _ (task/create helper/*conn* (:id p) "task" nil nil nil)
          _ (fact/create helper/*conn* (:id p) "fact" nil "Content")
          result (execute-op helper/*conn* "plan-delete" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (true? (get-in result [:data :deleted])))
      (is (= 1 (get-in result [:data :tasks-deleted])))
      (is (= 1 (get-in result [:data :facts-deleted])))
      (is (nil? (plan/get-by-id helper/*conn* (:id p)))))))

(deftest handle-plan-search-test
  (main/create-schema! helper/*conn*)

  (testing "finds matching plans"
    (plan/create helper/*conn* "roadmap 2024" nil nil)
    (plan/create helper/*conn* "other plan" nil nil)
    (let [result (execute-op helper/*conn* "plan-search" {:query "roadmap"})]
      (is (= :success (:status result)))
      (is (= 1 (:count result)))
      (is (= "roadmap 2024" (get-in result [:data 0 :name])))))

  (testing "returns empty for no matches"
    (let [result (execute-op helper/*conn* "plan-search" {:query "xyz123"})]
      (is (= :success (:status result)))
      (is (= 0 (:count result)))))

  (testing "fails when query missing"
    (let [result (execute-op helper/*conn* "plan-search" {})]
      (is (= :error (:status result)))
      (is (re-find #"query" (:message result))))))

;; -----------------------------------------------------------------------------
;; Task Operation Tests
;; -----------------------------------------------------------------------------

(deftest handle-task-list-test
  (main/create-schema! helper/*conn*)

  (testing "returns tasks for a plan"
    (let [p (plan/create helper/*conn* "task-list-plan" nil nil)
          _ (task/create helper/*conn* (:id p) "task-1" nil nil nil)
          _ (task/create helper/*conn* (:id p) "task-2" nil nil nil)
          result (execute-op helper/*conn* "task-list" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= 2 (:count result)))))

  (testing "returns error when plan not found"
    (let [result (execute-op helper/*conn* "task-list" {:plan_id 999})]
      (is (= :error (:status result)))
      (is (re-find #"not found" (:message result))))))

(deftest handle-task-get-test
  (main/create-schema! helper/*conn*)

  (testing "returns task when found"
    (let [p (plan/create helper/*conn* "task-get-plan" nil nil)
          t (task/create helper/*conn* (:id p) "my-task" "Desc" nil nil)
          result (execute-op helper/*conn* "task-get" {:task_id (:id t)})]
      (is (= :success (:status result)))
      (is (= "my-task" (get-in result [:data :name])))))

  (testing "returns error when task not found"
    (let [result (execute-op helper/*conn* "task-get" {:task_id 999})]
      (is (= :error (:status result)))
      (is (re-find #"not found" (:message result))))))

(deftest handle-task-show-test
  (main/create-schema! helper/*conn*)

  (testing "returns task with dependencies"
    (let [p (plan/create helper/*conn* "task-show-plan" nil nil)
          t1 (task/create helper/*conn* (:id p) "blocking" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "blocked" nil nil nil)
          _ (task/add-dependency helper/*conn* (:id t1) (:id t2))
          result (execute-op helper/*conn* "task-show" {:task_id (:id t2)})]
      (is (= :success (:status result)))
      (is (= "blocked" (get-in result [:data :task :name])))
      (is (= 1 (count (get-in result [:data :blocked-by]))))
      (is (= "blocking" (get-in result [:data :blocked-by 0 :name])))))

  (testing "returns error when task not found"
    (let [result (execute-op helper/*conn* "task-show" {:task_id 999})]
      (is (= :error (:status result))))))

(deftest handle-task-create-test
  (main/create-schema! helper/*conn*)

  (testing "creates task with all fields"
    (let [p (plan/create helper/*conn* "task-create-plan" nil nil)
          result (execute-op helper/*conn* "task-create"
                             {:plan_id (:id p)
                              :name "new-task"
                              :description "A task"
                              :content "Details"})]
      (is (= :success (:status result)))
      (is (number? (get-in result [:data :id])))
      (is (= "new-task" (get-in result [:data :name])))))

  (testing "creates task with parent"
    (let [p (plan/create helper/*conn* "parent-plan" nil nil)
          parent (task/create helper/*conn* (:id p) "parent" nil nil nil)
          result (execute-op helper/*conn* "task-create"
                             {:plan_id (:id p)
                              :name "child"
                              :parent_id (:id parent)})]
      (is (= :success (:status result)))
      (is (= (:id parent) (get-in result [:data :parent_id])))))

  (testing "fails when plan not found"
    (let [result (execute-op helper/*conn* "task-create"
                             {:plan_id 999 :name "orphan"})]
      (is (= :error (:status result))))))

(deftest handle-task-update-test
  (main/create-schema! helper/*conn*)

  (testing "updates task fields"
    (let [p (plan/create helper/*conn* "task-update-plan" nil nil)
          t (task/create helper/*conn* (:id p) "original" nil nil nil)
          result (execute-op helper/*conn* "task-update"
                             {:task_id (:id t) :name "updated"})]
      (is (= :success (:status result)))
      (is (= "updated" (get-in result [:data :name])))))

  (testing "fails when task not found"
    (let [result (execute-op helper/*conn* "task-update"
                             {:task_id 999 :name "new"})]
      (is (= :error (:status result))))))

(deftest handle-task-delete-test
  (main/create-schema! helper/*conn*)

  (testing "deletes task"
    (let [p (plan/create helper/*conn* "task-delete-plan" nil nil)
          t (task/create helper/*conn* (:id p) "to-delete" nil nil nil)
          result (execute-op helper/*conn* "task-delete" {:task_id (:id t)})]
      (is (= :success (:status result)))
      (is (true? (get-in result [:data :deleted])))
      (is (nil? (task/get-by-id helper/*conn* (:id t)))))))

(deftest handle-task-start-test
  (main/create-schema! helper/*conn*)

  (testing "starts pending task"
    (let [p (plan/create helper/*conn* "task-start-plan" nil nil)
          t (task/create helper/*conn* (:id p) "to-start" nil nil nil)
          result (execute-op helper/*conn* "task-start" {:task_id (:id t)})]
      (is (= :success (:status result)))
      (is (= "in_progress" (get-in result [:data :status])))))

  (testing "fails when task not found"
    (let [result (execute-op helper/*conn* "task-start" {:task_id 999})]
      (is (= :error (:status result))))))

(deftest handle-task-complete-test
  (main/create-schema! helper/*conn*)

  (testing "completes task"
    (let [p (plan/create helper/*conn* "task-complete-plan" nil nil)
          t (task/create helper/*conn* (:id p) "to-complete" nil nil nil)
          _ (task/start-task helper/*conn* (:id t))
          result (execute-op helper/*conn* "task-complete" {:task_id (:id t)})]
      (is (= :success (:status result)))
      (is (= "completed" (get-in result [:data :status]))))))

(deftest handle-task-fail-test
  (main/create-schema! helper/*conn*)

  (testing "fails task"
    (let [p (plan/create helper/*conn* "task-fail-plan" nil nil)
          t (task/create helper/*conn* (:id p) "to-fail" nil nil nil)
          _ (task/start-task helper/*conn* (:id t))
          result (execute-op helper/*conn* "task-fail" {:task_id (:id t)})]
      (is (= :success (:status result)))
      (is (= "failed" (get-in result [:data :status]))))))

(deftest handle-task-add-dependency-test
  (main/create-schema! helper/*conn*)

  (testing "adds dependency successfully"
    (let [p (plan/create helper/*conn* "dep-plan" nil nil)
          t1 (task/create helper/*conn* (:id p) "blocking" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "blocked" nil nil nil)
          result (execute-op helper/*conn* "task-add-dependency"
                             {:blocking_task_id (:id t1)
                              :blocked_task_id (:id t2)})]
      (is (= :success (:status result)))
      (is (true? (get-in result [:data :success])))))

  (testing "fails when would create cycle"
    (let [p (plan/create helper/*conn* "cycle-plan" nil nil)
          t1 (task/create helper/*conn* (:id p) "task-a" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "task-b" nil nil nil)
          _ (task/add-dependency helper/*conn* (:id t1) (:id t2))
          result (execute-op helper/*conn* "task-add-dependency"
                             {:blocking_task_id (:id t2)
                              :blocked_task_id (:id t1)})]
      (is (= :error (:status result)))
      (is (re-find #"cycle" (:message result))))))

(deftest handle-task-ready-test
  (main/create-schema! helper/*conn*)

  (testing "returns ready tasks"
    (let [p (plan/create helper/*conn* "ready-plan" nil nil)
          _ (task/create helper/*conn* (:id p) "ready-1" nil nil nil)
          _ (task/create helper/*conn* (:id p) "ready-2" nil nil nil)
          result (execute-op helper/*conn* "task-ready" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= 2 (:count result)))))

  (testing "excludes blocked tasks"
    (let [p (plan/create helper/*conn* "blocked-plan" nil nil)
          t1 (task/create helper/*conn* (:id p) "blocker" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "blocked" nil nil nil)
          _ (task/add-dependency helper/*conn* (:id t1) (:id t2))
          result (execute-op helper/*conn* "task-ready" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= 1 (:count result)))
      (is (= "blocker" (get-in result [:data 0 :name])))))

  (testing "fails when plan not found"
    (let [result (execute-op helper/*conn* "task-ready" {:plan_id 999})]
      (is (= :error (:status result))))))

(deftest handle-task-next-test
  (main/create-schema! helper/*conn*)

  (testing "returns next ready task"
    (let [p (plan/create helper/*conn* "next-plan" nil nil)
          _ (task/create helper/*conn* (:id p) "first" nil nil nil)
          result (execute-op helper/*conn* "task-next" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= "first" (get-in result [:data :name])))))

  (testing "returns empty when no ready tasks"
    (let [p (plan/create helper/*conn* "empty-plan" nil nil)
          result (execute-op helper/*conn* "task-next" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (empty? (get-in result [:data]))))))

(deftest handle-task-search-test
  (main/create-schema! helper/*conn*)

  (testing "finds matching tasks"
    (let [p (plan/create helper/*conn* "search-plan" nil nil)
          _ (task/create helper/*conn* (:id p) "design api" nil nil nil)
          _ (task/create helper/*conn* (:id p) "other task" nil nil nil)
          result (execute-op helper/*conn* "task-search" {:query "design"})]
      (is (= :success (:status result)))
      (is (= 1 (:count result))))))

;; -----------------------------------------------------------------------------
;; Fact Search Tests
;; -----------------------------------------------------------------------------

(deftest handle-fact-search-test
  (main/create-schema! helper/*conn*)

  (testing "finds matching facts"
    (let [p (plan/create helper/*conn* "fact-search-plan" nil nil)
          _ (fact/create helper/*conn* (:id p) "database" "Postgres config" "postgresql://localhost")
          _ (fact/create helper/*conn* (:id p) "other" "Other fact" "Some content")
          result (execute-op helper/*conn* "fact-search" {:query "postgres"})]
      (is (= :success (:status result)))
      (is (= 1 (:count result)))))

  (testing "fails when query missing"
    (let [result (execute-op helper/*conn* "fact-search" {})]
      (is (= :error (:status result)))
      (is (re-find #"query" (:message result))))))

;; -----------------------------------------------------------------------------
;; Lesson Operation Tests
;; -----------------------------------------------------------------------------

(deftest handle-lesson-create-test
  (main/create-schema! helper/*conn*)

  (testing "creates lesson with required fields"
    (let [result (execute-op helper/*conn* "lesson-create"
                             {:lesson_type "success_pattern"
                              :lesson_content "Always validate inputs"})]
      (is (= :success (:status result)))
      (is (number? (get-in result [:data :id])))
      (is (= "success_pattern" (get-in result [:data :lesson_type])))))

  (testing "creates lesson with plan"
    (let [p (plan/create helper/*conn* "lesson-plan" nil nil)
          result (execute-op helper/*conn* "lesson-create"
                             {:lesson_type "technique"
                              :lesson_content "Use threads for IO"
                              :plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= (:id p) (get-in result [:data :plan_id])))))

  (testing "creates lesson with all fields"
    (let [p (plan/create helper/*conn* "full-lesson-plan" nil nil)
          t (task/create helper/*conn* (:id p) "task" nil nil nil)
          result (execute-op helper/*conn* "lesson-create"
                             {:lesson_type "constraint"
                              :lesson_content "Max 100 concurrent connections"
                              :plan_id (:id p)
                              :task_id (:id t)
                              :trigger_condition "high load"
                              :confidence 0.9})]
      (is (= :success (:status result)))
      (is (= 0.9 (get-in result [:data :confidence])))))

  (testing "fails with invalid lesson type"
    (let [result (execute-op helper/*conn* "lesson-create"
                             {:lesson_type "invalid_type"
                              :lesson_content "Content"})]
      (is (= :error (:status result)))
      (is (re-find #"Invalid lesson type" (:message result))))))

(deftest handle-lesson-get-test
  (main/create-schema! helper/*conn*)

  (testing "returns lesson when found"
    (let [l (lesson/create helper/*conn* {:lesson-type "technique" :lesson-content "Test"})
          result (execute-op helper/*conn* "lesson-get" {:lesson_id (:id l)})]
      (is (= :success (:status result)))
      (is (= "technique" (get-in result [:data :lesson_type])))))

  (testing "returns error when not found"
    (let [result (execute-op helper/*conn* "lesson-get" {:lesson_id 999})]
      (is (= :error (:status result))))))

(deftest handle-lesson-list-all-test
  (main/create-schema! helper/*conn*)

  (testing "returns all lessons"
    (lesson/create helper/*conn* {:lesson-type "success_pattern" :lesson-content "One"})
    (lesson/create helper/*conn* {:lesson-type "failure_pattern" :lesson-content "Two"})
    (let [result (execute-op helper/*conn* "lesson-list-all" {})]
      (is (= :success (:status result)))
      (is (= 2 (:count result)))))

  (testing "filters by lesson type"
    (let [result (execute-op helper/*conn* "lesson-list-all"
                             {:lesson_type "success_pattern"})]
      (is (= :success (:status result)))
      (is (= 1 (:count result))))))

(deftest handle-lesson-list-plan-test
  (main/create-schema! helper/*conn*)

  (testing "returns lessons for plan"
    (let [p (plan/create helper/*conn* "plan-lessons" nil nil)
          _ (lesson/create helper/*conn* {:plan-id (:id p) :lesson-type "technique" :lesson-content "Tip"})
          result (execute-op helper/*conn* "lesson-list-plan" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= 1 (:count result)))))

  (testing "fails when plan not found"
    (let [result (execute-op helper/*conn* "lesson-list-plan" {:plan_id 999})]
      (is (= :error (:status result))))))

(deftest handle-lesson-list-task-test
  (main/create-schema! helper/*conn*)

  (testing "returns lessons for task"
    (let [p (plan/create helper/*conn* "task-lesson-plan" nil nil)
          t (task/create helper/*conn* (:id p) "task" nil nil nil)
          _ (lesson/create helper/*conn* {:task-id (:id t) :lesson-type "constraint" :lesson-content "Limit"})
          result (execute-op helper/*conn* "lesson-list-task" {:task_id (:id t)})]
      (is (= :success (:status result)))
      (is (= 1 (:count result))))))

(deftest handle-lesson-search-test
  (main/create-schema! helper/*conn*)

  (testing "finds matching lessons"
    (lesson/create helper/*conn* {:lesson-type "technique" :lesson-content "Use PostgreSQL for structured data"})
    (lesson/create helper/*conn* {:lesson-type "technique" :lesson-content "Other content"})
    (let [result (execute-op helper/*conn* "lesson-search" {:query "PostgreSQL"})]
      (is (= :success (:status result)))
      (is (= 1 (:count result))))))

(deftest handle-lesson-validate-test
  (main/create-schema! helper/*conn*)

  (testing "increases confidence"
    (let [l (lesson/create helper/*conn* {:lesson-type "technique" :lesson-content "Tip" :confidence 0.5})
          result (execute-op helper/*conn* "lesson-validate" {:lesson_id (:id l)})]
      (is (= :success (:status result)))
      (is (> (get-in result [:data :confidence]) 0.5)))))

(deftest handle-lesson-invalidate-test
  (main/create-schema! helper/*conn*)

  (testing "decreases confidence"
    (let [l (lesson/create helper/*conn* {:lesson-type "technique" :lesson-content "Tip" :confidence 0.5})
          result (execute-op helper/*conn* "lesson-invalidate" {:lesson_id (:id l)})]
      (is (= :success (:status result)))
      (is (< (get-in result [:data :confidence]) 0.5)))))

(deftest handle-lesson-delete-test
  (main/create-schema! helper/*conn*)

  (testing "deletes lesson"
    (let [l (lesson/create helper/*conn* {:lesson-type "technique" :lesson-content "To delete"})
          result (execute-op helper/*conn* "lesson-delete" {:lesson_id (:id l)})]
      (is (= :success (:status result)))
      (is (true? (get-in result [:data :deleted])))
      (is (nil? (lesson/get-by-id helper/*conn* (:id l)))))))

;; -----------------------------------------------------------------------------
;; Trace Operation Tests
;; -----------------------------------------------------------------------------

(deftest handle-trace-add-test
  (main/create-schema! helper/*conn*)

  (testing "adds trace to task"
    (let [p (plan/create helper/*conn* "trace-plan" nil nil)
          t (task/create helper/*conn* (:id p) "traced-task" nil nil nil)
          result (execute-op helper/*conn* "trace-add"
                             {:task_id (:id t)
                              :trace_type "thought"
                              :content "Need to refactor this"})]
      (is (= :success (:status result)))
      (is (number? (get-in result [:data :id])))
      (is (= "thought" (get-in result [:data :trace_type])))))

  (testing "adds trace with metadata"
    (let [p (plan/create helper/*conn* "trace-meta-plan" nil nil)
          t (task/create helper/*conn* (:id p) "task" nil nil nil)
          result (execute-op helper/*conn* "trace-add"
                             {:task_id (:id t)
                              :trace_type "action"
                              :content "Created database"
                              :metadata {:db "postgres"}})]
      (is (= :success (:status result)))))

  (testing "fails with invalid trace type"
    (let [p (plan/create helper/*conn* "invalid-trace-plan" nil nil)
          t (task/create helper/*conn* (:id p) "task" nil nil nil)
          result (execute-op helper/*conn* "trace-add"
                             {:task_id (:id t)
                              :trace_type "invalid"
                              :content "Test"})]
      (is (= :error (:status result)))
      (is (re-find #"Invalid trace type" (:message result))))))

(deftest handle-trace-get-task-test
  (main/create-schema! helper/*conn*)

  (testing "returns traces for task"
    (let [p (plan/create helper/*conn* "get-trace-plan" nil nil)
          t (task/create helper/*conn* (:id p) "task" nil nil nil)
          _ (trace/create helper/*conn* {:plan-id (:id p) :task-id (:id t) :trace-type "thought" :sequence-num 1 :content "One"})
          _ (trace/create helper/*conn* {:plan-id (:id p) :task-id (:id t) :trace-type "action" :sequence-num 2 :content "Two"})
          result (execute-op helper/*conn* "trace-get-task" {:task_id (:id t)})]
      (is (= :success (:status result)))
      (is (= 2 (:count result))))))

(deftest handle-trace-get-plan-test
  (main/create-schema! helper/*conn*)

  (testing "returns all traces for plan"
    (let [p (plan/create helper/*conn* "plan-traces" nil nil)
          t1 (task/create helper/*conn* (:id p) "task1" nil nil nil)
          t2 (task/create helper/*conn* (:id p) "task2" nil nil nil)
          _ (trace/create helper/*conn* {:plan-id (:id p) :task-id (:id t1) :trace-type "thought" :sequence-num 1 :content "A"})
          _ (trace/create helper/*conn* {:plan-id (:id p) :task-id (:id t2) :trace-type "thought" :sequence-num 2 :content "B"})
          result (execute-op helper/*conn* "trace-get-plan" {:plan_id (:id p)})]
      (is (= :success (:status result)))
      (is (= 2 (:count result))))))

;; -----------------------------------------------------------------------------
;; End-to-End Integration Tests
;; -----------------------------------------------------------------------------

(deftest full-crud-workflow-test
  (main/create-schema! helper/*conn*)

  (testing "complete CRUD workflow via MCP operations"
    (let [p (plan/create helper/*conn* "workflow-plan" "Test workflow" nil)
          ;; Create
          create-result (execute-op helper/*conn* "fact-create"
                                    {:plan_id (:id p)
                                     :name "workflow-fact"
                                     :description "Testing CRUD"
                                     :content "Initial content"})
          fact-id (get-in create-result [:data :id])]
      (is (= :success (:status create-result)))

      ;; Read (get)
      (let [result (execute-op helper/*conn* "fact-get" {:fact_id fact-id})]
        (is (= :success (:status result)))
        (is (= "workflow-fact" (get-in result [:data :name]))))

      ;; Read (list)
      (let [result (execute-op helper/*conn* "fact-list" {:plan_id (:id p)})]
        (is (= :success (:status result)))
        (is (= 1 (:count result))))

      ;; Update
      (let [result (execute-op helper/*conn* "fact-update"
                               {:fact_id fact-id
                                :content "Updated content"})]
        (is (= :success (:status result)))
        (is (= "Updated content" (get-in result [:data :content]))))

      ;; Delete
      (let [result (execute-op helper/*conn* "fact-delete" {:fact_id fact-id})]
        (is (= :success (:status result)))
        (is (true? (get-in result [:data :deleted]))))

      ;; Verify deleted
      (let [result (execute-op helper/*conn* "fact-get" {:fact_id fact-id})]
        (is (= :error (:status result)))))))

(deftest full-project-workflow-test
  (main/create-schema! helper/*conn*)

  (testing "complete project lifecycle via MCP"
    ;; Create plan
    (let [plan-result (execute-op helper/*conn* "plan-create"
                                  {:name "Q1 Roadmap"
                                   :description "Quarterly goals"})
          plan-id (get-in plan-result [:data :id])]
      (is (= :success (:status plan-result)))

      ;; Create a task
      (let [task-result (execute-op helper/*conn* "task-create"
                                    {:plan_id plan-id
                                     :name "Design API"
                                     :description "Create API spec"})
            task-id (get-in task-result [:data :id])]
        (is (= :success (:status task-result)))

        ;; Start task
        (let [start-result (execute-op helper/*conn* "task-start" {:task_id task-id})]
          (is (= :success (:status start-result)))
          (is (= "in_progress" (get-in start-result [:data :status]))))

        ;; Add trace
        (let [trace-result (execute-op helper/*conn* "trace-add"
                                       {:task_id task-id
                                        :trace_type "thought"
                                        :content "Using REST conventions"})]
          (is (= :success (:status trace-result))))

        ;; Create fact
        (let [fact-result (execute-op helper/*conn* "fact-create"
                                      {:plan_id plan-id
                                       :name "API Base URL"
                                       :content "https://api.example.com/v1"})]
          (is (= :success (:status fact-result))))

        ;; Create lesson
        (let [lesson-result (execute-op helper/*conn* "lesson-create"
                                        {:lesson_type "success_pattern"
                                         :lesson_content "Early API validation saves time"
                                         :plan_id plan-id})]
          (is (= :success (:status lesson-result))))

        ;; Complete task
        (let [complete-result (execute-op helper/*conn* "task-complete" {:task_id task-id})]
          (is (= :success (:status complete-result)))
          (is (= "completed" (get-in complete-result [:data :status]))))

        ;; Show plan with all data
        (let [show-result (execute-op helper/*conn* "plan-show" {:plan_id plan-id})]
          (is (= :success (:status show-result)))
          (is (= "Q1 Roadmap" (get-in show-result [:data :plan :name])))
          (is (= 1 (count (get-in show-result [:data :tasks]))))
          (is (= 1 (count (get-in show-result [:data :facts])))))))))
