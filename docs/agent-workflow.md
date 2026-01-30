# LLM Agent Workflow Guide

This guide shows how to use the Plan tool in an agentic workflow, combining ReAct reasoning traces with Reflexion learning.

## The Complete Agent Loop

```
┌──────────────────────────────────────────────────────────────────────┐
│                         AGENT WORK SESSION                           │
├──────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. INITIALIZE                                                       │
│     └─▶ plan task next --plan-id N                                  │
│         (Get highest-priority unblocked task)                        │
│                                                                      │
│  2. PREPARE                                                          │
│     ├─▶ plan lesson search -q "<task keywords>"                     │
│     │   (Query relevant lessons from past experience)                │
│     │                                                                │
│     └─▶ plan task start --id N                                      │
│         (Transition: pending → in_progress)                          │
│                                                                      │
│  3. EXECUTE (ReAct Loop)                                            │
│     ┌─────────────────────────────────────────────────┐             │
│     │  THOUGHT → ACTION → OBSERVATION → (repeat)      │             │
│     │                                                 │             │
│     │  plan trace add --task-id N --type thought ...  │             │
│     │  plan trace add --task-id N --type action ...   │             │
│     │  plan trace add --task-id N --type observation  │             │
│     └─────────────────────────────────────────────────┘             │
│                                                                      │
│  4. COMPLETE                                                         │
│     ├─▶ plan task complete --id N  (if success)                     │
│     │       OR                                                       │
│     └─▶ plan task fail --id N      (if failure)                     │
│                                                                      │
│  5. REFLECT (Reflexion)                                             │
│     ├─▶ plan trace add --type reflection ...                        │
│     │   (What worked? What didn't?)                                  │
│     │                                                                │
│     ├─▶ plan lesson add --type <type> ...                           │
│     │   (Capture reusable learning)                                  │
│     │                                                                │
│     └─▶ plan lesson validate --id N                                 │
│         (If applied lesson was helpful)                              │
│                                                                      │
│  6. CONTINUE                                                         │
│     └─▶ Loop back to step 1                                         │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

## Quick Reference: Essential Commands

```bash
# Task lifecycle
plan task next --plan-id 1        # Get next task to work on
plan task start --id N            # Begin working (pending → in_progress)
plan task complete --id N         # Finish successfully
plan task fail --id N             # Mark as failed
plan task show --id N             # View task with dependencies

# Dependencies
plan task depends --id 2 --on 1   # Task 2 blocked by task 1
plan task ready --plan-id 1       # List all unblocked tasks

# Reasoning traces (ReAct)
plan trace add --task-id N --type thought -c "..."
plan trace add --task-id N --type action -c "..."
plan trace add --task-id N --type observation -c "..."
plan trace add --task-id N --type reflection -c "..."
plan trace history --task-id N

# Learning (Reflexion)
plan lesson add --task-id N --type success_pattern -c "..."
plan lesson add --type failure_pattern --trigger "context" -c "..."
plan lesson search -q "keywords"
plan lesson validate --id N
plan lesson invalidate --id N
```

## Complete Example: Feature Implementation

Let's walk through implementing a "password reset" feature.

### Phase 1: Planning

```bash
# Create the plan
plan new -n "password-reset" -f password-reset.md

# Edit the markdown to add tasks with dependencies
```

**password-reset.md:**
```yaml
---
format_version: 3
description: Add password reset functionality
completed: false
tasks:
- name: Add reset_tokens table
  status: pending
  priority: 10
  acceptance_criteria: |
    - Table exists with token, user_id, expires_at columns
    - Index on token column
    - Foreign key to users table

- name: Create POST /forgot-password endpoint
  status: pending
  priority: 20
  blocked_by:
    - Add reset_tokens table
  acceptance_criteria: |
    - Accepts email in request body
    - Creates token in database
    - Returns 200 even if email not found (security)

- name: Create POST /reset-password endpoint
  status: pending
  priority: 30
  blocked_by:
    - Add reset_tokens table
  acceptance_criteria: |
    - Accepts token and new_password
    - Validates token not expired
    - Updates user password
    - Invalidates token after use

- name: Send reset email
  status: pending
  priority: 25
  blocked_by:
    - Create POST /forgot-password endpoint
  acceptance_criteria: |
    - Email contains reset link
    - Link expires in 1 hour
    - Uses HTML template

- name: Add integration tests
  status: pending
  priority: 40
  blocked_by:
    - Create POST /reset-password endpoint
    - Send reset email

facts:
- name: Email Provider
  content: Using SendGrid. API key in SENDGRID_API_KEY env var.
- name: Password Requirements
  content: Minimum 12 chars, at least one number and one symbol.
---

# Password Reset Feature

## Goal
Allow users to reset their password via email link.

## Security Considerations
- Tokens must be cryptographically random
- Tokens expire after 1 hour
- Don't reveal whether email exists in system
```

```bash
# Import the plan
plan import -f password-reset.md

# View what's ready to work on
plan task ready --plan-id 1
# => Task 1: Add reset_tokens table (priority: 10, no blockers)
```

### Phase 2: Execute First Task

```bash
# Check for relevant lessons
plan lesson search -q "database migration table"
# => Found: "Always add created_at timestamp to new tables"

# Get and start the task
plan task next --plan-id 1
plan task start --id 1

# THOUGHT: Plan the approach
plan trace add --task-id 1 --type thought \
  -c "Need to create reset_tokens table. Applying lesson: include created_at.
      Columns needed: id, token (unique), user_id (FK), expires_at, created_at."

# ACTION: Create migration
plan trace add --task-id 1 --type action \
  -c "Creating migration: npx prisma migrate dev --name add_reset_tokens"

# OBSERVATION: Result
plan trace add --task-id 1 --type observation \
  -c "Migration created successfully. Table has id, token, user_id, expires_at, created_at.
      Prisma auto-added index on id. Need to manually add index on token."

# THOUGHT: Need additional index
plan trace add --task-id 1 --type thought \
  -c "Token lookups will be frequent. Adding explicit index on token column."

# ACTION: Add index
plan trace add --task-id 1 --type action \
  -c "Added @@index([token]) to schema.prisma, running migrate again."

# OBSERVATION: Verify
plan trace add --task-id 1 --type observation \
  -c "Index created. Verified with: EXPLAIN SELECT * FROM reset_tokens WHERE token = 'x'
      Shows index scan, not seq scan."

# Complete the task
plan task complete --id 1

# REFLECTION: What we learned
plan trace add --task-id 1 --type reflection \
  -c "Prisma doesn't auto-index unique-like fields. Always verify query plans for lookup columns."

# Capture lesson
plan lesson add --task-id 1 --type technique \
  --trigger "Prisma schema" \
  -c "After creating tables, verify indexes with EXPLAIN. Prisma only auto-indexes @id and explicit @@index."

# Validate the lesson we applied
plan lesson validate --id 1  # The "add created_at" lesson
```

### Phase 3: Execute Dependent Task

```bash
# What's ready now?
plan task ready --plan-id 1
# => Task 2: Create POST /forgot-password endpoint (priority: 20)
# => Task 3: Create POST /reset-password endpoint (priority: 30)
# (Both unblocked now that task 1 is complete)

# Start the higher priority one
plan task start --id 2

# Check for relevant lessons
plan lesson search -q "API endpoint security email"
# => Found: "Never reveal if email exists - always return 200"

# THOUGHT
plan trace add --task-id 2 --type thought \
  -c "Implementing forgot-password. Key security concern: don't reveal email existence.
      Will return 200 with same message whether email found or not."

# ... continue with ReAct traces ...

# When discovering something new, capture as fact
plan fact create --plan-id 1 -n "Token Generation" \
  -c "Using crypto.randomBytes(32).toString('hex') for tokens. 64 char hex string."

# Complete when done
plan task complete --id 2
```

### Phase 4: Handle a Blocker

```bash
# Start the email sending task
plan task start --id 4

# OBSERVATION: Problem discovered
plan trace add --task-id 4 --type observation \
  -c "SendGrid API returning 403. Checked env var - SENDGRID_API_KEY is set but getting 'invalid API key' error."

# THOUGHT: Investigate
plan trace add --task-id 4 --type thought \
  -c "API key might be wrong or revoked. Need to check SendGrid dashboard. This is blocking - can't proceed without working email."

# Block this task
plan task update --id 4 --status blocked

# Create a new task for the blocker
plan task create --plan-id 1 -n "Fix SendGrid API key" \
  -d "Current key returning 403. Check SendGrid dashboard and update env var." \
  --priority 5

# Add dependency: email task blocked by API key task
plan task depends --id 4 --on 6

# Update fact with new information
plan fact update --id 2 \
  -c "Using SendGrid. API key in SENDGRID_API_KEY env var. NOTE: Key may need rotation - got 403 on 2024-01-15."

# Record lesson about this type of issue
plan lesson add --task-id 4 --type failure_pattern \
  --trigger "API integration" \
  -c "When API returns auth errors, check: 1) Key exists in env, 2) Key not revoked in provider dashboard, 3) Key has correct permissions/scopes."
```

### Phase 5: Session Handoff

```bash
# End of session - export current state
plan export --id 1 -f password-reset.md

# The markdown now contains:
# - Updated task statuses
# - New facts discovered
# - Dependencies
# - Full audit trail in traces (if included)
```

**Next session:**
```bash
# Resume work
plan import -f password-reset.md  # Sync any manual edits

# Check current state
plan plan show --id 1

# What's ready?
plan task ready --plan-id 1
# Shows unblocked pending tasks

# Continue the loop...
plan task next --plan-id 1
```

## Workflow Patterns

### Pattern 1: Depth-First (Focus)

Work on one task until complete before moving to next:

```bash
while task=$(plan task next --plan-id 1); do
    plan task start --id $task
    # ... work until done ...
    plan task complete --id $task
done
```

### Pattern 2: Breadth-First (Unblocking)

Prioritize unblocking other tasks:

```bash
# Find tasks that block the most other tasks
plan task list --plan-id 1 --sort-by blocks-count

# Work on high-impact blockers first
```

### Pattern 3: Priority-Driven

Let the system decide based on priority:

```bash
# Always just get the next task - system handles priority + dependencies
plan task next --plan-id 1
```

### Pattern 4: Time-Boxed

Work for fixed intervals then export:

```bash
# Start of session
plan import -f project.md

# Work for N tasks or until blocked
for i in {1..5}; do
    task=$(plan task next --plan-id 1)
    [ -z "$task" ] && break
    # ... work on task ...
done

# End of session
plan export --id 1 -f project.md
```

## Integration with LLM Agents

### System Prompt Addition

```markdown
## Task Management Protocol

You have access to the `plan` CLI for task management. Follow this workflow:

1. At session start:
   - Run `plan task next --plan-id {PLAN_ID}` to get your current task
   - Run `plan lesson search -q "{task description}"` for relevant lessons

2. Before taking action:
   - Record your reasoning: `plan trace add --task-id {ID} --type thought -c "..."`

3. After each action:
   - Record what you did: `plan trace add --task-id {ID} --type action -c "..."`
   - Record the result: `plan trace add --task-id {ID} --type observation -c "..."`

4. When task is complete:
   - Run `plan task complete --id {ID}`
   - Reflect: `plan trace add --task-id {ID} --type reflection -c "..."`
   - If you learned something reusable: `plan lesson add --type {type} -c "..."`

5. If blocked:
   - Run `plan task update --id {ID} --status blocked`
   - Create blocker task and add dependency

6. At session end:
   - Run `plan export --id {PLAN_ID} -f {filename}.md`
```

### Tool Definitions

```python
PLAN_TOOLS = [
    {
        "name": "get_next_task",
        "description": "Get the highest priority unblocked task",
        "parameters": {"plan_id": "integer"}
    },
    {
        "name": "start_task",
        "description": "Begin working on a task (changes status to in_progress)",
        "parameters": {"task_id": "integer"}
    },
    {
        "name": "add_trace",
        "description": "Record reasoning (thought), action taken (action), result observed (observation), or lesson learned (reflection)",
        "parameters": {
            "task_id": "integer",
            "trace_type": "enum: thought|action|observation|reflection",
            "content": "string"
        }
    },
    {
        "name": "complete_task",
        "description": "Mark task as successfully completed",
        "parameters": {"task_id": "integer"}
    },
    {
        "name": "fail_task",
        "description": "Mark task as failed",
        "parameters": {"task_id": "integer"}
    },
    {
        "name": "search_lessons",
        "description": "Find relevant lessons from past experience",
        "parameters": {"query": "string"}
    },
    {
        "name": "add_lesson",
        "description": "Record a reusable lesson for future tasks",
        "parameters": {
            "lesson_type": "enum: success_pattern|failure_pattern|constraint|technique",
            "content": "string",
            "trigger": "string (optional - context when lesson applies)"
        }
    }
]
```

## Summary

The complete workflow combines:

1. **Task Management**: Status lifecycle, dependencies, priorities
2. **ReAct**: Explicit reasoning traces during execution
3. **Reflexion**: Learning from experience across sessions

This creates an agent that:
- Knows what to work on next
- Thinks before acting
- Records its reasoning
- Learns from both success and failure
- Maintains state across sessions
- Improves over time
