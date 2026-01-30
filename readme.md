# Plan

A planning tool for LLM Agents

## Configuration

Configuration is loaded from `~/.config/plan/config.json`.

Default configuration:
```json
{
  "db-path": "~/.local/share/plan/plan.db"
}
```

The database path can be overridden with the `PLAN_DB_PATH` environment variable:
```bash
PLAN_DB_PATH=/path/to/custom.db plan init
```

## Commands

All commands output pretty-printed JSON/EDN data structures.

```bash
# Initialize the database
plan init

# Plan operations
plan plan list                                          # List all plans
plan plan create -n "My Plan"                           # Create plan (name required)
plan plan create -n "My Plan" -d "Description" -c "Content"  # Create with all fields
plan plan show --id 1                                   # Show plan with tasks and facts
plan plan update --id 1 -d "New desc" --completed true  # Update a plan
plan plan delete --id 1                                 # Delete plan (cascades to tasks and facts)
plan plan export --id 1                                 # Export to markdown (uses plan name as filename)
plan plan export --id 1 -f "custom.md"                  # Export to specific file
plan plan import -f "file.md"                           # Import plan from markdown (upsert)
plan plan import -f "file.md" -p                        # Preview import without changes

# Task operations
plan task list --plan-id 1                              # List tasks for a plan
plan task create --plan-id 1 -n "My Task"               # Create task (name required)
plan task create --plan-id 1 -n "My Task" -d "Desc" -c "Content"  # Create with all fields
plan task create --plan-id 1 -n "Subtask" --parent-id 1 # Create subtask
plan task update --id 1 -d "New desc" --completed true  # Update a task
plan task delete --id 1                                 # Delete a task

# Search (uses FTS5 for full-text search with prefix matching)
plan search -q "query"                                  # Search across plans, tasks, and facts
```

### Example Usage

```bash
# Initialize the database
plan init

# Create some plans
plan plan create -n "Build CLI Tool" -d "Build a CLI tool" -c "Using Clojure and GraalVM"
plan plan create -n "Documentation" -d "Write documentation"

# List all plans
plan plan list

# Show plan details with tasks and facts
plan plan show --id 1

# Add tasks to a plan
plan task create --plan-id 1 -n "Setup project" -d "Set up project structure"
plan task create --plan-id 1 -n "Core features" -d "Implement core features" -c "Include tests"

# List tasks for a plan
plan task list --plan-id 1

# Mark a task as completed
plan task update --id 1 --completed true

# Update plan content
plan plan update --id 1 -c "Updated content with **markdown**"

# Move task to different plan
plan task update --id 2 --plan-id 3

# Delete a task
plan task delete --id 5

# Delete a plan (cascades to all tasks and facts)
plan plan delete --id 2

# Search across all content
plan search -q "CLI"
plan search -q "core features"

# Export a plan to markdown
plan plan export --id 1 -f "my-plan.md"

# Import a plan from markdown (preview first)
plan plan import -f "my-plan.md" -p

# Import for real
plan plan import -f "my-plan.md"

# Full round-trip workflow
plan plan export --id 1 -f "backup.md"
plan plan delete --id 1
plan plan import -f "backup.md"
```

## Data Model

The planning tool is focused on plans. Plans are made up of
completable tasks. Tasks can have a parent task (max depth: 2).

Names serve as unique identifiers for import/export:
- Plan names are globally unique
- Task names are unique within a plan
- Fact names are unique within a plan

### Indexes

- idx_tasks_plan_id: index on tasks(plan_id)
- idx_tasks_parent_id: index on tasks(parent_id)
- idx_facts_plan_id: index on facts(plan_id)

### Full-Text Search

FTS5 virtual tables provide full-text search on description and content:

- plans_fts: FTS5 index for plans
- tasks_fts: FTS5 index for tasks
- facts_fts: FTS5 index for facts

Triggers keep the FTS indexes synchronized with the main tables.

### Plan

- id: integer (primary key, autoincrement)
- name: string (unique, required)
- description: string (short summary)
- content: string (full text, searchable)
- completed: boolean (not null, default: false)
- created_at: timestamp (default: current_timestamp)
- updated_at: timestamp
- tasks: many reference to Task
- facts: many reference to Fact

### Task

- id: integer (primary key, autoincrement)
- plan_id: integer (not null, foreign key to Plan)
- name: string (unique within plan, required)
- description: string (short summary)
- content: string (full text, searchable)
- completed: boolean (not null, default: false)
- parent_id: integer (optional, for subtasks, max depth 2)
- created_at: timestamp (default: current_timestamp)
- updated_at: timestamp

### Fact

Facts are little bits of knowledge that LLM and humans discover while
working on a plan. They are designed to prevent the LLM or human from
making the same mistake twice.

- id: integer (primary key, autoincrement)
- plan_id: integer (not null, foreign key to Plan)
- name: string (unique within plan, required)
- description: string (short summary)
- content: string (full text, searchable)
- created_at: timestamp (default: current_timestamp)
- updated_at: timestamp

## Markdown Export/Import Format

Plans can be exported to and imported from markdown files with YAML front matter.
This format is compatible with CommonMark parsers and works with GraalVM native-image.

### Example Markdown File

```markdown
---
description: Add login/logout functionality
completed: false
tasks:
- name: Create users table
  description: Set up database schema
  content: |
    Create the users table with email, password_hash, created_at columns.
  completed: true
- name: Implement login endpoint
  description: POST /api/login
  completed: false
facts:
- name: Security Requirements
  description: OAuth2 and JWT requirements
  content: |
    - Use OAuth2 for third-party auth
    - JWT tokens with 24h expiry
    - Refresh token rotation
---

# Implement User Authentication

This plan covers the implementation of user authentication
using OAuth2 and JWT tokens.
```

### Format Notes

- Uses hierarchical YAML with nested lists for tasks and facts
- Plan name comes from the first H1 heading in the body (`# Plan Name`)
- Plan description and completed status are in the front matter
- Tasks and facts are nested lists with `name`, `description`, `content`, and `completed` fields
- Multiline content uses YAML literal block syntax (`|`)
- The markdown body after the front matter becomes the plan's content field
- Import uses upsert semantics: existing plans are updated, tasks/facts matched by name
- Use `-p` flag to preview import changes before applying
