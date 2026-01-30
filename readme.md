# Plan

A planning tool designed for LLM agents to maintain persistent state, track progress, and accumulate knowledge across sessions.

## Why Plan?

LLM agents face fundamental limitations:

| Problem | Impact | How Plan Helps |
|---------|--------|----------------|
| **Context window limits** | Lose track of what's done vs pending | Persistent task state in SQLite |
| **No cross-session memory** | Repeat the same mistakes | Facts capture learned constraints |
| **Session boundaries** | Work lost between conversations | Markdown export/import for continuity |
| **Unstructured thinking** | Lose sight of the big picture | Hierarchical task decomposition |

## Quick Start

```bash
# Initialize the database
plan init

# Create a new plan from template
plan new -n "my-feature" -f my-feature.md

# Edit the markdown file to add tasks and context, then import
plan import -f my-feature.md

# View your plan
plan plan show --id 1

# Mark tasks complete as you work
plan task update --id 1 --completed true

# Capture discoveries that shouldn't be forgotten
plan fact create --plan-id 1 -n "API Limit" -c "Rate limited to 100 req/min"

# Export state for next session
plan export --id 1 -f my-feature.md
```

## Core Concepts

### Plans
Container for related work with a name, description, tasks, and facts.

### Tasks
Completable work items. Keep them atomic and verifiable:
- Good: "Add password hash column to users table"
- Bad: "Build authentication system"

### Facts
**The most powerful feature for LLMs.** Facts capture knowledge discovered during work:

```bash
plan fact create --plan-id 1 -n "Cache Key Bug" \
  -c "Cache key included timestamp, causing duplicate entries. Use content hash only."
```

Create a fact whenever you:
- Discover an unexpected constraint
- Debug something non-obvious
- Learn a codebase convention

## Markdown Format

Plans are stored as markdown with YAML front matter for human and LLM editing:

```markdown
---
description: Add user authentication
completed: false
tasks:
- name: Create users table
  description: Set up database schema
  completed: true
- name: Add login endpoint
  completed: false
facts:
- name: Password Requirements
  content: "Minimum 12 chars, must include number and symbol"
---

# Add User Authentication

Context and notes go here in the markdown body.
```

## Commands

### Plans
```bash
plan plan list                           # List all plans
plan plan create -n "Name" -d "Desc"     # Create plan
plan plan show --id 1                    # Show plan with tasks/facts
plan plan export --id 1 -f file.md       # Export to markdown
plan plan import -f file.md              # Import (upsert semantics)
plan plan import -f file.md -p           # Preview import first
plan plan delete --id 1                  # Delete plan
```

### Tasks
```bash
plan task list --plan-id 1               # List tasks
plan task create --plan-id 1 -n "Name"   # Create task
plan task update --id 1 --completed true # Mark complete
plan task delete --id 1                  # Delete task
```

### Facts
```bash
plan fact create --plan-id 1 -n "Name" -c "Content"
plan fact list --plan-id 1
plan fact delete --id 1
```

### Search
```bash
plan search -q "authentication"          # Full-text search across everything
```

## Data Model

- **Plan names** are globally unique
- **Task names** are unique within a plan
- **Fact names** are unique within a plan
- Import uses **upsert semantics**: matches by name, creates or updates

This enables idempotent round-trips between markdown and database.

## Configuration

Config file: `~/.config/plan/config.json`

```json
{
  "db-path": "~/.local/share/plan/plan.db"
}
```

Override with environment variable:
```bash
PLAN_DB_PATH=/path/to/custom.db plan init
```

## Documentation

- **[LLM Planning Guide](docs/llm-planning-guide.md)** - Best practices for LLM agents
- **[Example Plan](examples/complete-example.md)** - Full markdown format example

## Development

See [agents.md](agents.md) for development instructions.

```bash
bb ci        # Run full CI pipeline (format, lint, test)
bb test      # Run tests
bb nrepl     # Start development REPL
```
