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

```bash
# Initialize the database
plan init

# Plan operations
plan list                           # List all plans
plan create -d "Description"        # Create a new plan
plan show --id 1                    # Show a specific plan

# Task operations
task list --plan-id 1               # List tasks for a plan
task create --plan-id 1 -d "Task"   # Create a new task

# Search
search -q "query"                   # Search across plans, tasks, and facts
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
