-- :name fact-get-by-id :? :1
-- :doc Get a fact by ID
SELECT * FROM facts WHERE id = :id

-- :name fact-get-by-plan :? :*
-- :doc Get all facts for a plan
SELECT * FROM facts WHERE plan_id = :plan-id ORDER BY name

-- :name fact-get-by-name :? :*
-- :doc Get a fact by plan ID and name
SELECT * FROM facts WHERE plan_id = :plan-id AND name = :name

-- :name fact-create :! :1
-- :doc Create a new fact
INSERT INTO facts (plan_id, name, description, content)
VALUES (:plan-id, :name, :description, :content)
RETURNING *

-- :name fact-update :! :1
-- :doc Update a fact by ID
UPDATE facts
SET name = :name,
    description = :description,
    content = :content,
    updated_at = CURRENT_TIMESTAMP
WHERE id = :id
RETURNING *

-- :name fact-delete :! :n
-- :doc Delete a fact by ID
DELETE FROM facts WHERE id = :id

-- :name fact-delete-by-plan :! :n
-- :doc Delete all facts for a plan
DELETE FROM facts WHERE plan_id = :plan-id

-- :name fact-delete-orphans-query :? :*
-- :doc Get IDs of facts to delete (orphans not in the given names list)
SELECT id, name FROM facts WHERE plan_id = :plan-id

-- :name fact-get-all :? :*
-- :doc Get all facts
SELECT * FROM facts ORDER BY name

-- :name fact-search :? :*
-- :doc Search facts using FTS5
SELECT f.* FROM facts f
JOIN facts_fts fts ON f.id = fts.rowid
WHERE facts_fts MATCH :query
ORDER BY rank
