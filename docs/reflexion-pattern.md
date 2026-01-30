# Reflexion Pattern for LLM Agents

Reflexion is a learning paradigm where agents reflect on their experiences to improve future performance. Instead of just completing tasks, agents explicitly analyze what worked, what failed, and why—then store these insights for future reference.

## The Reflexion Cycle

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│   ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌─────────┐  │
│   │  ATTEMPT │───▶│  RESULT  │───▶│ EVALUATE │───▶│  LEARN  │  │
│   └──────────┘    └──────────┘    └──────────┘    └─────────┘  │
│        ▲                                               │        │
│        │                                               │        │
│        │         ┌─────────────────┐                   │        │
│        └─────────│ LESSON MEMORY   │◀──────────────────┘        │
│                  │ (persisted)     │                            │
│                  └─────────────────┘                            │
│                          │                                      │
│                          ▼                                      │
│                  ┌─────────────────┐                            │
│                  │  FUTURE TASKS   │                            │
│                  │ (informed by    │                            │
│                  │  past lessons)  │                            │
│                  └─────────────────┘                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Why Reflexion Matters

### The Problem: Repeating Mistakes

LLM agents without memory:
- Make the same errors across sessions
- Don't learn from failures
- Can't transfer knowledge between similar tasks
- Waste time rediscovering constraints

### The Solution: Structured Learning

Reflexion enables agents to:
- Capture lessons from both successes and failures
- Build a searchable knowledge base
- Apply past learnings to new situations
- Improve over time

## Lesson Types

| Type | When to Use | Example |
|------|-------------|---------|
| **success_pattern** | Something worked well | "Using batch inserts instead of individual saves reduced import time by 90%" |
| **failure_pattern** | Something failed and why | "Regex-based email validation missed edge cases. Use dedicated library instead." |
| **constraint** | Discovered limitation | "API rate limited to 100 req/min. Must implement backoff." |
| **technique** | Useful approach | "For large file processing, stream line-by-line instead of loading into memory." |

## Using Plan Tool for Reflexion

### Recording Lessons

```bash
# After a successful task
plan task complete --id 5

# Record what worked
plan lesson add --task-id 5 --type success_pattern \
  -c "Breaking the migration into small batches of 1000 rows prevented timeouts"

# After a failed attempt
plan task fail --id 6

# Record what went wrong
plan lesson add --task-id 6 --type failure_pattern \
  --trigger "database migration" \
  -c "Running migration during peak hours caused connection pool exhaustion. Always run migrations during low-traffic periods."

# Discovered constraint
plan lesson add --plan-id 1 --type constraint \
  --trigger "S3 upload" \
  -c "S3 multipart upload required for files over 5GB. Single PUT fails silently."

# Useful technique
plan lesson add --type technique \
  --trigger "debugging async" \
  -c "Add correlation IDs to all async operations. Makes tracing requests across services possible."
```

### Retrieving Relevant Lessons

Before starting a task, query for relevant lessons:

```bash
# Search lessons by keyword
plan lesson search -q "migration"

# Get high-confidence lessons
plan lesson list --min-confidence 0.7

# Get lessons for a specific type
plan lesson list --type constraint
```

### Validating and Invalidating Lessons

As lessons are applied, update their confidence:

```bash
# Lesson proved useful again
plan lesson validate --id 3
# Increases confidence score and times_validated count

# Lesson didn't apply as expected
plan lesson invalidate --id 3
# Decreases confidence score
```

## Reflexion Workflow Example

### Scenario: API Integration Task

```bash
# 1. Before starting, check for relevant lessons
plan lesson search -q "API integration"

# Found lesson: "Always check rate limits before implementing API calls"
# Confidence: 0.8, Validated: 5 times

# 2. Start the task with this knowledge in mind
plan task start --id 10

# 3. Record that we're applying a lesson
plan trace add --task-id 10 --type thought \
  -c "Applying lesson: checking API rate limits first. Found: 1000 req/hour."

# 4. Work proceeds... but we hit an issue
plan trace add --task-id 10 --type observation \
  -c "API returns 429 after ~100 requests, not 1000. Rate limit is per-minute, not per-hour."

# 5. Update the lesson with new information
plan lesson update --id 7 \
  -c "Check rate limit units carefully. Stripe is 100/sec, GitHub is 5000/hour, most APIs are per-minute."

# 6. Complete task and add new lesson
plan task complete --id 10

plan lesson add --task-id 10 --type constraint \
  --trigger "Acme API" \
  -c "Acme API rate limit is 100 req/min despite docs saying 1000/hour. Implement 600ms delay between calls."
```

### Scenario: Learning from Failure

```bash
# Task failed
plan task fail --id 12

# Review what happened via traces
plan trace history --task-id 12

# Output:
# 1. [thought] Need to parse CSV file with user data
# 2. [action] Using csv.reader with default settings
# 3. [observation] UnicodeDecodeError on line 4521
# 4. [thought] File might have non-UTF8 characters
# 5. [action] Trying encoding='latin-1'
# 6. [observation] Parsed but names corrupted: "José" became "JosÃ©"
# 7. [action] Trying encoding='utf-8-sig' for BOM handling
# 8. [observation] Still failing. File has mixed encodings.

# Create failure pattern lesson
plan lesson add --task-id 12 --type failure_pattern \
  --trigger "CSV parsing" \
  -c "CSV files from external sources often have mixed encodings.
      Use chardet library to detect encoding before parsing.
      If mixed, process line-by-line with error handling."

# Create technique lesson for the solution
plan lesson add --task-id 12 --type technique \
  --trigger "file encoding" \
  -c "For unknown encodings: 1) Try chardet detection, 2) If fails, use 'utf-8' with errors='replace', 3) Log replaced characters for manual review."
```

## Reflexion Best Practices

### 1. Be Specific in Lessons

```bash
# BAD: Too vague
plan lesson add --type failure_pattern -c "API calls failed"

# GOOD: Actionable detail
plan lesson add --type failure_pattern \
  --trigger "Stripe API" \
  -c "Stripe API returns 402 for expired cards but error message says 'card_declined'. Check decline_code field for actual reason."
```

### 2. Include Trigger Conditions

```bash
# Trigger helps with retrieval
plan lesson add --type constraint \
  --trigger "PostgreSQL JSONB" \
  -c "JSONB columns can't be indexed for arbitrary key lookups. Use GIN index only for known query patterns."
```

### 3. Connect Lessons to Tasks

```bash
# Link lesson to the task where it was discovered
plan lesson add --task-id 15 --type success_pattern \
  -c "Wrapping database operations in savepoints allows partial rollback without losing entire transaction."

# Later, when a similar task arises, you can see the full context
plan task show --id 15  # Shows the task details
plan trace history --task-id 15  # Shows how the lesson was discovered
```

### 4. Review and Prune Lessons Periodically

```bash
# List low-confidence lessons
plan lesson list --max-confidence 0.3

# Delete lessons that are no longer relevant
plan lesson delete --id 8

# Validate lessons that have proven useful
plan lesson validate --id 12
```

## Integrating Reflexion with Facts

Lessons and Facts serve different purposes:

| Aspect | Facts | Lessons |
|--------|-------|---------|
| Scope | Specific to a plan | Can be global or plan-specific |
| Purpose | Document constraints | Guide future behavior |
| Example | "API key is in env var ACME_KEY" | "Always check env vars before hardcoding keys" |
| Lifetime | Duration of plan | Persists across plans |

### Promoting Facts to Lessons

When a fact proves repeatedly useful:

```bash
# Original fact (plan-specific)
plan fact create --plan-id 1 -n "JWT Secret Location" \
  -c "JWT secret is in AWS Secrets Manager, not env vars"

# Later, realize this is a general pattern
plan lesson add --type technique \
  --trigger "secrets management" \
  -c "This codebase uses AWS Secrets Manager for all secrets. Check there first, not env vars."
```

## The Reflexion-Enhanced Agent Loop

```python
def reflexion_agent_loop(plan_id: int):
    while True:
        # 1. Get next task
        task = get_next_task(plan_id)
        if not task:
            break

        # 2. REFLEXION: Query relevant lessons before starting
        lessons = search_lessons(task["name"] + " " + task["description"])

        # 3. Start task with lessons in context
        start_task(task["id"])

        if lessons:
            add_trace(task["id"], "thought",
                f"Applying {len(lessons)} relevant lessons: {summarize(lessons)}")

        # 4. Execute task (with ReAct traces)
        result = execute_with_react(task, lessons)

        # 5. REFLEXION: Evaluate outcome and learn
        if result["success"]:
            complete_task(task["id"])

            # Validate lessons that helped
            for lesson in lessons:
                if lesson_was_helpful(lesson, result):
                    validate_lesson(lesson["id"])

            # Extract new success patterns
            if result.get("insights"):
                add_lesson("success_pattern", result["insights"], task["id"])
        else:
            fail_task(task["id"])

            # Record what went wrong
            add_lesson("failure_pattern",
                f"Failed because: {result['error']}. Approach: {result['approach']}",
                task["id"])

            # Invalidate lessons that didn't help
            for lesson in lessons:
                if lesson_was_misleading(lesson, result):
                    invalidate_lesson(lesson["id"])

        # 6. Export state
        export_plan(plan_id)
```

## Confidence Scoring

Lessons have confidence scores (0.0 to 1.0) that evolve:

| Event | Confidence Change |
|-------|-------------------|
| Lesson created | Starts at 0.5 |
| `lesson validate` | +0.1 (max 1.0) |
| `lesson invalidate` | -0.1 (min 0.0) |
| High `times_validated` | Indicates reliability |

Use confidence to prioritize lessons:

```bash
# For critical tasks, only use high-confidence lessons
plan lesson list --min-confidence 0.7

# For exploration, consider lower-confidence lessons too
plan lesson list --min-confidence 0.3
```

## Reflexion + ReAct Together

The patterns complement each other:

1. **ReAct** produces traces during task execution
2. **Reflexion** analyzes traces after task completion
3. **Lessons** inform future ReAct cycles

```
Session 1:
  ReAct traces → Task fails → Reflexion → Lesson stored

Session 2:
  New task → Query lessons → ReAct (informed by lessons) → Success
  → Validate lesson → Confidence increases
```

## Anti-Patterns to Avoid

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **No lessons from failures** | Same mistakes repeat | Always create failure_pattern after failed task |
| **Overly specific lessons** | Don't generalize | Extract the principle, not just the instance |
| **Never validating** | Confidence stays flat | Actively validate/invalidate as you apply lessons |
| **Lesson hoarding** | Noise in retrieval | Prune low-confidence, outdated lessons |
| **Ignoring lessons** | Wasted learning | Always query lessons before starting similar tasks |

## Summary

Reflexion transforms ephemeral agent experiences into persistent, searchable, validated knowledge. Combined with ReAct's explicit reasoning traces, it creates a learning system where agents genuinely improve over time.

Key commands:
```bash
# Record lessons
plan lesson add --type <type> --trigger "<context>" -c "<lesson>"

# Query before tasks
plan lesson search -q "<keywords>"

# Update confidence
plan lesson validate --id N
plan lesson invalidate --id N

# Review lessons
plan lesson list --min-confidence 0.5
```
