# Plan Serialization Examples

This directory contains examples of the hierarchical YAML format used by the
plan markdown serializer.

## Format Overview

The plan serialization format uses YAML front matter with nested structures
for tasks and facts. This provides:

- **Human-readable** - Easy to read and edit by hand
- **Hierarchical** - Tasks and facts are proper lists, not flat keys
- **Arbitrary depth** - Full YAML spec compliance via clj-yaml
- **GraalVM compatible** - Works with native-image compilation

## Structure

```yaml
---
name: Plan Name
description: Plan description
completed: false
tasks:
- name: Task 1
  description: Task description
  content: |
    Markdown content for the task
  completed: true
- name: Task 2
  description: Another task
  completed: false
facts:
- name: Fact Name
  description: What this fact is about
  content: The fact content
---

# Markdown Body

Any markdown content goes here after the front matter.
```

## Fields

### Plan Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | **Required.** The plan name |
| `description` | string | Short description of the plan |
| `completed` | boolean | Whether the plan is complete |
| `id` | integer | Database ID (optional) |
| `created_at` | string | ISO 8601 timestamp (optional) |
| `updated_at` | string | ISO 8601 timestamp (optional) |

### Task Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | **Required.** The task name |
| `description` | string | Short description |
| `content` | string | Full markdown content |
| `completed` | boolean | Whether the task is complete |
| `parent_id` | integer | ID of parent task (for subtasks) |
| `id` | integer | Database ID (optional) |
| `created_at` | string | ISO 8601 timestamp (optional) |
| `updated_at` | string | ISO 8601 timestamp (optional) |

### Fact Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | **Required.** The fact name |
| `description` | string | What this fact represents |
| `content` | string | The fact content (can be markdown) |
| `id` | integer | Database ID (optional) |
| `created_at` | string | ISO 8601 timestamp (optional) |
| `updated_at` | string | ISO 8601 timestamp (optional) |

## Example Files

- `complete-example.md` - A full example with multiple tasks and facts

## Usage

### Reading a Plan

```clojure
(require '[plan.serializers.markdown-v2 :as md])

;; Read from file
(def data (md/read-plan-from-file "plan.md"))

;; Access the data
(:plan data)    ; => {:name "..." :description "..." ...}
(:tasks data)   ; => [{:name "Task 1" ...} {:name "Task 2" ...}]
(:facts data)   ; => [{:name "Fact 1" ...}]
```

### Writing a Plan

```clojure
(require '[plan.serializers.markdown-v2 :as md])

(def plan
  {:name "My Plan"
   :description "A description"
   :content "# Markdown content"
   :completed false})

(def tasks
  [{:name "Task 1" :completed true}
   {:name "Task 2" :completed false}])

(def facts
  [{:name "Fact 1" :content "Important info"}])

;; Write to file
(md/write-plan-to-file "plan.md" plan tasks facts)
```

### Working with Strings

```clojure
;; Serialize to string
(def markdown-string (md/plan->markdown plan tasks facts))

;; Parse from string
(def data (md/markdown->plan markdown-string))
```

### Validation

```clojure
;; Check if markdown is valid
(md/valid-plan-markdown? markdown-string)  ; => true or false

;; Get metadata without full parsing
(md/get-plan-metadata markdown-string)  ; => {:name "..." :content "..."}

;; Count tasks/facts
(md/count-tasks markdown-string)   ; => 3
(md/count-facts markdown-string)   ; => 2
```
