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
   [plan.models.plan :as plan]
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
