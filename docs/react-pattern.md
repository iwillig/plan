# ReAct Pattern for LLM Agents

ReAct (Reasoning + Acting) is a prompting paradigm that interleaves reasoning traces with actions. Instead of acting immediately, the agent explicitly thinks through each step, observes results, and adjusts.

## The ReAct Loop

```
┌─────────────────────────────────────────────────────────┐
│                                                         │
│    ┌──────────┐    ┌──────────┐    ┌─────────────┐     │
│    │  THOUGHT │───▶│  ACTION  │───▶│ OBSERVATION │     │
│    └──────────┘    └──────────┘    └─────────────┘     │
│         ▲                                   │          │
│         │                                   │          │
│         └───────────────────────────────────┘          │
│                                                         │
│    After multiple cycles:                               │
│                                                         │
│    ┌────────────┐                                       │
│    │ REFLECTION │  (What worked? What didn't?)         │
│    └────────────┘                                       │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## Trace Types

| Type | Purpose | Example |
|------|---------|---------|
| **thought** | Reasoning about what to do next | "The tests are failing on line 42. I should check the input validation." |
| **action** | What the agent is doing | "Running pytest tests/test_user.py" |
| **observation** | Results of the action | "3 tests passed, 1 failed: test_empty_email" |
| **reflection** | Meta-reasoning about the approach | "Email validation is too strict. Empty string should return error, not crash." |

## Why ReAct Matters for LLM Agents

### Problem: Black-Box Execution

Without explicit reasoning, agents:
- Jump to conclusions without analysis
- Miss important context
- Can't explain their decisions
- Make the same mistakes repeatedly

### Solution: Externalized Thinking

ReAct forces the agent to:
- Articulate reasoning before acting
- Record observations for later reference
- Build a traceable decision history
- Enable debugging and improvement

## Using Plan Tool for ReAct

### Recording Traces

```bash
# Starting work on a task
plan task start --id 1

# Record your reasoning
plan trace add --task-id 1 --type thought \
  -c "Need to add user authentication. Should check existing auth patterns first."

# Record the action
plan trace add --task-id 1 --type action \
  -c "Searching codebase for existing auth: grep -r 'authenticate' src/"

# Record what you observed
plan trace add --task-id 1 --type observation \
  -c "Found existing JWT helper in src/utils/jwt.js. Uses RS256 algorithm."

# Another thought based on observation
plan trace add --task-id 1 --type thought \
  -c "Should reuse existing JWT helper rather than creating new one."

# After completing work, reflect
plan trace add --task-id 1 --type reflection \
  -c "Checking for existing patterns before implementing saved significant time."
```

### Viewing Trace History

```bash
# View all traces for a task
plan trace history --task-id 1

# Output:
# 1. [thought] Need to add user authentication...
# 2. [action] Searching codebase for existing auth...
# 3. [observation] Found existing JWT helper...
# 4. [thought] Should reuse existing JWT helper...
# 5. [reflection] Checking for existing patterns...
```

## ReAct Workflow Example

### Scenario: Fix a Bug

```bash
# 1. Get the next task
plan task next --plan-id 1
# => Task 3: "Fix login timeout bug"

# 2. Start the task
plan task start --id 3

# 3. THOUGHT: Analyze the problem
plan trace add --task-id 3 --type thought \
  -c "Users report being logged out after 5 minutes. JWT expiry is set to 1 hour.
      This suggests the issue is not with token expiry itself."

# 4. ACTION: Investigate
plan trace add --task-id 3 --type action \
  -c "Checking token refresh logic in src/auth/refresh.js"

# 5. OBSERVATION: What we found
plan trace add --task-id 3 --type observation \
  -c "refresh.js checks token expiry but uses client time, not server time.
      Client clocks can be wrong."

# 6. THOUGHT: Form hypothesis
plan trace add --task-id 3 --type thought \
  -c "Hypothesis: Clock skew between client and server causes premature logout.
      Should use server-issued expiry timestamp, not client-calculated."

# 7. ACTION: Implement fix
plan trace add --task-id 3 --type action \
  -c "Modified refresh.js to use exp claim from JWT instead of Date.now()"

# 8. OBSERVATION: Verify fix
plan trace add --task-id 3 --type observation \
  -c "Tests passing. Manual test with clock set 10 min ahead: no premature logout."

# 9. REFLECTION: What we learned
plan trace add --task-id 3 --type reflection \
  -c "Never trust client time for security-sensitive operations.
      Always use server-authoritative timestamps."

# 10. Complete the task
plan task complete --id 3

# 11. Capture the lesson for future reference
plan lesson add --task-id 3 --type constraint \
  -c "Use server timestamps, not client time, for token expiry checks"
```

## ReAct Best Practices

### 1. Think Before Acting

```bash
# BAD: Jumping straight to action
plan trace add --type action -c "Changing the database schema"

# GOOD: Reason first
plan trace add --type thought -c "Need to add email field. Should check if users table exists and what columns it has."
plan trace add --type action -c "Running: SELECT * FROM information_schema.columns WHERE table_name='users'"
```

### 2. Record Observations Faithfully

```bash
# BAD: Vague observation
plan trace add --type observation -c "It didn't work"

# GOOD: Specific observation
plan trace add --type observation -c "Error: UNIQUE constraint failed: users.email. User with email 'test@example.com' already exists."
```

### 3. Reflect on Failures

```bash
# After a failed approach
plan trace add --type reflection \
  -c "Tried to update email in-place but hit unique constraint.
      Should have checked for existing email first.
      Next time: always check constraints before UPDATE."
```

### 4. Use Thoughts to Plan Multi-Step Actions

```bash
plan trace add --type thought \
  -c "To add pagination I need to:
      1. Add page/limit params to API endpoint
      2. Modify SQL query to use LIMIT/OFFSET
      3. Return total count in response
      4. Update frontend to pass page params
      Will start with backend changes."
```

## Integrating ReAct with Task Dependencies

ReAct traces help you discover dependencies you didn't know existed:

```bash
# During work, you discover a blocker
plan trace add --type observation \
  -c "Can't add email verification - SMTP not configured in staging environment"

plan trace add --type thought \
  -c "This task is blocked until DevOps configures SMTP. Creating dependency."

# Block the current task and create a new one
plan task update --id 5 --status blocked
plan task create --plan-id 1 -n "Configure SMTP in staging" -d "Required for email verification"

# Add the dependency
plan task depends --id 5 --on 6
```

## When to Use Each Trace Type

| Situation | Trace Type |
|-----------|------------|
| Analyzing a problem | thought |
| Forming a hypothesis | thought |
| Planning next steps | thought |
| Running a command | action |
| Making a code change | action |
| Calling an API | action |
| Reading command output | observation |
| Seeing test results | observation |
| Noticing an error | observation |
| Summarizing what worked | reflection |
| Noting what to do differently | reflection |
| Extracting a reusable lesson | reflection |

## ReAct + Reflexion

ReAct traces feed into the Reflexion pattern. After completing (or failing) a task:

1. Review the trace history
2. Identify what worked and what didn't
3. Extract lessons for future tasks
4. Store lessons with confidence scores

See [Reflexion Pattern](reflexion-pattern.md) for details on learning from experience.
