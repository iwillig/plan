(ns plan.mcp-server
  "MCP server implementation for the planning tool.
   Uses a capabilities-based RPC design with two tools:
   - get_capabilities: self-documenting API reference
   - execute: RPC dispatcher for all operations
   
   Operations are delegated to plan.operations.* namespaces which use
   failjure for monadic error handling."
  (:require
   [clojure-mcp.core :as mcp-core]
   [clojure.data.json :as json]
   [failjure.core :as f]
   [plan.config :as config]
   [plan.db :as db]
   [plan.operations.fact :as fact-ops]))

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
   {:fact-list
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

    :fact-get
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

    :fact-create
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

    :fact-update
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

    :fact-delete
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
                   :message {:type "string"}}}}

    :fact-search
    {:description "Search facts using full-text search"
     :parameters
     {:query {:type "string"
              :description "Search query"
              :required true
              :example "database"}}
     :returns
     {:type "array"
      :description "Array of matching fact objects"}}}

   :examples
   [{:description "List all facts for plan 1"
     :request {:operation "fact-list" :plan_id 1}}
    {:description "Create a new fact"
     :request {:operation "fact-create"
               :plan_id 1
               :name "Database URL"
               :content "postgres://localhost/mydb"}}
    {:description "Get a specific fact"
     :request {:operation "fact-get" :fact_id 5}}
    {:description "Update fact content"
     :request {:operation "fact-update"
               :fact_id 5
               :content "Updated information here"}}
    {:description "Delete a fact"
     :request {:operation "fact-delete" :fact_id 5}}
    {:description "Search facts"
     :request {:operation "fact-search" :query "database"}}]})

;; -----------------------------------------------------------------------------
;; Result Formatting
;; -----------------------------------------------------------------------------

(defn- format-success
  "Format a successful operation result."
  [operation data]
  {:status :success
   :operation operation
   :data data})

(defn- format-success-with-count
  "Format a successful list operation result."
  [operation data]
  {:status :success
   :operation operation
   :count (count data)
   :data data})

(defn- format-error
  "Format a failure result from failjure."
  [operation failure]
  {:status :error
   :operation operation
   :message (f/message failure)})

;; -----------------------------------------------------------------------------
;; Operation Handlers
;; -----------------------------------------------------------------------------

(defn- handle-fact-list
  "List all facts for a plan"
  [conn params]
  (let [plan-id (:plan_id params)]
    (if (nil? plan-id)
      {:status :error
       :operation :fact-list
       :message "Missing required parameter: plan_id"
       :error-type :validation-error}
      (let [result (fact-ops/list-facts conn plan-id)]
        (if (f/failed? result)
          (format-error :fact-list result)
          (format-success-with-count :fact-list result))))))

(defn- handle-fact-get
  "Get a single fact by ID"
  [conn params]
  (let [fact-id (:fact_id params)]
    (if (nil? fact-id)
      {:status :error
       :operation :fact-get
       :message "Missing required parameter: fact_id"
       :error-type :validation-error}
      (let [result (fact-ops/get-fact conn fact-id)]
        (if (f/failed? result)
          (format-error :fact-get result)
          (format-success :fact-get result))))))

(defn- handle-fact-create
  "Create a new fact"
  [conn params]
  (let [result (fact-ops/create-fact conn params)]
    (if (f/failed? result)
      (format-error :fact-create result)
      (format-success :fact-create result))))

(defn- handle-fact-update
  "Update an existing fact"
  [conn params]
  (let [fact-id (:fact_id params)]
    (if (nil? fact-id)
      {:status :error
       :operation :fact-update
       :message "Missing required parameter: fact_id"
       :error-type :validation-error}
      (let [updates (select-keys params [:name :description :content])
            result (fact-ops/update-fact conn fact-id updates)]
        (if (f/failed? result)
          (format-error :fact-update result)
          (format-success :fact-update result))))))

(defn- handle-fact-delete
  "Delete a fact"
  [conn params]
  (let [fact-id (:fact_id params)]
    (if (nil? fact-id)
      {:status :error
       :operation :fact-delete
       :message "Missing required parameter: fact_id"
       :error-type :validation-error}
      (let [result (fact-ops/delete-fact conn fact-id)]
        (if (f/failed? result)
          (format-error :fact-delete result)
          (format-success :fact-delete result))))))

(defn- handle-fact-search
  "Search facts"
  [conn params]
  (let [query (:query params)]
    (if (nil? query)
      {:status :error
       :operation :fact-search
       :message "Missing required parameter: query"
       :error-type :validation-error}
      (let [result (fact-ops/search-facts conn query)]
        (if (f/failed? result)
          (format-error :fact-search result)
          (format-success-with-count :fact-search result))))))

;; -----------------------------------------------------------------------------
;; RPC Router
;; -----------------------------------------------------------------------------

(defn- execute-operation
  "Route to the appropriate operation handler"
  [conn operation params]
  (case operation
    "fact-list" (handle-fact-list conn params)
    "fact-get" (handle-fact-get conn params)
    "fact-create" (handle-fact-create conn params)
    "fact-update" (handle-fact-update conn params)
    "fact-delete" (handle-fact-delete conn params)
    "fact-search" (handle-fact-search conn params)
    {:status :error
     :operation operation
     :message (str "Unknown operation: " operation)
     :error-type :unknown-operation}))

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
   {:request '{\"operation\": \"fact-list\", \"plan_id\": 1}'}
   
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
                      "Request format: '{\"operation\": \"fact-list\", \"plan_id\": 1}'")
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
