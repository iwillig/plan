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
plan plan list                           # List all plans
plan plan create -d "Description" -c "Content"  # Create a new plan
plan plan show --id 1                    # Show a specific plan with tasks and facts
plan plan update --id 1 -d "New desc" -c "New content" --completed true  # Update a plan
plan plan delete --id 1                  # Delete a plan (cascades to tasks)
plan plan export --id 1 -f "file.md"     # Export plan to markdown file
plan plan import -f "file.md"            # Import plan from markdown file

# Task operations
plan task list --plan-id 1               # List tasks for a plan
plan task create --plan-id 1 -d "Task" -c "Content" --parent-id N  # Create a new task
plan task update --id 1 -d "New desc" -c "Content" --completed true --plan-id 2 --parent-id 3  # Update a task
plan task delete --id 1                  # Delete a task

# Search (uses FTS5 for full-text search with prefix matching)
plan search -q "query"                   # Search across plans, tasks, and facts
```

### Example Usage

```bash
# Initialize the database
plan init

# Create some plans
plan plan create -d "Build a CLI tool" -c "Using Clojure and GraalVM"
plan plan create -d "Write documentation"

# List all plans
plan plan list

# Show plan details with tasks and facts
plan plan show --id 1

# Add tasks to a plan
plan task create --plan-id 1 -d "Set up project structure"
plan task create --plan-id 1 -d "Implement core features" -c "Include tests"

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

# Delete a plan (cascades to all tasks)
plan plan delete --id 2

# Search across all content
plan search -q "CLI"
plan search -q "implement features"

# Export a plan to markdown
plan plan export --id 1 -f "my-plan.md"

# Import a plan from markdown
plan plan import -f "my-plan.md"

# Full round-trip workflow
plan plan export --id 1 -f "backup.md"
plan plan delete --id 1
plan plan import -f "backup.md"
```

## Data Model

The planning tool is focused on plans. Plans are made up of
completable tasks. Tasks can have a parent task (max depth: 2).

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
- completed: boolean (not null, default: false)
- description: string (short summary)
- content: string (full text, searchable)
- created_at: timestamp (default: current_timestamp)
- updated_at: timestamp
- tasks: many reference to Task
- facts: many reference to Fact

### Task

- id: integer (primary key, autoincrement)
- plan_id: integer (not null, foreign key to Plan)
- completed: boolean (not null, default: false)
- description: string (short summary)
- content: string (full text, searchable)
- parent_id: integer (optional, for subtasks, max depth 2)
- created_at: timestamp (default: current_timestamp)
- updated_at: timestamp

### Fact

Facts are little bits of knowledge that LLM and humans discover while
working on a plan. They are designed to prevent the LLM or human from
making the same mistake twice.

- id: integer (primary key, autoincrement)
- plan_id: integer (not null, foreign key to Plan)
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
plan_name: Implement User Authentication
plan_description: Add login/logout functionality
plan_completed: false
task_0_name: Create users table
task_0_description: Set up database schema
task_0_completed: true
task_1_name: Implement login endpoint
task_1_completed: false
fact_0_name: Security Requirements
fact_0_description: OAuth2 and JWT requirements
fact_0_content: |
  - Use OAuth2 for third-party auth
  - JWT tokens with 24h expiry
  - Refresh token rotation
---

# User Authentication Implementation

This plan covers the implementation of user authentication
using OAuth2 and JWT tokens.

## Tasks

- [x] Create users table
- [ ] Implement login endpoint
```

### Format Notes

- Uses flat key-value pairs in YAML front matter (CommonMark compatible)
- Plan fields use `plan_` prefix
- Task fields use `task_N_` prefix (e.g., `task_0_name`, `task_1_name`)
- Fact fields use `fact_N_` prefix (e.g., `fact_0_name`)
- Multiline content uses YAML literal block syntax (`|`)
- Boolean values are normalized ("true"/"false" strings become actual booleans)
- The body content after the front matter is the plan's content field
