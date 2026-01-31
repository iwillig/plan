(ns plan.failjure.malli
  "Malli schemas for failjure types and functions.

   Similar to failjure-spec but using Malli instead of clojure.spec.
   Provides schemas for:
   - Failure type
   - Value type (either success or failure)
   - Function schemas for failjure.core functions

   Usage:
     (require '[plan.failjure.malli :as fm])
     (require '[failjure.core :as f])
     (require '[malli.core :as m])

     ;; Validate a Failure
     (m/validate fm/Failure (f/fail \"error\"))
     ;; => true

     ;; Validate any value (success or failure)
     (m/validate fm/Value {:some :data})
     ;; => true
     (m/validate fm/Value (f/fail \"error\"))
     ;; => true

     ;; Use in your own schemas
     (def MyResponse
       [:map
        [:status :int]
        [:body fm/Value]])

   This namespace is useful for:
   - Documenting failjure usage in your codebase
   - Validating API responses that may contain failures
   - Testing with malli.generator
   - Schema-first development with failjure"
  (:require
   [failjure.core :as f]
   [malli.core :as m]))

(set! *warn-on-reflection* true)

;; -----------------------------------------------------------------------------
;; Core Type Schemas
;; -----------------------------------------------------------------------------

(def Failure
  "Schema for a failjure Failure.

   A Failure is a record with at minimum a :message string field,
   but may contain additional fields for context.

   Examples:
     (f/fail \"Something went wrong\")
     ;; => #failjure.core.Failure{:message \"Something went wrong\"}

     (assoc (f/fail \"Error\") :code 404 :details \"Not found\")
     ;; => #failjure.core.Failure{:message \"Error\" :code 404 :details \"Not found\"}"
  [:and
   [:map
    [:message :string]]
   [:fn
    {:error/message "Must be a failjure Failure instance"}
    f/failed?]])

(def Value
  "Schema for any value - either a Failure or a success value.

   This represents the union type that failjure functions work with.
   Most failjure operations accept a Value and return a Value.

   Examples:
     {:success :data}  ;; ok value
     (f/fail \"error\") ;; failure value
     42                ;; ok value
     nil               ;; ok value"
  [:or
   {:error/message "Must be either a success value or a Failure"}
   Failure
   :any])

;; -----------------------------------------------------------------------------
;; Function Schemas
;; -----------------------------------------------------------------------------

;; Note: Malli's m/=> is for function instrumentation, but unlike spec,
;; it doesn't provide automatic runtime checking. These schemas serve as
;; documentation and can be used with malli.dev for development-time checking.

(comment
  "Function schema format for reference:"

  (m/=> function-name
        [:=> [:cat arg1-schema arg2-schema]  ; args
         return-schema])                      ; return

  (m/=> multi-arity-function
        [:function
         [:=> [:cat arg1] ret1]
         [:=> [:cat arg1 arg2] ret2]]))

;; f/fail - Creates a Failure
(def fail-schema
  "Schema for failjure.core/fail

   Arities:
     (fail msg)
     (fail msg & fmt-parts)"
  [:function
   {:description "Create a Failure with a message"}
   [:=> [:cat :string] Failure]
   [:=> [:cat :string [:* :any]] Failure]])

;; f/failed? - Checks if value is a Failure
(def failed?-schema
  "Schema for failjure.core/failed?

   Takes any value, returns boolean."
  [:=> [:cat :any] :boolean])

;; f/message - Extracts message from Failure
(def message-schema
  "Schema for failjure.core/message

   Takes a Failure, returns its message string."
  [:=> [:cat Failure] :string])

;; f/ok? - Checks if value is NOT a Failure
(def ok?-schema
  "Schema for failjure.core/ok?

   Takes any value, returns boolean."
  [:=> [:cat :any] :boolean])

;; f/attempt-all - Monadic bind for Failures
(def attempt-all-schema
  "Schema for failjure.core/attempt-all macro

   Bindings can bind to any Value, if any is a Failure,
   short-circuits and returns that Failure."
  [:=>
   [:cat
    [:vector :any]  ; bindings
    :any]           ; body
   Value])

;; f/if-let-ok? - Conditional on success
(def if-let-ok?-schema
  "Schema for failjure.core/if-let-ok? macro"
  [:function
   [:=> [:cat [:vector :any] :any] Value]
   [:=> [:cat [:vector :any] :any :any] Value]])

;; f/when-let-ok? - When on success
(def when-let-ok?-schema
  "Schema for failjure.core/when-let-ok? macro"
  [:=> [:cat [:vector :any] [:* :any]] Value])

;; f/if-let-failed? - Conditional on failure
(def if-let-failed?-schema
  "Schema for failjure.core/if-let-failed? macro"
  [:function
   [:=> [:cat [:vector :any] :any] Value]
   [:=> [:cat [:vector :any] :any :any] Value]])

;; f/when-let-failed? - When on failure
(def when-let-failed?-schema
  "Schema for failjure.core/when-let-failed? macro"
  [:=> [:cat [:vector :any] [:* :any]] Value])

;; f/when-failed - Execute on failure
(def when-failed-schema
  "Schema for failjure.core/when-failed macro"
  [:=> [:cat [:vector :any] :any] Value])

;; f/ok-> - Thread-first for ok values
(def ok->-schema
  "Schema for failjure.core/ok-> macro

   Like -> but stops threading on Failure."
  [:=> [:cat Value [:* :any]] Value])

;; f/ok->> - Thread-last for ok values
(def ok->>-schema
  "Schema for failjure.core/ok->> macro

   Like ->> but stops threading on Failure."
  [:=> [:cat Value [:* :any]] Value])

;; f/as-ok-> - Thread-as for ok values
(def as-ok->-schema
  "Schema for failjure.core/as-ok-> macro

   Like as-> but stops threading on Failure."
  [:=> [:cat Value :any [:* :any]] Value])

;; Assertion helpers
(def assert-with-schema
  "Schema for failjure.core/assert-with

   (assert-with pred value message)"
  [:=> [:cat [:=> [:cat :any] :boolean] :any :string] Value])

;; -----------------------------------------------------------------------------
;; Registry
;; -----------------------------------------------------------------------------

(def failjure-registry
  "Malli registry with failjure schemas.

   Can be merged into your application's schema registry.

   Usage:
     (def my-registry
       (merge
         (m/default-schemas)
         fm/failjure-registry
         my-app-schemas))

     (def MySchema
       [:map
        [:result :failjure/Value]
        [:error {:optional true} :failjure/Failure]])"
  {:failjure/Failure Failure
   :failjure/Value Value
   :failjure/fail fail-schema
   :failjure/failed? failed?-schema
   :failjure/message message-schema
   :failjure/ok? ok?-schema
   :failjure/attempt-all attempt-all-schema
   :failjure/if-let-ok? if-let-ok?-schema
   :failjure/when-let-ok? when-let-ok?-schema
   :failjure/if-let-failed? if-let-failed?-schema
   :failjure/when-let-failed? when-let-failed?-schema
   :failjure/when-failed when-failed-schema
   :failjure/ok-> ok->-schema
   :failjure/ok->> ok->>-schema
   :failjure/as-ok-> as-ok->-schema
   :failjure/assert-with assert-with-schema})

;; -----------------------------------------------------------------------------
;; Helper Functions
;; -----------------------------------------------------------------------------

(defn failure?
  "Malli validator for Failure type.

   Can be used in schemas with [:fn failure?]

   Example:
     [:and [:map [:data :any]] [:fn failure?]]"
  [x]
  (f/failed? x))

(defn ok?
  "Malli validator for ok (non-Failure) values.

   Can be used in schemas with [:fn ok?]

   Example:
     [:and [:map [:result :any]] [:fn ok?]]"
  [x]
  (not (f/failed? x)))
