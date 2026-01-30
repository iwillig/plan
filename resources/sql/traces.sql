-- :name trace-create :! :1
-- :doc Create a new trace entry
INSERT INTO traces (plan_id, task_id, trace_type, sequence_num, content, metadata)
VALUES (:plan-id, :task-id, :trace-type, :sequence-num, :content, :metadata)
RETURNING *

-- :name trace-get-next-sequence :? :1
-- :doc Get the max sequence number for a plan
SELECT COALESCE(MAX(sequence_num), 0) as seq FROM traces WHERE plan_id = :plan-id

-- :name trace-get-by-plan :? :*
-- :doc Get all traces for a plan, ordered by sequence
SELECT * FROM traces WHERE plan_id = :plan-id ORDER BY sequence_num

-- :name trace-get-by-task :? :*
-- :doc Get all traces for a task, ordered by sequence
SELECT * FROM traces WHERE task_id = :task-id ORDER BY sequence_num

-- :name trace-delete-by-plan :! :n
-- :doc Delete all traces for a plan
DELETE FROM traces WHERE plan_id = :plan-id

-- :name trace-delete-by-task :! :n
-- :doc Delete all traces for a task
DELETE FROM traces WHERE task_id = :task-id
