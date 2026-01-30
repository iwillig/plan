# LLM Agent Planning Guide

A comprehensive guide to using the Plan tool for LLM agent workflows. This guide covers task management, reasoning traces (ReAct), and learning from experience (Reflexion).

## Quick Navigation

| Guide | Purpose |
|-------|---------|
| **This document** | Overview and quick reference |
| [ReAct Pattern](react-pattern.md) | Detailed guide to reasoning traces |
| [Reflexion Pattern](reflexion-pattern.md) | Detailed guide to learning from experience |
| [Agent Workflow](agent-workflow.md) | Complete workflow with examples |

## Why External Planning for LLM Agents?

| Limitation | Problem | How Plan Solves It |
|------------|---------|-------------------|
| **Context window** | Lose track of progress | Persistent task state in SQLite |
| **No memory** | Repeat same mistakes | Lessons capture learnings across sessions |
| **Session boundaries** | Work lost between chats | Markdown export/import for continuity |
| **Black-box reasoning** | Can't debug decisions | ReAct traces externalize thinking |
| **No learning** | Don't improve over time | Reflexion with confidence scoring |

## Core Concepts

### Task Status Lifecycle

```
                    ┌─────────┐
                    │ blocked │◀──────────┐
                    └────┬────┘           │
                         │ unblock        │ blocker found
                         ▼                │
┌─────────┐ start  ┌────────────┐        │
│ pending │───────▶│ in_progress│────────┤
└─────────┘        └─────┬──────┘        │
                         │               │
              ┌──────────┼──────────┐    │
              │          │          │    │
              ▼          ▼          ▼    │
         ┌─────────┐ ┌──────┐ ┌─────────┐
         │completed│ │failed│ │ skipped │
         └─────────┘ └──────┘ └─────────┘
```

### Task Dependencies

Tasks can block other tasks. A blocked task won't appear in `task ready` or `task next` until its blockers complete.

```yaml
tasks:
- name: Create database schema
  status: pending
  priority: 10

- name: Implement API endpoints
  status: pending
  priority: 20
  blocked_by:
    - Create database schema  # Can't start until schema exists
```

### Task Priority

Lower numbers = higher priority. `task next` returns the highest-priority ready task.

```bash
plan task next --plan-id 1
# Returns: lowest priority number among unblocked pending tasks
```

### Reasoning Traces (ReAct)

Externalize your thinking process:

| Type | When to Use |
|------|-------------|
| `thought` | Reasoning about what to do |
| `action` | What you're doing |
| `observation` | What you see/learn |
| `reflection` | Meta-analysis of approach |

```bash
plan trace add --task-id 1 --type thought -c "Need to check existing auth patterns"
plan trace add --task-id 1 --type action -c "Searching: grep -r 'authenticate' src/"
plan trace add --task-id 1 --type observation -c "Found JWT helper in src/utils/jwt.js"
```

### Lessons (Reflexion)

Capture learnings for future use:

| Type | When to Use |
|------|-------------|
| `success_pattern` | Something that worked well |
| `failure_pattern` | Something that failed (and why) |
| `constraint` | Discovered limitation |
| `technique` | Useful approach to remember |

```bash
plan lesson add --type constraint --trigger "S3 uploads" \
  -c "Files over 5GB require multipart upload. Single PUT fails silently."
```

### Facts

Plan-specific knowledge that informs tasks:

```bash
plan fact create --plan-id 1 -n "API Rate Limit" \
  -c "External API limited to 100 req/min. Implement backoff."
```

## The Agent Work Loop

```bash
# 1. Get next task
plan task next --plan-id 1

# 2. Check for relevant lessons
plan lesson search -q "relevant keywords"

# 3. Start the task
plan task start --id N

# 4. Work with ReAct traces
plan trace add --task-id N --type thought -c "..."
plan trace add --task-id N --type action -c "..."
plan trace add --task-id N --type observation -c "..."

# 5. Complete or fail
plan task complete --id N   # or: plan task fail --id N

# 6. Reflect and learn
plan trace add --task-id N --type reflection -c "..."
plan lesson add --type success_pattern -c "..."

# 7. Repeat
```

## Command Reference

### Task Commands

```bash
# Lifecycle
plan task next --plan-id 1              # Get highest priority ready task
plan task ready --plan-id 1             # List all ready tasks
plan task start --id N                  # pending → in_progress
plan task complete --id N               # → completed
plan task fail --id N                   # → failed
plan task update --id N --status blocked

# Dependencies
plan task depends --id 2 --on 1         # Task 2 blocked by task 1
plan task show --id N                   # View task with dependencies

# CRUD
plan task list --plan-id 1
plan task create --plan-id 1 -n "Name" -d "Description" --priority 10
plan task update --id N --name "New name"
plan task delete --id N
```

### Trace Commands

```bash
plan trace add --task-id N --type thought -c "content"
plan trace add --task-id N --type action -c "content"
plan trace add --task-id N --type observation -c "content"
plan trace add --task-id N --type reflection -c "content"
plan trace history --task-id N
```

### Lesson Commands

```bash
plan lesson add --type success_pattern -c "content"
plan lesson add --type failure_pattern --trigger "context" -c "content"
plan lesson add --type constraint --task-id N -c "content"
plan lesson add --type technique --plan-id N -c "content"
plan lesson search -q "keywords"
plan lesson list --min-confidence 0.7
plan lesson validate --id N             # Increase confidence
plan lesson invalidate --id N           # Decrease confidence
plan lesson delete --id N
```

### Plan Commands

```bash
plan plan list
plan plan create -n "Name" -d "Description"
plan plan show --id N
plan plan export --id N -f file.md
plan plan import -f file.md
plan plan import -f file.md -p          # Preview first
plan plan delete --id N
```

### Other Commands

```bash
plan init                               # Initialize database
plan new -n "name" -f file.md           # Create plan template
plan search -q "keyword"                # Search everything
plan config                             # Show configuration
```

## Markdown Format (v3)

```yaml
---
format_version: 3
description: Plan description
completed: false
tasks:
- name: First task
  status: pending
  priority: 10
  acceptance_criteria: |
    - Criterion 1
    - Criterion 2
- name: Second task
  status: pending
  priority: 20
  blocked_by:
    - First task
  blocks:
    - Third task
- name: Third task
  status: blocked
  priority: 30
facts:
- name: Important constraint
  content: Details here
---

# Plan Title

Markdown content goes here...
```

### Status Values

- `pending` - Not started
- `in_progress` - Currently being worked on
- `completed` - Successfully finished
- `failed` - Attempted but failed
- `blocked` - Waiting on dependencies
- `skipped` - Intentionally not doing

### Import/Export

```bash
# Export preserves all v3 fields
plan export --id 1 -f my-plan.md

# Import handles v2 and v3 formats
plan import -f my-plan.md -p    # Preview changes
plan import -f my-plan.md       # Apply changes
```

## Best Practices

### Task Design

| Aspect | Good | Bad |
|--------|------|-----|
| Size | "Add password hash column" | "Build auth system" |
| Naming | "Fix null check in UserService.getById" | "Fix bug" |
| Criteria | Specific, verifiable | Vague |
| Dependencies | Explicit in blocked_by | Assumed |

### Using Priorities

```yaml
# Lower number = higher priority
tasks:
- name: Critical security fix
  priority: 1
- name: Normal feature work
  priority: 50
- name: Nice-to-have improvement
  priority: 100
```

### When to Create Traces

- **thought**: Before any significant action
- **action**: When running commands, making changes
- **observation**: After seeing results, errors, output
- **reflection**: After completing/failing task, at session end

### When to Create Lessons

- Discovered unexpected behavior
- Found a working solution after struggle
- Made a mistake worth remembering
- Learned a codebase pattern

### When to Create Facts

- Plan-specific constraints
- Environment details (API keys, URLs)
- Discovered requirements
- Decisions made and rationale

## Integration Examples

### Claude Code / CLAUDE.md

```markdown
## Planning Protocol

Before multi-step tasks:
1. `plan task next --plan-id N` - Get current task
2. `plan lesson search -q "..."` - Check for relevant lessons

During work:
3. Record traces with `plan trace add`
4. Create facts for discoveries

After completion:
5. `plan task complete --id N`
6. `plan lesson add` for reusable learnings
7. `plan export` at session end
```

### Autonomous Agent

```python
def agent_loop(plan_id):
    while task := get_next_task(plan_id):
        lessons = search_lessons(task.description)
        start_task(task.id)

        try:
            result = execute_with_traces(task, lessons)
            complete_task(task.id)
            capture_success_lessons(task, result)
            validate_helpful_lessons(lessons)
        except Exception as e:
            fail_task(task.id)
            capture_failure_lesson(task, e)

        export_plan(plan_id)
```

## Further Reading

- [ReAct Pattern](react-pattern.md) - Deep dive on reasoning traces
- [Reflexion Pattern](reflexion-pattern.md) - Deep dive on learning
- [Agent Workflow](agent-workflow.md) - Complete worked example
- [Example Plan](../examples/complete-example.md) - Sample markdown format
