# LLM Agent Instructions - Plan

**Version:** 1.1
**Last Updated:** 2025-01-29
**Project:** plan
**Shell:** fish (Friendly Interactive Shell)

## Quick Start - 5 Critical Rules

1. **Run `bb ci` before completing any task** - runs formatting, linting, and tests
2. **Use `clj-nrepl-eval` when REPL is running** - never spawn new processes
3. **Write docstrings** for all public functions
4. **Pass connections explicitly** - use `db/with-connection` pattern
5. **Never use `conn-from-db` in production** - pass connections, not snapshots

See detailed sections below for complete guidelines.

## Critical Rules

### MUST DO

1. **Always run `bb ci` before completing any task** - runs
   formatting, linting, and tests
2. **Write docstrings** for all new records and public functions
3. **Write tests** for new functionality in the corresponding `test/plan/` directory
4. **Use clj-nrepl-eval when REPL is running** - never spawn new Clojure processes with `clojure` command
5. **Use `db/with-connection` pattern** for database access

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

**Use `clj-nrepl-eval` with Kaocha when:**

- REPL is running (preferred for development)
- Need interactive test debugging
- Want faster feedback without JVM startup

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

# Run tests
clj-nrepl-eval -p 7889 "(require 'kaocha.repl) (kaocha.repl/run 'plan.main-test)"

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
```

## Code Style Guidelines

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
