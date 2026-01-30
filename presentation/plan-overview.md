% Plan: A Planning Tool for LLM Agents
% Ivan Willig
% January 2026

# What is Plan?

A CLI tool that helps LLM agents:

- Maintain persistent state across sessions
- Track task progress with dependencies
- Learn from experience (Reflexion pattern)
- Externalize reasoning (ReAct pattern)

# The Problem

| Challenge | Impact |
|-----------|--------|
| Context window limits | Lose track of progress |
| No cross-session memory | Repeat mistakes |
| Black-box reasoning | Can't debug decisions |
| No task sequencing | Work on blocked tasks |
| Vendor lock-in | Plans trapped in proprietary formats |

**Vendor Lock-In:** Claude, OpenCode, and Codium all maintain plans in their own
inaccessible formats. Agents like pi-agent have no planning mode at all. These
proprietary systems prevent you from reviewing, modifying, or transferring plans
between tools.

**The Solution:** Store all plans, tasks, and reasoning traces locally in a
SQLite database with portable Markdown export.

# Architecture Overview

```{.plantuml}
@startuml
!theme plain
skinparam backgroundColor white

package "Plan CLI" {
  [main.clj] as Main
  [config.clj] as Config
  [db.clj] as DB
}

package "Models" {
  [plan.clj] as Plan
  [task.clj] as Task
  [fact.clj] as Fact
  [lesson.clj] as Lesson
  [trace.clj] as Trace
}

database "SQLite" {
  [plans]
  [tasks]
  [facts]
  [lessons]
  [traces]
  [FTS5 indexes]
}

Main --> Config
Main --> DB
Main --> Plan
Main --> Task
Main --> Fact
DB --> [plans]
DB --> [tasks]
DB --> [facts]
Plan --> DB
Task --> DB
Fact --> DB
Lesson --> DB
Trace --> DB
@enduml
```

# Task Lifecycle

```{.plantuml}
@startuml
!theme plain
skinparam backgroundColor white

[*] --> pending
pending --> in_progress : start
in_progress --> completed : complete
in_progress --> failed : fail
in_progress --> blocked : dependency blocks
blocked --> in_progress : dependency resolved
pending --> skipped : skip
failed --> pending : retry

completed --> [*]
skipped --> [*]
@enduml
```

# Key Features

## Task Management
- Status lifecycle (pending, in_progress, completed, failed, blocked, skipped)
- Priority-based scheduling
- Dependency tracking

## Knowledge Capture
- Facts: Plan-specific knowledge
- Lessons: Cross-session learnings
- Traces: ReAct reasoning chains

# Data Flow

```{.plantuml}
@startuml
!theme plain
skinparam backgroundColor white

actor "LLM Agent" as Agent
participant "Plan CLI" as CLI
database "SQLite DB" as DB
entity "Markdown Files" as MD

Agent -> CLI: plan import -f plan.md
CLI -> MD: read
MD --> CLI: parsed data
CLI -> DB: upsert plan, tasks, facts

Agent -> CLI: task next --plan-id 1
CLI -> DB: query ready tasks
DB --> CLI: highest priority task
CLI --> Agent: task details

Agent -> CLI: task complete --id 1
CLI -> DB: update status
CLI -> DB: unblock dependents
@enduml
```

# Tech Stack

- **Language**: Clojure 1.12
- **Database**: SQLite with FTS5
- **CLI**: cli-matic
- **SQL**: HoneySQL + next.jdbc
- **Validation**: Malli schemas
- **Error Handling**: Failjure
- **Markdown Parsing**: CommonMark (Java) + clj-yaml
- **Serialization**: Custom YAML/Markdown serializers (v1 flat keys, v3 hierarchical)

# CLI Commands

## Initialization

```bash
plan init                    # Initialize database and config
```

## Plans

```bash
plan plan list               # List all plans
plan plan create --name "New Plan" --description "Details"
plan plan import -f plan.md  # Import from markdown (with upsert)
plan plan export --id 1      # Export to stdout
plan plan export --id 1 -f out.md  # Export to file
plan plan delete --id 1      # Delete plan and all related data
```

### Import/Export Options

```bash
# Preview import without making changes
plan plan import -f plan.md --dry-run

# Export with specific format (default: v3)
plan plan export --id 1 --format v3

# Force overwrite on export
plan plan export --id 1 -f out.md --force
```

## Tasks

```bash
plan task next --plan-id 1           # Get highest priority ready task
plan task list --plan-id 1           # List all tasks for plan
plan task create --plan-id 1 --name "New Task" --priority 10
plan task start --id 1               # Transition to in_progress
plan task complete --id 1            # Mark completed
plan task fail --id 1                # Mark failed
plan task block --id 1               # Mark blocked
plan task skip --id 1                # Mark skipped
plan task unblock --id 1             # Check and unblock if ready
```

## Dependencies

```bash
plan task depend --id 1 --blocks-task-id 2    # Task 1 blocks Task 2
plan task undepend --id 1 --blocks-task-id 2  # Remove dependency
plan task blocking --id 1                      # List what's blocking this task
plan task dependents --id 1                    # List what this blocks
```

## Search

```bash
plan search -q "authentication"      # Search plans, tasks, facts
plan search -q "api" --type plan     # Search only plans
plan search -q "urgent" --type task  # Search only tasks
```

## Working with Markdown Files

```bash
# Typical workflow: export, edit, import
plan plan export --id 1 -f plan.md   # Export current state
$EDITOR plan.md                       # Edit tasks, add dependencies
plan plan import -f plan.md          # Re-import with changes

# Collaborative workflow
plan plan export --id 1 -f shared.md
# ... share file with team ...
plan plan import -f shared.md --dry-run   # Preview changes
plan plan import -f shared.md             # Apply changes
```

# Markdown Serialization Format

Plan uses **YAML front matter embedded in Markdown** for portable, human-readable plan serialization.

## Two Serializer Implementations

| Feature | markdown.clj (v1) | markdown_v2.clj (v3) |
|---------|-------------------|----------------------|
| **Structure** | Flat key-value pairs | Hierarchical nested YAML |
| **IDs in output** | Database IDs included | Names as identifiers only |
| **Dependencies** | Not supported | Full dependency support |
| **Task status** | completed boolean | Full status lifecycle |
| **Priority** | Not supported | Supported |
| **Use case** | Legacy/compatibility | Modern, recommended |

## Format v3 Specification (markdown_v2.clj)

### Complete Example

```markdown
---
format_version: 3
description: Launch our new SaaS product
completed: false
tasks:
- name: Design marketing website
  description: Create landing page with product features
  status: completed
  priority: 10
  acceptance_criteria: |
    - Hero section renders correctly
    - Mobile responsive
    - Contact form validates input
  content: |
    # Website Requirements

    ## Must Have
    - Hero section with video
    - Feature grid
    - Pricing table

    ## Nice to Have
    - Blog section

- name: Write copy for website
  description: All marketing text and messaging
  status: in_progress
  priority: 20
  blocked_by:
    - Design marketing website

- name: Setup analytics
  description: Configure Google Analytics
  status: pending
  priority: 30
  blocked_by:
    - Design marketing website
  blocks:
    - Launch campaign

facts:
- name: Target Launch Date
  description: When we want to go live
  content: March 15, 2024

- name: Marketing Budget
  description: Total spend allocated
  content: $15,000 USD
---

# Product Launch 2024

## Overview
This plan tracks all activities for our Q1 2024 product launch.

## Strategy
Our go-to-market strategy focuses on content marketing and social media.
```

### YAML Front Matter Schema

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `format_version` | integer | Yes | Version identifier (3) |
| `description` | string | No | Plan description |
| `completed` | boolean | No | Overall completion status |
| `tasks` | array | No | List of task objects |
| `facts` | array | No | List of fact objects |

### Task Object Schema

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Unique identifier (required) |
| `description` | string | Brief task description |
| `content` | string | Detailed content (multiline supported) |
| `status` | enum | `pending`, `in_progress`, `completed`, `failed`, `blocked`, `skipped` |
| `priority` | integer | Lower = higher priority (default: 100) |
| `acceptance_criteria` | string | Criteria for completion |
| `completed` | boolean | Backwards compatibility |
| `blocked_by` | array | List of task names that block this task |
| `blocks` | array | List of task names this task blocks |

### Fact Object Schema

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Unique identifier (required) |
| `description` | string | Brief fact description |
| `content` | string | Detailed content (multiline supported) |

## Plan Name from H1 Header

The plan name is extracted from the first H1 header in the markdown body:

```markdown
---
format_version: 3
---

# This Becomes the Plan Name

Content follows...
```

The H1 header is automatically added during export if not present.

# Serialization/Deserialization API

## Serializer Functions (markdown_v2.clj)

### Core Functions

```clojure
;; Serialize plan to markdown string
(md-v2/plan->markdown plan tasks facts)
(md-v2/plan->markdown plan tasks facts dependencies)

;; Deserialize markdown to plan data
(md-v2/markdown->plan markdown-string)
; => {:plan {...} :tasks [...] :facts [...] :dependencies [...] :format_version 3}

;; File I/O
(md-v2/write-plan-to-file "plan.md" plan tasks facts)
(md-v2/read-plan-from-file "plan.md")

;; Validation
(md-v2/valid-plan-markdown? markdown-string)  ; => true/false

;; Metadata helpers
(md-v2/get-plan-metadata markdown-string)  ; Extract without parsing all tasks
(md-v2/count-tasks markdown-string)        ; Quick task count
(md-v2/count-facts markdown-string)        ; Quick fact count
```

### Dependency Map Structure

Dependencies are passed separately from tasks:

```clojure
{task-id {:blocked-by [blocking-task-ids]
          :blocks [blocked-task-ids]}}
```

During serialization, task IDs are resolved to names for portability.

## Import/Export Flow

### Export Process

1. Fetch plan, tasks, and facts from database
2. Resolve task IDs to build dependency map
3. Convert all IDs to names for portability
4. Generate YAML front matter with clj-yaml
5. Add H1 header with plan name if needed
6. Write to markdown file

### Import Process (with Upsert Semantics)

1. Parse YAML front matter from markdown
2. Extract H1 header as plan name
3. Normalize tasks (v2/v3 compatibility)
4. Build dependency list from `blocked_by`/`blocks`
5. **BEGIN TRANSACTION**
6. Upsert plan by name (insert or update)
7. Upsert each task by (plan_id, name)
8. Delete orphaned tasks (not in import)
9. Upsert each fact by (plan_id, name)
10. Delete orphaned facts (not in import)
11. Clear existing dependencies for plan
12. Recreate dependencies from import data
13. **COMMIT**
14. Return import statistics

### Import API

```clojure
;; Import from parsed data
(import/import-plan conn
  {:plan {:name "Project" ...}
   :tasks [{:name "Task 1" ...} ...]
   :facts [{:name "Fact 1" ...} ...]
   :dependencies [{:from "Task 1" :to "Task 2"} ...]})
; => {:id 1 :name "Project" :tasks-imported 3 ...}

;; Import from file
(import/import-from-file conn "plan.md")

;; Import from string
(import/import-from-string conn markdown-string)

;; Preview without importing
(import/preview-import conn data)
; => {:plan-name "Project" :plan-exists? false
;     :tasks {:create 2 :update 1 :delete 0}
;     :facts {:create 1 :update 0 :delete 1}}
```

### Upsert Semantics

Import uses **name-based identifiers** for idempotent imports:

| Entity | Identifier | Behavior on Conflict |
|--------|-----------|---------------------|
| Plan | `name` | Update description, content, completed |
| Task | `plan_id` + `name` | Update all fields except IDs |
| Fact | `plan_id` + `name` | Update all fields except IDs |
| Dependencies | `task_id` + `blocks_task_id` | Recreated from import data |

**Orphan Deletion**: Tasks/facts in the database but not in the import are deleted.

## Task Dependencies

### Dependency Types

Dependencies are uni-directional blocking relationships:

- `blocked_by`: Tasks that must complete before this task can start
- `blocks`: Tasks that cannot start until this task completes

### Example with Dependencies

```yaml
tasks:
- name: Design API
  status: completed
- name: Implement API
  status: blocked
  blocked_by:
    - Design API
- name: Write Tests
  status: blocked
  blocked_by:
    - Design API
- name: Deploy
  status: pending
  blocked_by:
    - Implement API
    - Write Tests
```

### Dependency Resolution During Import

1. All tasks are upserted first
2. All existing dependencies for the plan are cleared
3. Dependencies are recreated by resolving task names to IDs
4. Invalid dependencies (missing tasks) are silently skipped

## Format Backwards Compatibility

### v2 to v3 Migration

| v2 Field | v3 Equivalent | Migration |
|----------|---------------|-----------|
| `completed: true` | `status: completed` | Automatic |
| `completed: false` | `status: pending` | Automatic |
| No priority field | `priority: 100` | Uses default |
| No dependencies | `blocked_by: []` | Empty list |

The serializer automatically normalizes tasks:

```clojure
;; v2 format input
{:name "Task" :completed true}

;; Normalized to v3
{:name "Task" :status "completed" :priority 100 :completed true}
```

# Practical Examples

## Example 1: Simple Project Plan

```markdown
---
format_version: 3
description: Personal blog redesign
completed: false
tasks:
- name: Choose static site generator
  status: completed
  priority: 10
- name: Design new layout
  status: in_progress
  priority: 20
  blocked_by:
    - Choose static site generator
- name: Migrate content
  status: pending
  priority: 30
  blocked_by:
    - Design new layout
- name: Setup deployment
  status: pending
  priority: 40
- name: Launch site
  status: pending
  priority: 50
  blocked_by:
    - Migrate content
    - Setup deployment
facts:
- name: Current Platform
  content: WordPress on shared hosting
- name: Target Platform
  content: Hugo on Netlify
- name: Deadline
  content: End of March 2024
---

# Blog Redesign

Migrating from WordPress to a static site generator for better performance
and lower hosting costs.

## Goals

1. Improve page load speed
2. Reduce hosting costs
3. Simplify content management
4. Add dark mode support
```

## Example 2: Software Feature with Complex Dependencies

```markdown
---
format_version: 3
description: Implement user authentication system
tasks:
- name: Design auth flow
  status: completed
  priority: 10
  acceptance_criteria: |
    - Login/register mockups approved
    - Session handling documented
    - Security review passed

- name: Create database schema
  status: completed
  priority: 20
  blocked_by:
    - Design auth flow

- name: Implement password hashing
  status: in_progress
  priority: 30
  blocked_by:
    - Create database schema

- name: Build login API
  status: pending
  priority: 40
  blocked_by:
    - Implement password hashing

- name: Build registration API
  status: pending
  priority: 40
  blocked_by:
    - Implement password hashing

- name: Create login UI
  status: pending
  priority: 50
  blocked_by:
    - Build login API

- name: Create registration UI
  status: pending
  priority: 50
  blocked_by:
    - Build registration API

- name: Add password reset
  status: pending
  priority: 60
  blocked_by:
    - Build login API

- name: Integration tests
  status: pending
  priority: 70
  blocked_by:
    - Create login UI
    - Create registration UI
    - Add password reset

facts:
- name: Security Requirements
  content: |
    - Argon2id for password hashing
    - JWT with 24h expiration
    - Rate limiting: 5 attempts per IP per minute
    - Require email verification

- name: OAuth Providers
  content: Google, GitHub, and Apple sign-in required

- name: Compliance
  content: Must be GDPR compliant with data export/deletion
---

# User Authentication System

Implementing secure user authentication with email/password and OAuth options.

## Technical Notes

Using Clojure with Buddy for crypto operations. Frontend will be React
with form validation via react-hook-form.
```

## Example 3: Research Project with Facts

```markdown
---
format_version: 3
description: Evaluate machine learning approaches
tasks:
- name: Literature review
  status: in_progress
  priority: 10
  content: |
    ## Key Papers to Review

    1. "Attention Is All You Need" - Vaswani et al.
    2. "BERT: Pre-training of Deep Bidirectional Transformers"
    3. "Language Models are Few-Shot Learners" (GPT-3)

- name: Benchmark existing models
  status: pending
  priority: 20
  blocked_by:
    - Literature review
  acceptance_criteria: |
    - Evaluate on 3 datasets
    - Measure inference time
    - Document memory requirements

- name: Prototype custom model
  status: pending
  priority: 30
  blocked_by:
    - Benchmark existing models

- name: Write evaluation report
  status: pending
  priority: 40
  blocked_by:
    - Prototype custom model

facts:
- name: Available Compute
  description: GPU resources for training
  content: |
    - 4x NVIDIA A100 (40GB each)
    - Training budget: 1000 GPU hours
    - Inference target: <100ms per request

- name: Baseline Performance
  description: Current system metrics
  content: |
    - F1 score: 0.78
    - Throughput: 50 req/sec
    - Must improve F1 by at least 5%

- name: Data Availability
  description: Training and evaluation datasets
  content: |
    - Labeled data: 50k samples
    - Unlabeled data: 2M samples
    - Test set: 5k samples (held out)
---

# ML Approach Evaluation

Research project to identify the best architecture for our classification
problem.

## Hypothesis

Transformer-based models will outperform our current CNN approach due to
the sequential nature of the input data.
```

# Summary

**Plan** enables LLM agents to:

1. **Persist** state across sessions via SQLite with FTS5
2. **Track** task dependencies and priorities with full lifecycle support
3. **Import/Export** plans as human-readable Markdown with YAML front matter
4. **Learn** from successes and failures (Reflexion pattern)
5. **Reason** transparently with traces (ReAct pattern)

## Key Capabilities

| Capability | Implementation |
|------------|----------------|
| **Portability** | YAML/Markdown serialization with name-based identifiers |
| **Idempotency** | Upsert-based imports (create or update by name) |
| **Dependencies** | Task graph with automatic blocked/ready detection |
| **Versioning** | Format v3 with backwards compatibility |
| **Validation** | Malli schemas for all data structures |
| **Preview** | Dry-run mode shows changes before import |

## Serialization Highlights

- **Two serializers**: Flat keys (v1) and hierarchical YAML (v3)
- **Human-editable**: Markdown files can be edited in any text editor
- **Dependency tracking**: Specify `blocked_by` and `blocks` in YAML
- **Round-trip safe**: Export to Edit to Import preserves all data
- **Collaboration-friendly**: Share markdown files, merge changes via import

GitHub: github.com/iwillig/plan
