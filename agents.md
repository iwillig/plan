# LLM Agent Instructions - Plan

**Version:** 1.0
**Last Updated:** 2025-01-19
**Project:** plan
**Shell:** fish (Friendly Interactive Shell)

## Quick Start - 5 Critical Rules

1. **Run `bb ci` before completing any task** - runs formatting, linting, and tests
2. **Use `clj-nrepl-eval` when REPL is running** - never spawn new processes
3. **Add type annotations** using `typed.clojure` for all new code
4. **Follow Component pattern** for stateful resources
5. **Never use `conn-from-db` in production** - pass connections, not snapshots

See detailed sections below for complete guidelines.

## Critical Rules

### MUST DO

1. **Always run `bb ci` before completing any task** - runs
   formatting, linting, and tests
2. **Add type annotations** using `typed.clojure` for all new records and public functions
3. **Follow the Component pattern** for any new system components
4. **Write tests** for new functionality in the corresponding `test/csb/` directory
5. **Use clj-nrepl-eval when REPL is running** - never spawn new Clojure processes with `clojure` command

### MUST NOT

1. **Never commit code that fails `bb ci`**
2. **Never use `def` for mutable state** - use atoms within components if needed
3. **Never add dependencies without explicit user approval**
4. **Never bypass Component lifecycle** - all stateful resources must be components
5. **Never use `println` for logging** - use `mulog` instead
6. **Never use emojis** - in code, comments, commit messages, documentation, or any output
7. **Never use `clojure -M:...` command when REPL is running** - use clj-nrepl-eval instead

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
(require '[csb.api.pages.facts :as facts])
(facts/render-page)
EOF

# Multi-line with backslash continuation
clj-nrepl-eval -p 7889 \
  "(do (require 'csb.main :reload-all) \
       (csb.main/restart-system))"

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
clj-nrepl-eval -p 7889 "(require '[csb.api.pages.facts :as facts] :reload)"

# Test functions
clj-nrepl-eval -p 7889 "(facts/render-page)"

# Run tests
clj-nrepl-eval -p 7889 "(require 'kaocha.repl) (kaocha.repl/run 'csb.models-test)"

# Check server state
clj-nrepl-eval -p 7889 "(-> @csb.main/system :server)"

# Multi-line code block with heredoc (preferred for complex code)
clj-nrepl-eval -p 7889 <<'EOF'
(do
  (require 'csb.main :reload-all)
  (csb.main/restart-system))
EOF
```

### DO NOT DO THIS (when REPL is running):

```fish
# ✗ WRONG - spawns new JVM process, doesn't use running REPL
clojure -M:jvm-base:dev -e "(some-function)"

# ✗ WRONG - runs separate process, not connected to running system
clojure -M:jvm-base:dev:test --focus csb.models-test
```

## Code Style Guidelines

### Namespace Declaration

Use sorted, aligned requires with single-space indent:

```clojure
(ns csb.example
  (:require
   [com.stuartsierra.component :as c]
   [typed.clojure :as t])
  (:import
   (java.io
    PushbackReader)))

(set! *warn-on-reflection* true)
```

### Type Annotations

Annotate BEFORE the definition:

```clojure
;; For records
(t/ann-record Database [db-path :- t/Str
                        connection :- t/Any])
(defrecord Database [db-path connection] ...)

;; For functions
(t/ann process-data [t/Str t/Int :-> t/Bool])
(defn process-data [name count] (pos? count))

;; For side-effecting functions (return nil)
(t/ann log-event! [t/Str :-> nil])
(defn log-event! [msg] (u/log ::event :message msg) nil)

;; Type aliases
(t/defalias Result (t/All [a] (t/U a Failure)))
```

### Component Pattern (REQUIRED for stateful resources)

```clojure
(t/ann-record ExampleComponent [config :- t/Str
                                connection :- (t/Option SomeType)])
(defrecord ExampleComponent [config connection]
  c/Lifecycle
  (start [this] (assoc this :connection (create-connection config)))
  (stop [this]
    (when connection (close-connection connection))
    (assoc this :connection nil)))

(t/ann new-example-component [t/Str :-> ExampleComponent])
(defn new-example-component [config]
  (map->ExampleComponent {:config config}))
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


## Test Patterns

```clojure
(ns csb.feature-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [typed.clojure :as t]))

(deftest feature-works
  (testing "specific behavior" (is (= expected actual))))

;; Type checking test (include in every namespace test)
(deftest type-check
  (testing "types are valid" (is (t/check-ns-clj 'csb.feature))))
```

## Formatting Rules (cljstyle)

- List indent: 1 space, namespace indent: 1 space
- Inline comments: `; ` prefix (space after semicolon)
- Run `bb fmt` to auto-fix

## Common Type Annotations

| Type | Meaning |
|------|---------|
| `t/Int` | Integer |
| `t/Str` | String |
| `t/Bool` | Boolean |
| `t/Any` | Any type (escape hatch) |
| `[A :-> B]` | Function A to B |
| `(t/Vec X)` | Vector of X |
| `(t/Map K V)` | Map from K to V |
| `(t/Option X)` | X or nil |

## Where to Put New Code

| Type of code | Location |
|--------------|----------|
| New component | `src/csb/components/name.clj` |
| Business logic | `src/csb/controllers.clj` |
| Data shapes/types | `src/csb/models.clj` |
| Routes | `src/csb/routes.clj` |
| Type annotations for libs | `src/csb/annotations/` |

## Adding Dependencies

1. **Get approval
