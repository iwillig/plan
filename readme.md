# Plan

A planning tool designed for LLM agents to maintain persistent state, track progress, learn from experience, and improve over time.

## Why Plan?

LLM agents face fundamental limitations that external tooling can solve:

| Problem | Impact | How Plan Helps |
|---------|--------|----------------|
| **Context window limits** | Lose track of what's done vs pending | Persistent task state with status lifecycle |
| **No cross-session memory** | Repeat the same mistakes | Lessons capture learnings with confidence scores |
| **Black-box reasoning** | Can't debug or improve decisions | ReAct traces externalize thinking |
| **No task sequencing** | Work on blocked tasks, miss dependencies | Priority + dependency management |
| **Session boundaries** | Work lost between conversations | Markdown export/import for continuity |

## Quick Start

```bash
# Initialize the database
plan init

# Create a new plan from template
plan new -n "my-feature" -f my-feature.md

# Edit the markdown to add tasks, then import
plan import -f my-feature.md

# Get the next task to work on
plan task next --plan-id 1

# Start working on it
plan task start --id 1

# Record your reasoning (ReAct pattern)
plan trace add --task-id 1 --type thought -c "Analyzing the requirements..."
plan trace add --task-id 1 --type action -c "Creating database migration"
plan trace add --task-id 1 --type observation -c "Migration successful"

# Mark complete when done
plan task complete --id 1

# Capture what you learned (Reflexion pattern)
plan lesson add --type technique -c "Always run migrations in a transaction"

# Export state for next session
plan export --id 1 -f my-feature.md
```

## Core Features

### Task Status Lifecycle

Tasks progress through states: `pending` → `in_progress` → `completed` | `failed` | `blocked` | `skipped`

```bash
plan task start --id 1      # Begin work
plan task complete --id 1   # Success
plan task fail --id 1       # Failed
```

### Dependencies & Priority

Tasks can block other tasks. Lower priority numbers = higher priority.

```yaml
tasks:
- name: Create schema
  priority: 10
- name: Implement API
  priority: 20
  blocked_by:
    - Create schema
```

```bash
plan task depends --id 2 --on 1   # API blocked by schema
plan task ready --plan-id 1       # List unblocked tasks
plan task next --plan-id 1        # Get highest priority ready task
```

### ReAct Reasoning Traces

Externalize your thinking for debugging and improvement:

```bash
plan trace add --task-id 1 --type thought -c "Need to check auth patterns first"
plan trace add --task-id 1 --type action -c "Searching codebase for JWT usage"
plan trace add --task-id 1 --type observation -c "Found helper in src/utils/jwt.js"
plan trace add --task-id 1 --type reflection -c "Should reuse existing patterns"
```

### Reflexion Learning

Capture lessons that persist across sessions:

```bash
# After discovering something useful
plan lesson add --type success_pattern -c "Batch inserts 10x faster than individual"

# After a failure
plan lesson add --type failure_pattern --trigger "CSV parsing" \
  -c "External CSVs often have mixed encodings. Use chardet to detect."

# Query lessons before starting similar work
plan lesson search -q "database migration"
```

### Facts

Plan-specific knowledge:

```bash
plan fact create --plan-id 1 -n "API Rate Limit" \
  -c "External API limited to 100 req/min. Must implement backoff."
```

## Markdown Format (v3)

Plans are human-readable markdown with YAML front matter:

```yaml
---
format_version: 3
description: Add user authentication
completed: false
tasks:
- name: Create users table
  status: completed
  priority: 10
- name: Add login endpoint
  status: in_progress
  priority: 20
  blocked_by:
    - Create users table
  acceptance_criteria: |
    - Returns JWT on success
    - Returns 401 on invalid credentials
facts:
- name: Password Requirements
  content: "Minimum 12 chars, must include number"
---

# Add User Authentication

Context and notes here...
```

## Commands

### Plans
```bash
plan plan list                    # List all plans
plan plan show --id 1             # Show plan with tasks/facts
plan plan export --id 1 -f out.md # Export to markdown
plan plan import -f file.md       # Import (upsert semantics)
plan plan import -f file.md -p    # Preview import first
```

### Tasks
```bash
plan task next --plan-id 1        # Get next task to work on
plan task ready --plan-id 1       # List all ready tasks
plan task start --id 1            # Begin working
plan task complete --id 1         # Mark done
plan task fail --id 1             # Mark failed
plan task depends --id 2 --on 1   # Add dependency
plan task show --id 1             # View with dependencies
```

### Traces
```bash
plan trace add --task-id 1 --type thought -c "..."
plan trace add --task-id 1 --type action -c "..."
plan trace add --task-id 1 --type observation -c "..."
plan trace add --task-id 1 --type reflection -c "..."
plan trace history --task-id 1
```

### Lessons
```bash
plan lesson add --type success_pattern -c "..."
plan lesson add --type failure_pattern --trigger "context" -c "..."
plan lesson search -q "keywords"
plan lesson validate --id 1       # Increase confidence
plan lesson invalidate --id 1     # Decrease confidence
```

### Other
```bash
plan init                         # Initialize database
plan new -n "name" -f file.md     # Create plan template
plan search -q "keyword"          # Search everything
```

## Documentation

- **[LLM Planning Guide](docs/llm-planning-guide.md)** - Complete guide to agent workflows
- **[ReAct Pattern](docs/react-pattern.md)** - Reasoning traces deep dive
- **[Reflexion Pattern](docs/reflexion-pattern.md)** - Learning from experience
- **[Agent Workflow](docs/agent-workflow.md)** - End-to-end worked example
- **[Example Plan](examples/complete-example.md)** - Sample markdown format

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

## Development

See [agents.md](agents.md) for development instructions.

```bash
bb ci        # Run full CI pipeline (format, lint, test)
bb test      # Run tests only
bb nrepl     # Start development REPL
```
