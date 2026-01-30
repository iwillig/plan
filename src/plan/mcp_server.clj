(ns plan.mcp-server
  "MCP server implementation for the planning tool.
   Uses a capabilities-based RPC design with two tools:
   - get_capabilities: self-documenting API reference
   - execute: RPC dispatcher for all operations"
  (:require
   [clojure-mcp.core :as mcp-core]
   [clojure.data.json :as json]
   [clojure.string :as str]
   [plan.config :as config]
   [plan.db :as db]
   [plan.models.fact :as fact]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Capabilities Document
;; -----------------------------------------------------------------------------

(def ^:private capabilities
  "Self-documenting API specification that the LLM can query to discover
   available operations and their parameters."
  {:version "1.0.0"
   :description "Plan Management System - Fact Operations API"
   :note "All operations require a valid database. Use init command if needed."
   :operations
   {:fact/list
    {:description "List all facts for a specific plan"
     :parameters
     {:plan_id {:type "integer"
                :description "ID of the plan to list facts for"
                :required true
                :example 1}}
     :returns
     {:type "array"
      :description "Array of fact objects"
      :items {:type "object"
              :properties {:id {:type "integer"}
                           :name {:type "string"}
                           :description {:type "string"}
                           :content {:type "string"}
                           :created_at {:type "string"}
                           :updated_at {:type "string"}}}}}

    :fact/get
    {:description "Get a single fact by its ID"
     :parameters
     {:fact_id {:type "integer"
                :description "ID of the fact to retrieve"
                :required true
                :example 1}}
     :returns
     {:type "object"
      :description "Fact object or null if not found"
      :properties {:id {:type "integer"}
                   :plan_id {:type "integer"}
                   :name {:type "string"}
                   :description {:type "string"}
                   :content {:type "string"}
                   :created_at {:type "string"}
                   :updated_at {:type "string"}}}}

    :fact/create
    {:description "Create a new fact in a plan"
     :parameters
     {:plan_id {:type "integer"
                :description "ID of the plan to add the fact to"
                :required true
                :example 1}
      :name {:type "string"
             :description "Unique name for the fact (required)"
             :required true
             :example "API Endpoint"}
      :description {:type "string"
                    :description "Brief description of the fact"
                    :required false
                    :example "Production API server URL"}
      :content {:type "string"
                :description "Full content of the fact"
                :required true
                :example "https://api.example.com/v1"}}
     :returns
     {:type "object"
      :description "Created fact object with generated ID"
      :properties {:id {:type "integer"}
                   :plan_id {:type "integer"}
                   :name {:type "string"}
                   :description {:type "string"}
                   :content {:type "string"}
                   :created_at {:type "string"}
                   :updated_at {:type "string"}}}}

    :fact/update
    {:description "Update an existing fact"
     :parameters
     {:fact_id {:type "integer"
                :description "ID of the fact to update"
                :required true
                :example 1}
      :name {:type "string"
             :description "New name for the fact"
             :required false}
      :description {:type "string"
                    :description "New description"
                    :required false}
      :content {:type "string"
                :description "New content"
                :required false}}
     :note "At least one field to update must be provided"
     :returns
     {:type "object"
      :description "Updated fact object or null if not found"}}

    :fact/delete
    {:description "Delete a fact by ID"
     :parameters
     {:fact_id {:type "integer"
                :description "ID of the fact to delete"
                :required true
                :example 1}}
     :returns
     {:type "object"
      :description "Success status"
      :properties {:success {:type "boolean"}
                   :message {:type "string"}}}}}

   :examples
   [{:description "List all facts for plan 1"
     :request {:operation "fact/list" :plan_id 1}}
    {:description "Create a new fact"
     :request {:operation "fact/create"
               :plan_id 1
               :name "Database URL"
               :content "postgres://localhost/mydb"}}
    {:description "Get a specific fact"
     :request {:operation "fact/get" :fact_id 5}}
    {:description "Update fact content"
     :request {:operation "fact/update"
               :fact_id 5
               :content "Updated information here"}}
    {:description "Delete a fact"
     :request {:operation "fact/delete" :fact_id 5}}]})

;; -----------------------------------------------------------------------------
;; Operation Handlers
;; -----------------------------------------------------------------------------

(defn- validate-params
  "Validate that required parameters are present"
  [params required-keys]
  (let [missing (remove #(contains? params %) required-keys)]
    (when (seq missing)
      (throw (ex-info (str "Missing required parameters: "
                           (str/join ", " (map name missing)))
                      {:type :validation-error
                       :missing missing})))))

(defn- handle-fact-list
  "List all facts for a plan"
  [conn params]
  (validate-params params [:plan_id])
  (let [plan-id (:plan_id params)
        facts (fact/get-by-plan conn plan-id)]
    {:status :success
     :operation :fact/list
     :count (count facts)
     :data facts}))

(defn- handle-fact-get
  "Get a single fact by ID"
  [conn params]
  (validate-params params [:fact_id])
  (let [fact-id (:fact_id params)
        fact (fact/get-by-id conn fact-id)]
    (if fact
      {:status :success
       :operation :fact/get
       :data fact}
      {:status :error
       :operation :fact/get
       :message (str "Fact not found: " fact-id)})))

(defn- handle-fact-create
  "Create a new fact"
  [conn params]
  (validate-params params [:plan_id :name :content])
  (let [result (fact/create conn
                            (:plan_id params)
                            (:name params)
                            (:description params)
                            (:content params))]
    {:status :success
     :operation :fact/create
     :data result}))

(defn- handle-fact-update
  "Update an existing fact"
  [conn params]
  (validate-params params [:fact_id])
  (let [fact-id (:fact_id params)
        updates (select-keys params [:name :description :content])
        _ (when (empty? updates)
            (throw (ex-info "No fields to update provided"
                            {:type :validation-error})))
        result (fact/update conn fact-id updates)]
    (if result
      {:status :success
       :operation :fact/update
       :data result}
      {:status :error
       :operation :fact/update
       :message (str "Fact not found: " fact-id)})))

(defn- handle-fact-delete
  "Delete a fact"
  [conn params]
  (validate-params params [:fact_id])
  (let [fact-id (:fact_id params)
        _ (fact/delete conn fact-id)]
    {:status :success
     :operation :fact/delete
     :data {:deleted true :fact_id fact-id}}))

;; -----------------------------------------------------------------------------
;; RPC Router
;; -----------------------------------------------------------------------------

(defn- execute-operation
  "Route to the appropriate operation handler"
  [conn operation params]
  (try
    (case operation
      "fact/list" (handle-fact-list conn params)
      "fact/get" (handle-fact-get conn params)
      "fact/create" (handle-fact-create conn params)
      "fact/update" (handle-fact-update conn params)
      "fact/delete" (handle-fact-delete conn params)
      (throw (ex-info (str "Unknown operation: " operation)
                      {:type :unknown-operation
                       :operation operation})))
    (catch Exception e
      (let [data (ex-data e)]
        {:status :error
         :operation operation
         :message (ex-message e)
         :error-type (:type data)}))))

;; -----------------------------------------------------------------------------
;; MCP Tool Functions
;; -----------------------------------------------------------------------------

(defn get-capabilities-tool
  "Returns the capabilities document as JSON string"
  [_args _callback]
  (json/write-str capabilities :escape-slash false))

(defn execute-tool
  "Execute an RPC operation
   Expected args format:
   {:request '{\"operation\": \"fact/list\", \"plan_id\": 1}'}
   
   The request is a JSON string containing the operation and parameters."
  [args callback]
  (let [request-json (get args "request")
        db-path (config/db-path)]
    (try
      (let [request (json/read-str request-json :key-fn keyword)
            operation (:operation request)
            params (dissoc request :operation)]
        (db/with-connection db-path
          (fn [conn]
            (let [result (execute-operation conn operation params)]
              (callback [(json/write-str result :escape-slash false)] false)))))
      (catch Exception e
        (callback [(json/write-str {:status :error
                                    :message (str "JSON parse error: "
                                                  (.getMessage e))}
                                   :escape-slash false)]
                  true)))))

;; -----------------------------------------------------------------------------
;; MCP Server Setup
;; -----------------------------------------------------------------------------

(defn make-tools
  "Create the MCP tools for the planning system"
  [_nrepl-client-atom _working-dir]
  [{:name "get_capabilities"
    :description (str "Get the capabilities document for the planning system. "
                      "This tool returns a complete API specification including "
                      "all available operations, their parameters, and examples. "
                      "Call this first to discover how to use the system.")
    :schema {:type "object"
             :properties {}
             :required []}
    :tool-fn (fn [_exchange _args callback]
               (callback [(get-capabilities-tool _args callback)] false))}

   {:name "execute"
    :description (str "Execute an operation on the planning system. "
                      "Requires a JSON request string with 'operation' and parameters. "
                      "Use get_capabilities first to discover available operations. "
                      "Request format: '{\"operation\": \"fact/list\", \"plan_id\": 1}'")
    :schema {:type "object"
             :properties
             {:request {:type "string"
                        :description "JSON string with 'operation' and parameters"}}
             :required ["request"]}
    :tool-fn execute-tool}])

(defn start-mcp-server
  "Start the MCP server for the planning tool
   
   Options:
   - :port - nREPL port (optional, for project discovery)
   - :project-dir - Project directory (can be used instead of :port)"
  [opts]
  (mcp-core/build-and-start-mcp-server
   opts
   {:make-tools-fn make-tools
    :make-prompts-fn (constantly [])  ;; No prompts for now
    :make-resources-fn (constantly [])}))  ;; No resources for now
