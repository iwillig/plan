-- :name fts-search-plans
-- :doc Search plans using FTS5 with BM25 ranking
-- :result :*
SELECT p.*, rank
FROM plans p
JOIN plans_fts fts ON p.id = fts.rowid
WHERE plans_fts MATCH :query
ORDER BY rank

-- :name fts-search-tasks
-- :doc Search tasks using FTS5 with BM25 ranking
-- :result :*
SELECT t.*, rank
FROM tasks t
JOIN tasks_fts fts ON t.id = fts.rowid
WHERE tasks_fts MATCH :query
ORDER BY rank

-- :name fts-search-facts
-- :doc Search facts using FTS5 with BM25 ranking
-- :result :*
SELECT f.*, rank
FROM facts f
JOIN facts_fts fts ON f.id = fts.rowid
WHERE facts_fts MATCH :query
ORDER BY rank

-- :name fts-highlight-plans
-- :doc Return highlighted snippets for plan search results
-- :result :*
SELECT p.id,
       highlight(plans_fts, 0, :start-mark, :end-mark) as description_highlight,
       highlight(plans_fts, 1, :start-mark, :end-mark) as content_highlight
FROM plans p
JOIN plans_fts ON p.id = plans_fts.rowid
WHERE plans_fts MATCH :query
ORDER BY rank

-- :name fts-highlight-tasks
-- :doc Return highlighted snippets for task search results
-- :result :*
SELECT t.id,
       highlight(tasks_fts, 0, :start-mark, :end-mark) as description_highlight,
       highlight(tasks_fts, 1, :start-mark, :end-mark) as content_highlight
FROM tasks t
JOIN tasks_fts ON t.id = tasks_fts.rowid
WHERE tasks_fts MATCH :query
ORDER BY rank

-- :name fts-highlight-facts
-- :doc Return highlighted snippets for fact search results
-- :result :*
SELECT f.id,
       highlight(facts_fts, 0, :start-mark, :end-mark) as description_highlight,
       highlight(facts_fts, 1, :start-mark, :end-mark) as content_highlight
FROM facts f
JOIN facts_fts ON f.id = facts_fts.rowid
WHERE facts_fts MATCH :query
ORDER BY rank
