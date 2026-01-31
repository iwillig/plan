# LLM Agent Instructions - Plan

**Version:** 1.2
**Last Updated:** 2025-01-30
**Project:** plan
**Shell:** fish (Friendly Interactive Shell)

## Quick Start - 5 Critical Rules

1. **Run `bb ci` before completing any task** - runs formatting, linting, and tests
2. **Use `clj-nrepl-eval` when REPL is running** - never spawn new processes
3. **Write docstrings** for all public functions
4. **Pass connections explicitly** - use `db/with-connection` pattern
5. **Never use `conn-from-db` in production** - pass connections, not snapshots

See detailed sections below for complete guidelines.

## Check for Running REPL First

**ALWAYS check if a REPL is running before executing any Clojure code.** This determines which approach to use for running tests and evaluating code.

### How to Check

```fish
# Check if REPL is running on port 7889
pgrep -f "bb.*nrepl"

# Or check if port 7889 is in use
lsof -i :7889 2>/dev/null || netstat -an 2>/dev/null | grep 7889 || echo "Port 7889 not in use"
```

### Decision Tree

```
Is REPL running on port 7889?
├── YES → Use clj-nrepl-eval with (k/run) for tests
│         Use clj-nrepl-eval for all code execution
│
└── NO  → Start REPL with "bb nrepl" (background it)
          Wait for REPL to be ready
          Then use clj-nrepl-eval with (k/run)
```

### Required Workflow

**Before running ANY tests or Clojure code:**

1. **Check if REPL is running:**
   ```fish
   pgrep -f "bb.*nrepl" && echo "REPL running" || echo "REPL not running"
   ```

2. **If REPL is NOT running:**
   ```fish
   # Start REPL in background
   bb nrepl > /tmp/nrepl.log 2>&1 &
   
   # Wait for REPL to be ready
   sleep 3
   
   # Verify it's running
   pgrep -f "bb.*nrepl"
   ```

3. **Initialize dev environment (once per REPL session):**
   ```fish
   clj-nrepl-eval -p 7889 "(fast-dev)"
   ```

4. **Now run tests using k/run:**
   ```fish
   clj-nrepl-eval -p 7889 "(k/run)"
   ```

**Never assume the REPL state - always verify first.**

## Running Tests (REPL-First Approach)

**ALWAYS use the REPL to run tests during development.** This is faster and provides better feedback than spawning new JVM processes.

### Step 1: Verify REPL is Running

Before attempting to run tests, you MUST verify the REPL is active:

```fish
# Check if REPL process is running
pgrep -f "bb.*nrepl" && echo "REPL is running" || echo "REPL is NOT running"

# Alternative: test connection directly
clj-nrepl-eval -p 7889 "(+ 1 1)" 2>/dev/null && echo "REPL responsive" || echo "REPL not responding"
```

### Step 2: Start REPL if Needed

If the REPL is not running, start it:

```fish
# Start REPL in background
bb nrepl > /tmp/nrepl.log 2>&1 &

# Wait for startup
sleep 3

# Verify it's ready
pgrep -f "bb.*nrepl"
```

### Step 3: Initialize Dev Environment

Once the REPL is confirmed running, initialize the fast-dev environment:

```fish
# One-time setup - loads dev namespace with test helpers
clj-nrepl-eval -p 7889 "(fast-dev)"
```

This loads the `dev` namespace which provides:
- `dev/reload` - Reload and compile all namespaces
- `dev/lint` - Run clj-kondo linter
- `k/run` - Run Kaocha tests (the primary way to run tests)

### Running Tests with k/run

After running `(fast-dev)`, use `k/run` for all test execution:

```fish
# Run all tests
clj-nrepl-eval -p 7889 "(k/run)"

# Run tests for a specific namespace
clj-nrepl-eval -p 7889 "(k/run 'plan.config-test)"

# Run a specific test
clj-nrepl-eval -p 7889 "(k/run 'plan.config-test/db-path-test)"

# Run tests with custom configuration
clj-nrepl-eval -p 7889 "(k/run {} {:reporter [kaocha.report/documentation]})"
```

### Test Development Workflow

When writing or debugging tests, follow this workflow:

```fish
# 0. ALWAYS check REPL is running first
pgrep -f "bb.*nrepl" || (bb nrepl > /tmp/nrepl.log 2>&1 &; sleep 3)

# 1. Reload code after making changes
clj-nrepl-eval -p 7889 "(dev/reload)"

# 2. Run the specific test you're working on
clj-nrepl-eval -p 7889 "(k/run 'plan.my-new-test)"

# 3. Run all tests to ensure nothing is broken
clj-nrepl-eval -p 7889 "(k/run)"

# 4. Run linter to check code quality
clj-nrepl-eval -p 7889 "(dev/lint)"
```

### When to Use bb test vs k/run

| Scenario | Command | Why |
|----------|---------|-----|
| REPL is running | `clj-nrepl-eval -p 7889 "(k/run)"` | Fast, no JVM startup |
| REPL not running | `bb test` | Standalone execution |
| CI/CD automation | `bb ci` | Full validation pipeline |
| Final verification | `bb ci` | Ensures everything passes |

**Important**: When the REPL is running, `k/run` is the ONLY method you should use to execute tests. Never use `bb test` or `clojure -M:...` commands when a REPL is available.

## Critical Rules

### MUST DO

1. **Always run `bb ci` before completing any task** - runs
   formatting, linting, and tests
2. **Write docstrings** for all new records and public functions
3. **Write tests** for new functionality in the corresponding `test/plan/` directory
4. **Use clj-nrepl-eval when REPL is running** - never spawn new Clojure processes with `clojure` command
5. **Use `db/with-connection` pattern** for database access
6. **Always check if REPL is running before executing tests** - use `pgrep -f "bb.*nrepl"`

### MUST NOT

1. **Never commit code that fails `bb ci`**
2. **Never use `def` for mutable state** - use atoms if needed, passed explicitly
3. **Never add dependencies without explicit user approval**
4. **Never use `println` for logging** - use `cambium` instead
5. **Never use emojis** - in code, comments, commit messages, documentation, or any output
6. **Never use `clojure -M:...` command when REPL is running** - use clj-nrepl-eval instead

## Commands Reference

```bash
bb ci              # REQUIRED: Run before completing work (clean -> fmt-check -> lint -> test)
bb test            # Run all tests with Kaocha
bb lint            # Run clj-kondo linter
bb fmt             # Fix code formatting
bb fmt-check       # Check formatting without fixing
bb clean           # Remove build artifacts
bb nrepl           # Start development REPL on port 7889
bb main            # Run the application
```

### When to Use Each Command

**Use `bb test` when:**

- Running tests in CI/automation
- No REPL is running
- Need standalone test execution

**Use `clj-nrepl-eval` with `k/run` when:**

- REPL is running (preferred for development)
- Need interactive test debugging
- Want faster feedback without JVM startup
- This is the REQUIRED approach during development

**Never use these when REPL is running:**

- `bb test` - bypasses the running REPL
- `clojure -M:...:test` - spawns new JVM process
- Any `clojure` command for test execution

## Shell Environment (fish)

This project uses **fish shell** (Friendly Interactive Shell). Key
differences from bash:

### Multi-line Commands in fish

Fish uses different syntax for multi-line commands and heredocs:

```fish
# Single-line command
clj-nrepl-eval -p 7889 "(+ 1 2)"

# Multi-line code - use heredoc with single quotes (prevents interpolation)
clj-nrepl-eval -p 7889 <<'EOF'
(require '[plan.db :as db])
(db/with-connection (plan.config/db-path) (fn [conn] (plan.db/execute! conn {:select [:*] :from [:plans]})))
EOF

# Multi-line with backslash continuation
clj-nrepl-eval -p 7889 \
  "(do (require 'plan.main :reload-all) \
       (plan.main/create-schema! conn))"

# Background job (note: fish uses 'set' not export, and & works the same)
bb main > /tmp/server.log 2>&1 &

# Variable assignment (fish uses 'set' not '=')
set my_var "value"
echo $my_var
```

### Common fish Patterns

```fish
# Check if process is running
pgrep -f "bb.*nrepl"

# Kill and restart process
pkill -f "bb.*nrepl"
sleep 2
cd /path/to/project
bb nrepl > /tmp/nrepl.log 2>&1 &

# Multiple commands (use 'and' or 'or', not '&&' or '||')
bb clean; and bb fmt; and bb test

# Conditional execution
if test -f deps.edn
    echo "Found deps.edn"
end
```

## REPL Usage (CRITICAL)

**When a REPL is running (started with `bb nrepl` on port 7889),
ALWAYS use `clj-nrepl-eval` to execute code.**

### DO THIS (when REPL is running):

```fish
# Evaluate any Clojure expression
clj-nrepl-eval -p 7889 "(+ 1 2)"

# Require and reload namespaces
clj-nrepl-eval -p 7889 "(require '[plan.db :as db] :reload)"

# Test functions
clj-nrepl-eval -p 7889 "(plan.config/db-path)"

# Initialize dev environment (run once after REPL starts)
clj-nrepl-eval -p 7889 "(fast-dev)"

# Run tests using k/run (REQUIRED approach)
clj-nrepl-eval -p 7889 "(k/run)"                    ; Run all tests
clj-nrepl-eval -p 7889 "(k/run 'plan.config-test)"  ; Run specific namespace
clj-nrepl-eval -p 7889 "(k/run 'plan.config-test/db-path-test)"  ; Run specific test

# Reload code after changes
clj-nrepl-eval -p 7889 "(dev/reload)"

# Run linter
clj-nrepl-eval -p 7889 "(dev/lint)"

# Multi-line code block with heredoc (preferred for complex code)
clj-nrepl-eval -p 7889 <<'EOF'
(do
  (require 'plan.main :reload-all)
  (plan.main/create-schema! conn))
EOF
```

### DO NOT DO THIS (when REPL is running):

```fish
# ✗ WRONG - spawns new JVM process, doesn't use running REPL
clojure -M:jvm-base:dev -e "(some-function)"

# ✗ WRONG - runs separate process, not connected to running system
clojure -M:jvm-base:dev:test --focus plan.main-test

# ✗ WRONG - bypasses REPL even though it's running
bb test

# ✗ WRONG - uses kaocha.repl/run directly instead of k/run alias
clj-nrepl-eval -p 7889 "(require 'kaocha.repl) (kaocha.repl/run)"
```

**Why `k/run` instead of `kaocha.repl/run`?**

The `k/run` function is pre-configured with the correct test configuration and is the standard alias used throughout this project. Always use `k/run` after running `(fast-dev)`.

## Code Style Guidelines

Follow the [Clojure Community Style Guide](https://guide.clojure.style) conventions.

### Code Layout

- **Line Length**: Keep lines under 80 characters when feasible
- **Indentation**: Use 2 spaces for body indentation, never tabs
- **Closing Parens**: Gather trailing parentheses on a single line

```clojure
;; Good - 2 space body indent, closing parens together
(when something
  (something-else))

;; Bad - separate lines for closing parens
(when something
  (something-else)
)
```

### Namespace Declaration

Use sorted, aligned requires with single-space indent:

```clojure
(ns plan.example
  (:require
   [clojure.string :as str]
   [plan.config :as config]
   [plan.db :as db])
  (:import
   (java.io
    File)))

(set! *warn-on-reflection* true)
```

### Naming Conventions

**Functions and vars** use `kebab-case`:

```clojure
;; Good
(defn calculate-total-price [items])
(def max-retry-attempts 3)

;; Bad - don't use camelCase or snake_case
(defn calculateTotalPrice [items])
```

**Predicates** end with `?`:

```clojure
;; Good
(defn valid-email? [email])
(defn active? [user])

;; Bad
(defn is-valid-email [email])
```

**Conversion functions** use `source->target`:

```clojure
(defn map->vector [m])
(defn string->int [s])
```

**Dynamic vars** use earmuffs:

```clojure
(def ^:dynamic *connection* nil)
```

### Threading Macros

**Use `->` (thread-first) for object/map transformations:**

```clojure
;; Good - data flows through first position
(-> user
    (assoc :last-login (Instant/now))
    (update :login-count inc)
    (dissoc :temporary-token))

;; Bad - deeply nested
(dissoc
  (update
    (assoc user :last-login (Instant/now))
    :login-count inc)
  :temporary-token)
```

**Use `->>` (thread-last) for collection operations:**

```clojure
;; Good - data flows through last position
(->> users
     (filter active?)
     (map :email)
     (remove nil?)
     (sort))

;; Bad - nested collection operations
(sort (remove nil? (map :email (filter active? users))))
```

**Use `some->` to short-circuit on nil:**

```clojure
(some-> user
        :address
        :postal-code
        (subs 0 5))
```

**Use `cond->` for conditional transformations:**

```clojure
(cond-> request
  authenticated? (assoc :user current-user)
  admin?         (assoc :permissions :all)
  (:debug opts)  (assoc :debug true))
```

### Control Flow

**Use `when` for single-branch with side effects:**

```clojure
;; Good
(when (valid-input? data)
  (log-event "Processing data")
  (process data))

;; Bad - if without else for side effects
(if (valid-input? data)
  (do
    (log-event "Processing data")
    (process data)))
```

**Use `cond` for multiple conditions:**

```clojure
;; Good
(cond
  (< n 0) :negative
  (= n 0) :zero
  (> n 0) :positive)

;; Bad - nested ifs
(if (< n 0)
  :negative
  (if (= n 0)
    :zero
    :positive))
```

### Data Structure Idioms

**Prefer plain data structures over custom types:**

```clojure
;; Good - plain maps
(def user {:id 123
           :email "user@example.com"
           :roles #{:admin :editor}})

;; Use keywords as map keys
{:name "Alice" :age 30}

;; Bad - string keys
{"name" "Alice" "age" 30}
```

**Use destructuring to extract values:**

```clojure
;; Good - destructuring in function arguments
(defn format-user [{:keys [first-name last-name email]}]
  (str last-name ", " first-name " <" email ">"))

;; With defaults
(defn connect [{:keys [host port timeout]
                :or {port 8080 timeout 5000}}]
  (create-connection host port timeout))
```

**Use `into` for combining collections:**

```clojure
(into [] (filter even? [1 2 3 4]))  ;=> [2 4]
(into {} (map (fn [x] [x (* x 2)]) [1 2 3]))  ;=> {1 2, 2 4, 3 6}
```

### Function Composition

**Use `#()` for simple, single-expression functions:**

```clojure
;; Good
(map #(* % 2) numbers)
(filter #(> % 10) values)

;; Bad - #() for complex expressions
(map #(if (> % 10) (* % 2) (/ % 2)) numbers)
```

**Use `fn` for multi-expression or named functions:**

```clojure
(map (fn [x]
       (let [doubled (* x 2)]
         (if (even? doubled)
           doubled
           (inc doubled))))
     numbers)
```

**Prefer higher-order functions over explicit loops:**

```clojure
;; Good - declarative
(->> items
     (filter valid?)
     (map transform)
     (reduce combine))

;; Avoid - explicit loop/recur when not needed
```

### Database Access Pattern

Use the `db/with-connection` helper to ensure connections are properly closed:

```clojure
;; Good - connection is automatically closed
(defn fetch-plans []
  (db/with-connection (config/db-path)
    (fn [conn]
      (db/execute! conn {:select [:*] :from [:plans]}))))

;; Good - for multiple operations on same connection
(defn complex-operation []
  (db/with-connection (config/db-path)
    (fn [conn]
      (let [plans (db/execute! conn {:select [:*] :from [:plans]})
            tasks (db/execute! conn {:select [:*] :from [:tasks]})]
        {:plans plans :tasks tasks}))))
```

### CLI Command Pattern (cli-matic)

Command functions receive a map of parsed arguments:

```clojure
;; Define the command handler
(defn my-command
  "Handler for my command"
  [{:keys [option1 option2]}]
  (let [db-path (config/db-path)]
    (db/with-connection db-path
      (fn [conn]
        ;; Command logic here
        (clojure.pprint/pprint result)))))

;; Register in CLI definition
(def cli-definition
  {:app-name "plan"
   :subcommands
   [{:command "my-cmd"
     :description "Does something"
     :opts [{:as "Option 1" :option "option1" :short "o" :type :string}]
     :runs my-command}]})
```

### Error Handling (Failjure)

```clojure
(defn fetch-user [id]
  (f/try*
    (if-let [user (db/get-user id)] user (f/fail "User not found"))))

(f/if-let-ok? [user (fetch-user 123)]
  (handle-success user)
  (handle-error user))
```

### HoneySQL vs Raw SQL

Use HoneySQL for standard queries, raw SQL for SQLite-specific features:

```clojure
;; HoneySQL for standard queries
(db/execute! conn {:select [:*] :from [:plans] :where [:= :id 1]})

;; Raw SQL for FTS5, virtual tables, triggers
(db/execute! conn [:raw "CREATE VIRTUAL TABLE IF NOT EXISTS plans_fts USING fts5(...)"])
```

## Test Patterns

```clojure
(ns plan.feature-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plan.db :as db]
   [plan.main :as main]
   [plan.test-helper :as helper]))

;; Use the db-fixture for database tests
(use-fixtures :each helper/db-fixture)

(deftest feature-works
  (testing "specific behavior"
    (main/create-schema! helper/*conn*)
    ;; Test code here
    (is (= expected actual))))
```

### Running Tests During Development

When working on tests, use this workflow:

```fish
# 0. Check REPL status and start if needed
pgrep -f "bb.*nrepl" || (bb nrepl > /tmp/nrepl.log 2>&1 &; sleep 3)

# 1. Ensure REPL is running and fast-dev is loaded
clj-nrepl-eval -p 7889 "(fast-dev)"

# 2. Run the specific test namespace you're working on
clj-nrepl-eval -p 7889 "(k/run 'plan.feature-test)"

# 3. After making changes, reload and run again
clj-nrepl-eval -p 7889 "(dev/reload)"
clj-nrepl-eval -p 7889 "(k/run 'plan.feature-test)"

# 4. Before committing, run all tests
clj-nrepl-eval -p 7889 "(k/run)"

# 5. Run the full CI pipeline
bb ci
```

## Formatting Rules (cljstyle)

- List indent: 1 space, namespace indent: 1 space
- Inline comments: `; ` prefix (space after semicolon)
- Run `bb fmt` to auto-fix

## Where to Put New Code

| Type of code | Location |
|--------------|----------|
| New namespace | `src/plan/feature.clj` |
| CLI commands | `src/plan/main.clj` |
| Database helpers | `src/plan/db.clj` |
| Configuration | `src/plan/config.clj` |
| Tests | `test/plan/feature_test.clj` |

## Project Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PLAN_DB_PATH` | Override database file path | From config file |

### Configuration File

Location: `~/.config/plan/config.json`

```json
{
  "db-path": "~/.local/share/plan/plan.db"
}
```

## Adding Dependencies

1. **Get approval first** - Never add dependencies without explicit user approval
2. **Add to deps.edn** - Place in the appropriate section (:deps or :aliases)
3. **Update lock files** - Run `clojure -P` to download and cache dependencies
4. **Document usage** - Add a brief comment explaining why the dependency is needed
5. **Run tests** - Ensure `bb ci` passes after adding

Example:
```clojure
;; deps.edn
{:deps
 {;; ... existing deps ...
  my.library/awesome-lib {:mvn/version "1.0.0"} ; For feature X
  }}
```

## Database Schema

The database schema is defined in `src/plan/main.clj`:

- **Tables**: `plans`, `tasks`, `facts`
- **FTS5 Tables**: `plans_fts`, `tasks_fts`, `facts_fts` (full-text search)
- **Triggers**: Automatic FTS index maintenance on insert/update/delete

When modifying schema:
1. Update the schema definitions in `main.clj`
2. Add migration logic if needed for existing databases
3. Update tests to verify new schema elements
4. Document changes in commit messages

## Resources

- [Clojure Community Style Guide](https://guide.clojure.style) - Comprehensive style conventions
- [Clojure Style Guide (GitHub)](https://github.com/bbatsov/clojure-style-guide) - Original by Bozhidar Batsov
- [clojure-skills](https://github.com/ivanwilliammd/clojure-skills) - Searchable Clojure skill library
