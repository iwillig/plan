-- :name lesson-create :! :1
-- :doc Create a new lesson
INSERT INTO lessons (plan_id, task_id, lesson_type, trigger_condition, lesson_content, confidence, times_validated)
VALUES (:plan-id, :task-id, :lesson-type, :trigger-condition, :lesson-content, :confidence, 0)
RETURNING *

-- :name lesson-get-by-id :? :1
-- :doc Get a lesson by ID
SELECT * FROM lessons WHERE id = :id

-- :name lesson-get-all :? :*
-- :doc Get all lessons ordered by confidence desc, then created_at desc
SELECT * FROM lessons ORDER BY confidence DESC, created_at DESC

-- :name lesson-get-by-plan :? :*
-- :doc Get lessons for a plan ordered by confidence desc
SELECT * FROM lessons WHERE plan_id = :plan-id ORDER BY confidence DESC

-- :name lesson-get-by-task :? :*
-- :doc Get lessons for a task
SELECT * FROM lessons WHERE task_id = :task-id ORDER BY confidence DESC

-- :name lesson-validate :! :n
-- :doc Increase confidence and times_validated for a lesson
UPDATE lessons
SET confidence = min(1.0, confidence + :delta),
    times_validated = times_validated + 1
WHERE id = :id

-- :name lesson-invalidate :! :n
-- :doc Decrease confidence for a lesson
UPDATE lessons
SET confidence = max(0.0, confidence - :delta)
WHERE id = :id

-- :name lesson-delete :! :n
-- :doc Delete a lesson by ID
DELETE FROM lessons WHERE id = :id

-- :name lesson-delete-by-plan :! :n
-- :doc Delete all lessons for a plan
DELETE FROM lessons WHERE plan_id = :plan-id

-- :name lesson-delete-by-task :! :n
-- :doc Delete all lessons for a task
DELETE FROM lessons WHERE task_id = :task-id

-- :name lesson-search :? :*
-- :doc Search lessons using FTS5
SELECT l.* FROM lessons l
JOIN lessons_fts fts ON l.id = fts.rowid
WHERE lessons_fts MATCH :query
ORDER BY rank
