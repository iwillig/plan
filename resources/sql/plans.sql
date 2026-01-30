-- :name plan-create :! :1
-- :doc Create a new plan
INSERT INTO plans (name, description, content, completed)
VALUES (:name, :description, :content, :completed)
RETURNING *

-- :name plan-get-by-id :? :1
-- :doc Get a plan by ID
SELECT * FROM plans WHERE id = :id

-- :name plan-get-by-name :? :1
-- :doc Get a plan by name
SELECT * FROM plans WHERE name = :name

-- :name plan-get-all :? :*
-- :doc Get all plans ordered by created_at desc, then id desc
SELECT * FROM plans ORDER BY created_at DESC, id DESC

-- :name plan-update :! :1
-- :doc Update a plan by ID
UPDATE plans
SET name = :name,
    description = :description,
    content = :content,
    completed = :completed,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :id
RETURNING *

-- :name plan-delete :! :n
-- :doc Delete a plan by ID
DELETE FROM plans WHERE id = :id

-- :name plan-upsert :! :n
-- :doc Insert or update a plan by name (ON CONFLICT)
INSERT INTO plans (name, description, content, completed)
VALUES (:name, :description, :content, :completed)
ON CONFLICT(name) DO UPDATE SET
    description = excluded.description,
    content = excluded.content,
    completed = excluded.completed
