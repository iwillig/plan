-- :name task-create :! :1
-- :doc Create a new task
INSERT INTO tasks (plan_id, name, description, content, parent_id, completed, status, priority, acceptance_criteria)
VALUES (:plan-id, :name, :description, :content, :parent-id, :completed, :status, :priority, :acceptance-criteria)
RETURNING *

-- :name task-get-by-id :? :1
-- :doc Get a task by ID
SELECT * FROM tasks WHERE id = :id

-- :name task-get-by-plan :? :*
-- :doc Get all tasks for a plan, ordered by created_at desc, then id desc
SELECT * FROM tasks WHERE plan_id = :plan-id ORDER BY created_at DESC, id DESC

-- :name task-get-by-plan-and-name :? :1
-- :doc Get a task by plan_id and name
SELECT * FROM tasks WHERE plan_id = :plan-id AND name = :name

-- :name task-get-children :? :*
-- :doc Get all child tasks for a parent task
SELECT * FROM tasks WHERE parent_id = :parent-id ORDER BY created_at DESC, id DESC

-- :name task-get-all :? :*
-- :doc Get all tasks, ordered by created_at desc, then id desc
SELECT * FROM tasks ORDER BY created_at DESC, id DESC

-- :name task-update :! :1
-- :doc Update a task by ID
UPDATE tasks
SET name = :name,
    description = :description,
    content = :content,
    plan_id = :plan-id,
    parent_id = :parent-id,
    completed = :completed,
    status = :status,
    priority = :priority,
    acceptance_criteria = :acceptance-criteria,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :id
RETURNING *

-- :name task-delete :! :n
-- :doc Delete a task by ID
DELETE FROM tasks WHERE id = :id

-- :name task-delete-by-plan :! :n
-- :doc Delete all tasks for a plan
DELETE FROM tasks WHERE plan_id = :plan-id

-- :name task-upsert :! :n
-- :doc Insert or update a task by plan_id and name (ON CONFLICT)
INSERT INTO tasks (plan_id, name, description, content, completed, parent_id, status, priority, acceptance_criteria)
VALUES (:plan-id, :name, :description, :content, :completed, :parent-id, :status, :priority, :acceptance-criteria)
ON CONFLICT(plan_id, name) DO UPDATE SET
    description = excluded.description,
    content = excluded.content,
    completed = excluded.completed,
    parent_id = excluded.parent_id,
    status = excluded.status,
    priority = excluded.priority,
    acceptance_criteria = excluded.acceptance_criteria

-- :name task-delete-orphans-query :? :*
-- :doc Get all tasks for a plan to filter orphans
SELECT id, name FROM tasks WHERE plan_id = :plan-id

-- :name task-add-dependency :! :n
-- :doc Add a dependency between tasks (with conflict handling)
INSERT INTO task_dependencies (task_id, blocks_task_id, dependency_type)
VALUES (:task-id, :blocks-task-id, :dependency-type)
ON CONFLICT(task_id, blocks_task_id) DO NOTHING

-- :name task-remove-dependency :! :n
-- :doc Remove a dependency between tasks
DELETE FROM task_dependencies
WHERE task_id = :task-id AND blocks_task_id = :blocks-task-id

-- :name task-get-blocking :? :*
-- :doc Get tasks that are blocking the given task
SELECT t.* FROM tasks t
JOIN task_dependencies d ON t.id = d.task_id
WHERE d.blocks_task_id = :task-id

-- :name task-get-blocked :? :*
-- :doc Get tasks blocked by the given task
SELECT t.* FROM tasks t
JOIN task_dependencies d ON t.id = d.blocks_task_id
WHERE d.task_id = :task-id

-- :name task-get-dependencies-for-plan :? :*
-- :doc Get all dependencies for tasks in a plan
SELECT d.* FROM task_dependencies d
JOIN tasks t ON t.id = d.task_id
WHERE t.plan_id = :plan-id

-- :name task-delete-dependencies-for-task :! :n
-- :doc Delete all dependencies involving a task (both directions)
DELETE FROM task_dependencies
WHERE task_id = :task-id OR blocks_task_id = :task-id

-- :name task-set-status :! :1
-- :doc Set a task's status and update status_changed_at
UPDATE tasks
SET status = :status,
    status_changed_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :id
RETURNING *

-- :name task-get-ready :? :*
-- :doc Get ready tasks for a plan (pending status, no incomplete blockers)
-- Uses a raw query due to complex EXISTS subquery
SELECT t.* FROM tasks t
WHERE t.plan_id = :plan-id
AND t.status = 'pending'
AND NOT EXISTS (
    SELECT 1 FROM task_dependencies d
    JOIN tasks blocker ON blocker.id = d.task_id
    WHERE d.blocks_task_id = t.id
    AND blocker.status NOT IN ('completed', 'skipped')
)
ORDER BY t.priority ASC, t.id ASC
