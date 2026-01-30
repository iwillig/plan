# LLM Agent Planning Guide

A guide to effective planning practices for LLM agents using the Plan tool.

## Why External Planning Matters

LLMs face fundamental limitations that external planning tools address:

| Limitation | Problem | How Plan Solves It |
|------------|---------|-------------------|
| **Context window** | Lose track of progress in long sessions | Persistent task state in database |
| **No memory** | Repeat same mistakes across sessions | Facts capture learned constraints |
| **Tunnel vision** | Focus on immediate code, forget big picture | Hierarchical plan structure |
| **Session boundaries** | Work lost between conversations | Markdown export/import for continuity |

## Core Concepts

### Plans

A plan is a container for related work. It has:
- **Name**: Unique identifier (used for import/export matching)
- **Description**: Short summary of the goal
- **Content**: Full markdown body with context, notes, strategy
- **Tasks**: Actionable work items
- **Facts**: Discovered knowledge and constraints

### Tasks

Tasks are completable work items. Good tasks are:
- **Atomic**: One clear action
- **Verifiable**: Can confirm when done
- **Scoped**: Completable in one focused session

| Task Quality | Example | Verdict |
|--------------|---------|---------|
| Too big | "Build authentication system" | Break down |
| Right size | "Add password hash column to users table" | Good |
| Too small | "Import bcrypt library" | Combine with related work |

Tasks support one level of nesting for subtasks (max depth: 2).

### Facts (Critical for LLMs)

Facts are the most underutilized yet powerful feature. They capture knowledge discovered during work that prevents repeated mistakes.

**When to create a fact:**
- Discovered an unexpected API limitation
- Found a non-obvious codebase convention
- Hit an error that took time to debug
- Learned a constraint not in the documentation

**Examples:**

```yaml
facts:
- name: API Rate Limit
  description: External service constraint
  content: "Stripe API limits to 100 req/sec. Must implement exponential backoff."

- name: User ID Type
  description: Schema discovery
  content: "user_id is UUID not integer. Broke 3 tests before finding this."

- name: Test Database
  description: Environment setup
  content: "Tests require POSTGRES_URL env var. CI uses localhost:5433."

- name: CSS Framework
  description: Codebase convention
  content: "Project uses Tailwind. Don't add inline styles or new CSS files."
```

**Rule**: If you spent more than 2 minutes debugging something non-obvious, create a fact.

## Workflow Patterns

### Pattern 1: Research-Plan-Execute

Best for well-defined features or bug fixes.

```bash
# 1. Create plan file from template
plan new -n "add-user-export" -f add-user-export.md

# 2. Edit markdown: add context, break into tasks, note known constraints
# (LLM or human edits the file directly)

# 3. Import to database
plan import -f add-user-export.md

# 4. Work through tasks, marking complete as you go
plan task update --id 1 --completed true
plan task update --id 2 --completed true

# 5. Add facts when you discover constraints
plan fact create --plan-id 1 -n "Export Size Limit" \
  -c "CSV export times out over 10k rows. Need pagination."

# 6. Export updated state for next session
plan export --id 1 -f add-user-export.md
```

### Pattern 2: Iterative Discovery

Best for investigation, debugging, or unclear scope.

```bash
# 1. Start minimal
plan plan create -n "investigate-memory-leak" -d "Users report high memory usage"

# 2. Add facts as you investigate
plan fact create --plan-id 1 -n "Heap Profile" \
  -c "80% of retained memory is in ImageCache. Not being evicted."

plan fact create --plan-id 1 -n "Root Cause" \
  -c "Cache key includes timestamp, so same image cached multiple times."

# 3. Add tasks once approach is clear
plan task create --plan-id 1 -n "Fix cache key to use image hash only"
plan task create --plan-id 1 -n "Add cache size limit with LRU eviction"
plan task create --plan-id 1 -n "Add memory usage monitoring"

# 4. Execute and complete
plan task update --id 1 --completed true
```

### Pattern 3: Multi-Session Handoff

Best for work spanning multiple sessions or agents.

```bash
# Session 1: Setup and partial work
plan plan create -n "api-v2-migration" -d "Migrate from REST to GraphQL"
plan task create --plan-id 1 -n "Define GraphQL schema"
plan task create --plan-id 1 -n "Implement resolvers"
plan task create --plan-id 1 -n "Update client code"
plan task create --plan-id 1 -n "Add integration tests"

# Do some work...
plan task update --id 1 --completed true

# Export state for next session
plan export --id 1 -f api-v2-migration.md

# Session 2: Resume work
plan import -f api-v2-migration.md  # Sync any manual edits
plan plan show --id 1               # Review current state

# Continue working...
plan task update --id 2 --completed true

# Add discovered fact
plan fact create --plan-id 1 -n "Resolver Pattern" \
  -c "Use dataloader for N+1 prevention. See src/graphql/loaders.js for pattern."
```

## Task Decomposition Guidelines

### Signs a Task Needs Breaking Down

- Contains "and" (do X and Y)
- Estimated at more than 2 hours of work
- Has unclear completion criteria
- Touches more than 3 files
- Requires multiple distinct skills

### Decomposition Example

**Before:**
```yaml
tasks:
- name: Add user authentication
  completed: false
```

**After:**
```yaml
tasks:
- name: Add user authentication
  completed: false
  tasks:
  - name: Create users table with email and password_hash
    completed: false
  - name: Add POST /api/register endpoint
    completed: false
  - name: Add POST /api/login endpoint with JWT
    completed: false
  - name: Add auth middleware for protected routes
    completed: false
  - name: Add tests for auth flow
    completed: false
```

### Task Naming Conventions

Use action verbs and specific scope:

| Bad | Good |
|-----|------|
| "Authentication" | "Add JWT authentication to API" |
| "Fix bug" | "Fix null pointer in UserService.getById" |
| "Tests" | "Add unit tests for PaymentProcessor" |
| "Refactor" | "Extract email validation to shared util" |

## Markdown Format Best Practices

### Structure Your Plan Content

The markdown body (after front matter) should provide context that helps future sessions understand the work:

```markdown
---
name: Feature Name
description: One-line summary
completed: false
tasks:
  # ... tasks here
facts:
  # ... facts here
---

# Feature Name

## Goal
What are we trying to achieve? What does success look like?

## Context
Why is this work needed? What problem does it solve?

## Approach
High-level strategy. Why this approach over alternatives?

## Open Questions
Things still to figure out (update as resolved).

## Notes
Anything else useful for future sessions.
```

### Keep Facts Actionable

Facts should be immediately useful, not just observations:

| Passive (Less Useful) | Actionable (More Useful) |
|-----------------------|--------------------------|
| "The API is slow" | "API calls over 100ms. Cache responses for 5 min." |
| "Tests are flaky" | "UserTest.login flaky due to timing. Add explicit wait." |
| "Config is complex" | "Config loads from ENV > config.json > defaults. Check in that order." |

## Integration Examples

### Claude Code / Agentic IDE

Add to your system prompt or CLAUDE.md:

```markdown
## Planning Protocol

Before starting any multi-step task:
1. Check for existing plan: `plan search -q "relevant keywords"`
2. Create or resume plan: `plan plan show --id X` or `plan plan create -n "task-name"`

During work:
3. Mark tasks complete as you finish them
4. Create facts for any non-obvious discoveries
5. Add new tasks if scope expands

After completing work:
6. Export plan state: `plan export --id X -f task-name.md`
```

### LangChain / Tool-Using Agents

```python
from langchain.tools import Tool
import subprocess
import json

def run_plan_cmd(args: list[str]) -> str:
    result = subprocess.run(["plan"] + args, capture_output=True, text=True)
    return result.stdout or result.stderr

plan_tools = [
    Tool(
        name="show_plan",
        description="Show current plan state with all tasks and facts",
        func=lambda plan_id: run_plan_cmd(["plan", "show", "--id", plan_id])
    ),
    Tool(
        name="complete_task",
        description="Mark a task as completed. Use after finishing work.",
        func=lambda task_id: run_plan_cmd(["task", "update", "--id", task_id, "--completed", "true"])
    ),
    Tool(
        name="add_fact",
        description="Record a discovered constraint or learning. Use when you find something non-obvious.",
        func=lambda args: run_plan_cmd(["fact", "create", "--plan-id", args["plan_id"],
                                        "-n", args["name"], "-c", args["content"]])
    ),
    Tool(
        name="search_plans",
        description="Search across all plans, tasks, and facts",
        func=lambda query: run_plan_cmd(["search", "-q", query])
    ),
]
```

### Autonomous Agent Loop

```python
def agent_work_loop(plan_id: int):
    while True:
        # 1. Check current state
        state = json.loads(run_plan_cmd(["plan", "show", "--id", str(plan_id)]))

        # 2. Find next incomplete task
        pending = [t for t in state["tasks"] if not t["completed"]]
        if not pending:
            print("All tasks complete!")
            break

        task = pending[0]

        # 3. Execute task (your agent logic here)
        result = execute_task(task)

        # 4. Record any discoveries
        if result.get("discoveries"):
            for fact in result["discoveries"]:
                run_plan_cmd(["fact", "create", "--plan-id", str(plan_id),
                             "-n", fact["name"], "-c", fact["content"]])

        # 5. Mark complete if successful
        if result["success"]:
            run_plan_cmd(["task", "update", "--id", str(task["id"]), "--completed", "true"])

        # 6. Export state for recovery
        run_plan_cmd(["plan", "export", "--id", str(plan_id), "-f", f"plan-{plan_id}.md"])
```

## Anti-Patterns to Avoid

| Anti-Pattern | Why It's Bad | Solution |
|--------------|--------------|----------|
| **One giant task** | No progress visibility, overwhelming | Break into 3-7 subtasks |
| **No facts** | Repeat same debugging, waste time | Capture constraints immediately |
| **Stale exports** | Markdown and DB out of sync | Export after each session |
| **Vague task names** | Can't verify completion | Action verb + specific scope |
| **Orphaned plans** | Database clutter | Delete or complete old plans |
| **Tasks as notes** | Mixing concerns | Use content field for notes, tasks for actions |
| **Skipping preview** | Destructive imports | Always `plan import -f file.md -p` first |

## Quick Reference

```bash
# Initialize (first time only)
plan init

# Create new plan from template
plan new -n "my-feature" -f my-feature.md

# Import plan from markdown
plan import -f my-feature.md -p    # Preview first
plan import -f my-feature.md       # Then import

# View plan state
plan plan show --id 1
plan plan list

# Work with tasks
plan task create --plan-id 1 -n "Task name" -d "Description"
plan task update --id 1 --completed true
plan task list --plan-id 1

# Capture knowledge
plan fact create --plan-id 1 -n "Fact name" -c "What you learned"

# Search everything
plan search -q "authentication"

# Export for next session
plan export --id 1 -f my-feature.md
```
