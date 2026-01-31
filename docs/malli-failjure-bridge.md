# Malli-Failjure Bridge

## Overview

The `plan.validation` namespace provides a clean bridge between Malli schema validation and Failjure error handling. This allows you to:

1. Validate data against Malli schemas
2. Get failjure `Failure` objects when validation fails
3. Extract humanized error messages from failures
4. Seamlessly integrate with `failjure.core/attempt-all` chains

## Core API

### `validate-or-fail`

```clojure
(validate-or-fail schema data)
```

**Behavior:**
- If data is valid → returns data unchanged
- If data is invalid → returns a `failjure.core.Failure` with:
  - `:message` - Human-readable string describing which fields failed
  - `:errors` - Humanized Malli error map/vector

**Examples:**

```clojure
(require '[plan.validation :as v])

(def User [:map 
           [:name [:string {:min 3}]] 
           [:age [:int {:min 0 :max 150}]]])

;; Valid data passes through
(v/validate-or-fail User {:name "Alice" :age 30})
;;=> {:name "Alice" :age 30}

;; Invalid data returns Failure
(v/validate-or-fail User {:name "Al" :age 200})
;;=> #failjure.core.Failure{
;;     :message "Validation failed for fields: name, age"
;;     :errors {:name ["should be at least 3 characters"]
;;              :age ["should be at most 150"]}}
```

### `validation-errors`

```clojure
(validation-errors failure)
```

Extracts the humanized error map from a validation failure.

**Examples:**

```clojure
(def result (v/validate-or-fail User {:name "Al" :age 200}))

(v/validation-errors result)
;;=> {:name ["should be at least 3 characters"]
;;     :age ["should be at most 150"]}

;; Returns nil for valid data
(v/validation-errors {:name "Alice" :age 30})
;;=> nil
```

### `validation-failed?`

```clojure
(validation-failed? x)
```

Checks if a value is a validation failure (more specific than `failjure.core/failed?`).

**Examples:**

```clojure
(def result (v/validate-or-fail User {:name "Al" :age 200}))

(v/validation-failed? result)
;;=> true

;; False for other failures
(v/validation-failed? (f/fail "Some other error"))
;;=> false

;; False for valid data
(v/validation-failed? {:name "Alice" :age 30})
;;=> false
```

## Integration with Failjure

The bridge works seamlessly with `failjure.core/attempt-all`:

```clojure
(require '[failjure.core :as f])

(defn create-user [data]
  (f/attempt-all
   [validated (v/validate-or-fail User data)
    ;; If validation fails, attempt-all short-circuits here
    _ (when (< (:age validated) 18)
        (f/fail "Must be 18 or older"))]
   {:success true :user validated}))

;; Valid adult
(create-user {:name "Alice" :age 30})
;;=> {:success true :user {:name "Alice" :age 30}}

;; Invalid schema
(create-user {:name "Al" :age 200})
;;=> #failjure.core.Failure{
;;     :message "Validation failed for fields: name, age"
;;     :errors {:name [...] :age [...]}}

;; Valid schema but too young
(create-user {:name "Bob" :age 15})
;;=> #failjure.core.Failure{:message "Must be 18 or older"}
```

## Error Message Format

### For map schemas with multiple errors:
```
"Validation failed for fields: name, age"
:errors => {:name ["should be at least 3 characters"]
            :age ["should be at most 150"]}
```

### For simple type errors:
```
"Validation failed: [\"invalid type\"]"
:errors => ["invalid type"]
```

### For missing required keys:
```
"Validation failed for fields: name, age"
:errors => {:name ["missing required key"]
            :age ["missing required key"]}
```

## Common Patterns

### Pattern 1: Simple Validation

```clojure
(defn update-user [id updates]
  (f/attempt-all
   [validated (v/validate-or-fail UserUpdate updates)
    user (find-user id)]
   (db/update user validated)))
```

### Pattern 2: Multiple Validations

```clojure
(defn create-task [plan-id task-data]
  (f/attempt-all
   [validated-task (v/validate-or-fail TaskCreate task-data)
    plan (validate-plan-exists plan-id)
    _ (validate-plan-not-completed plan)]
   (db/create-task validated-task)))
```

### Pattern 3: Conditional Validation

```clojure
(defn process [data]
  (f/attempt-all
   [validated (v/validate-or-fail Schema data)
    result (if (:premium validated)
             (validate-premium-features validated)
             validated)]
   (handle result)))
```

### Pattern 4: Error Handling in APIs

```clojure
(defn api-handler [request]
  (let [result (create-user (:body request))]
    (if (v/validation-failed? result)
      {:status 400
       :body {:errors (v/validation-errors result)}}
      {:status 200
       :body result})))
```

## Testing

The bridge includes comprehensive tests covering:
- Valid data passthrough
- Validation failures with humanized errors
- Missing required fields
- Nil data handling
- Integration with `attempt-all`
- Optional fields
- Nested schemas

Run tests:
```clojure
;; In REPL
(require '[clojure.test :as t])
(t/run-tests 'plan.validation-test)
```

## Benefits Over Manual Validation

**Before (manual validation):**
```clojure
(defn create-task [params]
  (let [missing (remove #(contains? params %) [:plan_id :name])]
    (if (seq missing)
      (f/fail "Missing required parameters: %s" 
              (str/join ", " (map name missing)))
      (if (str/blank? (:name params))
        (f/fail "Name cannot be blank")
        (task/create params)))))
```

**After (schema-based):**
```clojure
(defn create-task [params]
  (f/attempt-all
   [validated (v/validate-or-fail TaskCreate params)]
   (task/create validated)))
```

**Advantages:**
- ✅ Single source of truth for validation rules (the schema)
- ✅ Automatic humanized error messages
- ✅ Handles multiple errors at once
- ✅ Less code to write and maintain
- ✅ Type safety from schemas
- ✅ Better error messages for API clients

## Next Steps

This bridge can be used to refactor existing operations:
- `plan.operations.task` - Replace manual validation
- `plan.operations.plan` - Replace manual validation  
- `plan.operations.fact` - Replace manual validation

See `plan.schemas` for existing schemas that can be used with this bridge.
