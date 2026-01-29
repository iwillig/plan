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
plan plan create -d "Description" [-c "Content"]  # Create a new plan
plan plan show --id 1                    # Show a specific plan with tasks and facts
plan plan update --id 1 [-d "New desc"] [-c "New content"] [--completed true]  # Update a plan
plan plan delete --id 1                  # Delete a plan (cascades to tasks)

# Task operations
plan task list --plan-id 1               # List tasks for a plan
plan task create --plan-id 1 -d "Task" [-c "Content"] [--parent-id N]  # Create a new task
plan task update --id 1 [-d "New desc"] [-c "Content"] [--completed true] [--plan-id 2] [--parent-id 3]  # Update a task
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
