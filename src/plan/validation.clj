(ns plan.validation
  "Bridge between Malli schema validation and Failjure error handling.

   This namespace provides a single interface for validating data against Malli schemas
   and returning failjure Failures with humanized error messages when validation fails.

   Usage:
     (require '[plan.validation :as v])

     (def User [:map [:name [:string {:min 3}]] [:age [:int {:min 0 :max 150}]]])

     ;; Valid data passes through
     (v/validate-or-fail User {:name \"Alice\" :age 30})
     ;; => {:name \"Alice\" :age 30}

     ;; Invalid data returns a Failure with humanized errors
     (v/validate-or-fail User {:name \"Al\" :age 200})
     ;; => #failjure.core.Failure{:message \"Validation failed for fields: name, age\"
     ;;                            :errors {:name [\"should be at least 3 characters\"]
     ;;                                     :age [\"should be at most 150\"]}}

     ;; Extract errors from a failure
     (def result (v/validate-or-fail User {:name \"Al\" :age 200}))
     (v/validation-errors result)
     ;; => {:name [\"should be at least 3 characters\"] :age [\"should be at most 150\"]}

     ;; Works seamlessly with failjure's attempt-all
     (f/attempt-all [validated (v/validate-or-fail User data)]
       (create-user validated))"
  (:require
   [clojure.string :as str]
   [failjure.core :as f]
   [malli.core :as m]
   [malli.error :as me]))

(set! *warn-on-reflection* true)

(defn validate-or-fail
  "Validate data against a Malli schema.

   If validation succeeds, returns the data unchanged.
   If validation fails, returns a failjure Failure with:
   - A human-readable message describing which fields failed
   - An :errors key containing humanized Malli error messages

   Args:
     schema - Malli schema to validate against
     data   - Data to validate

   Returns:
     The data if valid, or a failjure Failure with :errors if invalid.

   Examples:
     (validate-or-fail [:string {:min 3}] \"hello\")
     ;; => \"hello\"

     (validate-or-fail [:string {:min 3}] \"hi\")
     ;; => #failjure.core.Failure{:message \"Validation failed: ...\"
     ;;                            :errors [\"should be at least 3 characters\"]}"
  [schema data]
  (if (m/validate schema data)
    data
    (let [errors (-> (m/explain schema data)
                     (me/humanize))
          msg (if (map? errors)
                (str "Validation failed for fields: "
                     (str/join ", " (map name (keys errors))))
                (str "Validation failed: " errors))]
      (-> (f/fail msg)
          (assoc :errors errors)))))

(defn validation-errors
  "Extract humanized Malli errors from a failjure Failure.

   Args:
     failure - A failjure Failure (typically from validate-or-fail)

   Returns:
     The humanized errors map/vector, or nil if not a failure or no errors.

   Examples:
     (def result (validate-or-fail schema data))
     (validation-errors result)
     ;; => {:name [\"should be at least 3 characters\"]}

     (validation-errors {:valid :data})
     ;; => nil"
  [failure]
  (when (f/failed? failure)
    (:errors failure)))

(defn validation-failed?
  "Check if a value is a failjure Failure with validation errors.

   This is more specific than failjure.core/failed? as it checks for
   the presence of :errors, indicating it's from validate-or-fail.

   Args:
     x - Value to check

   Returns:
     true if x is a Failure with :errors, false otherwise.

   Examples:
     (validation-failed? (validate-or-fail schema bad-data))
     ;; => true

     (validation-failed? (f/fail \"Some other error\"))
     ;; => false

     (validation-failed? {:valid :data})
     ;; => false"
  [x]
  (and (f/failed? x)
       (contains? x :errors)))
