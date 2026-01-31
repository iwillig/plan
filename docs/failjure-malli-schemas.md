# Failjure Malli Schemas

## Overview

The `plan.failjure.malli` namespace provides Malli schemas for the [failjure](https://github.com/adambard/failjure) library, similar to how [failjure-spec](https://github.com/adambard/failjure-spec) provides clojure.spec definitions.

This allows you to:
- **Validate** failjure types with Malli
- **Document** APIs that use failjure
- **Compose** failjure types into larger schemas
- **Generate** test data for failjure-based code
- **Use registry** for schema references throughout your codebase

## Installation

Already included in this project. Just require it:

```clojure
(require '[plan.failjure.malli :as fm])
(require '[malli.core :as m])
(require '[failjure.core :as f])
```

## Core Schemas

### `Failure`

Schema for a failjure `Failure` instance.

```clojure
;; Definition
[:and
 [:map [:message :string]]
 [:fn f/failed?]]

;; Usage
(m/validate fm/Failure (f/fail "Something went wrong"))
;;=> true

(m/validate fm/Failure {:message "not a real failure"})
;;=> false

;; Failures can have additional fields
(m/validate fm/Failure 
  (assoc (f/fail "Error") :code 404 :details "Not found"))
;;=> true
```

### `Value`

Schema for any value - either a `Failure` or a success value.

```clojure
;; Definition
[:or Failure :any]

;; Usage - accepts anything
(m/validate fm/Value {:success :data})
;;=> true

(m/validate fm/Value (f/fail "error"))
;;=> true

(m/validate fm/Value nil)
;;=> true
```

## Schema Composition

Use failjure schemas in your own schemas:

### API Response Example

```clojure
(def APIResponse
  [:map
   [:status :int]
   [:body fm/Value]])

;; Success response
(m/validate APIResponse
  {:status 200
   :body {:user "Alice" :id 123}})
;;=> true

;; Error response
(m/validate APIResponse
  {:status 500
   :body (f/fail "Database connection failed")})
;;=> true
```

### Operation Result Example

```clojure
(def OperationResult
  [:map
   [:success :boolean]
   [:data fm/Value]
   [:error {:optional true} fm/Failure]])

;; Successful operation
(m/validate OperationResult
  {:success true
   :data {:id 123 :name "Task"}})
;;=> true

;; Failed operation
(m/validate OperationResult
  {:success false
   :data (f/fail "Validation failed")
   :error (f/fail "Name is required")})
;;=> true
```

### Batch Processing Example

```clojure
(def BatchResult
  [:map
   [:results [:vector fm/Value]]])

;; Mix of successes and failures
(m/validate BatchResult
  {:results [{:id 1 :status "ok"}
             (f/fail "Item 2 failed")
             {:id 3 :status "ok"}]})
;;=> true
```

## Using the Registry

The `failjure-registry` allows you to reference schemas by keyword:

```clojure
(def my-registry
  (merge (m/default-schemas)
         fm/failjure-registry))

(def TaskResult
  [:map
   [:task [:maybe :map]]
   [:error {:optional true} :failjure/Failure]])

(m/validate TaskResult
  {:task nil
   :error (f/fail "Task not found")}
  {:registry my-registry})
;;=> true
```

### Available Registry Keys

- `:failjure/Failure` - Failure type
- `:failjure/Value` - Value type (success or failure)
- `:failjure/fail` - f/fail function schema
- `:failjure/failed?` - f/failed? function schema
- `:failjure/message` - f/message function schema
- `:failjure/ok?` - f/ok? function schema
- ... and more (see namespace for full list)

## Helper Functions

### `failure?`

Predicate for use in schemas:

```clojure
(def MySchema
  [:and
   [:map [:data :any]]
   [:fn fm/failure?]])

(m/validate MySchema (f/fail "error"))
;;=> true
```

### `ok?`

Predicate for non-failure values:

```clojure
(def SuccessSchema
  [:and
   [:map [:result :any]]
   [:fn fm/ok?]])

(m/validate SuccessSchema {:result {:data "value"}})
;;=> true

(m/validate SuccessSchema (f/fail "error"))
;;=> false
```

## Real-World Examples

### Validation with plan.validation

Combine with `plan.validation` for complete validation solution:

```clojure
(require '[plan.validation :as v])
(require '[plan.schemas :as schemas])

(def CreateTaskResponse
  [:map
   [:success :boolean]
   [:task [:or schemas/Task fm/Failure]]])

(defn create-task-api [params]
  (let [result (f/attempt-all
                 [validated (v/validate-or-fail schemas/TaskCreate params)
                  task (db/create-task validated)]
                 {:success true :task task})]
    (if (fm/failure? result)
      {:success false :task result}
      result)))
```

### Error Handling in APIs

```clojure
(defn api-handler [request]
  (let [result (process-request request)]
    (cond
      (fm/failure? result)
      {:status 400
       :body {:error (f/message result)
              :details (:errors result)}}
      
      :else
      {:status 200
       :body result})))
```

### Database Operations

```clojure
(def DatabaseResult
  [:or
   [:map [:data :any]]
   fm/Failure])

(defn fetch-user [id]
  (f/attempt-all
    [conn (db/get-connection)
     user (db/query conn ["SELECT * FROM users WHERE id = ?" id])]
    (if user
      {:data user}
      (f/fail "User not found: %s" id))))
```

## Function Schemas

Function schemas document the expected inputs and outputs:

```clojure
;; f/fail
[:function
 [:=> [:cat :string] Failure]
 [:=> [:cat :string [:* :any]] Failure]]

;; f/failed?
[:=> [:cat :any] :boolean]

;; f/message
[:=> [:cat Failure] :string]
```

These are primarily for documentation. For runtime validation, use the type schemas (`Failure`, `Value`).

## Testing

Generate test data using `malli.generator`:

```clojure
(require '[malli.generator :as mg])

;; Generate random valid data
(mg/generate fm/Value)
;;=> {:random :data} or sometimes a Failure

;; For testing, you can create specific schemas
(def AlwaysFailure fm/Failure)
(def NeverFailure [:and fm/Value [:fn fm/ok?]])
```

## Integration with plan.validation

The schemas work seamlessly with our validation layer:

```clojure
(require '[plan.validation :as v])

;; v/validate-or-fail returns fm/Value
(m/validate fm/Value (v/validate-or-fail schema data))
;;=> true (always, since it returns data or Failure)

;; Check if result is a validation failure
(let [result (v/validate-or-fail schema data)]
  (cond
    (v/validation-failed? result)
    ;; Handle validation error
    (fm/failure? result)
    ;; Also true for validation failures!
    ))
```

## Comparison with failjure-spec

| Feature | failjure-spec | plan.failjure.malli |
|---------|---------------|---------------------|
| Type system | clojure.spec | Malli |
| Instrumentation | Yes (with spec.alpha) | No (schemas for docs) |
| Generative testing | Via test.check | Via malli.generator |
| Error messages | Spec format | Humanized Malli format |
| Composability | Spec registry | Malli registry |
| Primary use case | Runtime checking | Data validation & docs |

## Best Practices

1. **Use `fm/Value` for operation results** - Most failjure-based functions should return `Value`

2. **Use `fm/Failure` for explicit error types** - When a field must be a failure

3. **Combine with validation** - Use both for complete data validation:
   ```clojure
   (f/attempt-all
     [validated (v/validate-or-fail schema data)]
     (process validated))
   ```

4. **Document APIs with schemas** - Make return types explicit:
   ```clojure
   (defn my-operation
     "Does something.
      Returns: fm/Value - success data or Failure"
     [input]
     ...)
   ```

5. **Use registry for consistency** - Define once, reference everywhere:
   ```clojure
   {:result :failjure/Value
    :error :failjure/Failure}
   ```

## See Also

- [failjure documentation](https://github.com/adambard/failjure)
- [Malli documentation](https://github.com/metosin/malli)
- [plan.validation](./malli-failjure-bridge.md) - Our validation bridge
- [plan.schemas](../src/plan/schemas.clj) - Application schemas
