(ns plan.db
  (:require
   [clojure.string :as str]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]))

(defn with-connection
  "Execute a function with a JDBC connection to the database at db-path.
   Opens a connection, calls (f conn), then closes the connection."
  [db-path f]
  (let [jdbc-url (str "jdbc:sqlite:" db-path)]
    (with-open [conn (jdbc/get-connection jdbc-url)]
      (f conn))))

(defn execute!
  "Execute a HoneySQL query against a JDBC connection.
   Takes a connection and a HoneySQL map, formats it,
   and executes with jdbc/execute!."
  ([conn honeysql]
   (jdbc/execute! conn (sql/format honeysql)))
  ([conn honeysql opts]
   (jdbc/execute! conn (sql/format honeysql) opts)))

(defn execute-one!
  "Execute a HoneySQL query and return a single result.
   Takes a connection and a HoneySQL map, formats it,
   and executes with jdbc/execute-one!."
  ([conn honeysql]
   (jdbc/execute-one! conn (sql/format honeysql)))
  ([conn honeysql opts]
   (jdbc/execute-one! conn (sql/format honeysql) opts)))

;; Full-text search functions

(defn- format-fts-query
  "Format a search query for FTS5 with prefix matching.
   Adds * to each term for prefix matching."
  [query]
  (->> (str/split query #"\s+")
       (map #(str % "*"))
       (str/join " ")))

(defn search-plans
  "Search plans using FTS5 with BM25 ranking.
   Returns results ordered by relevance (best matches first).
   Supports prefix matching (e.g., 'plan' matches 'planning')."
  [conn query]
  (let [fts-query (format-fts-query query)]
    (jdbc/execute! conn
                   [(str "SELECT p.*, rank FROM plans p "
                         "JOIN plans_fts fts ON p.id = fts.rowid "
                         "WHERE plans_fts MATCH ? "
                         "ORDER BY rank") fts-query])))

(defn search-tasks
  "Search tasks using FTS5 with BM25 ranking.
   Returns results ordered by relevance (best matches first).
   Supports prefix matching."
  [conn query]
  (let [fts-query (format-fts-query query)]
    (jdbc/execute! conn
                   [(str "SELECT t.*, rank FROM tasks t "
                         "JOIN tasks_fts fts ON t.id = fts.rowid "
                         "WHERE tasks_fts MATCH ? "
                         "ORDER BY rank") fts-query])))

(defn search-facts
  "Search facts using FTS5 with BM25 ranking.
   Returns results ordered by relevance (best matches first).
   Supports prefix matching."
  [conn query]
  (let [fts-query (format-fts-query query)]
    (jdbc/execute! conn
                   [(str "SELECT f.*, rank FROM facts f "
                         "JOIN facts_fts fts ON f.id = fts.rowid "
                         "WHERE facts_fts MATCH ? "
                         "ORDER BY rank") fts-query])))

;; Highlight functions for search result snippets

(defn highlight-plans
  "Return highlighted snippets for plan search results.
   Shows context around matched terms with <b></b> markup."
  [conn query]
  (let [fts-query (format-fts-query query)]
    (jdbc/execute! conn
                   [(str "SELECT p.id, "
                         "highlight(plans_fts, 0, '<b>', '</b>') as description_highlight, "
                         "highlight(plans_fts, 1, '<b>', '</b>') as content_highlight "
                         "FROM plans p "
                         "JOIN plans_fts ON p.id = plans_fts.rowid "
                         "WHERE plans_fts MATCH ? "
                         "ORDER BY rank")
                    fts-query])))

(defn highlight-tasks
  "Return highlighted snippets for task search results."
  [conn query]
  (let [fts-query (format-fts-query query)]
    (jdbc/execute! conn
                   [(str "SELECT t.id, "
                         "highlight(tasks_fts, 0, '<b>', '</b>') as description_highlight, "
                         "highlight(tasks_fts, 1, '<b>', '</b>') as content_highlight "
                         "FROM tasks t "
                         "JOIN tasks_fts ON t.id = tasks_fts.rowid "
                         "WHERE tasks_fts MATCH ? "
                         "ORDER BY rank")
                    fts-query])))

(defn highlight-facts
  "Return highlighted snippets for fact search results."
  [conn query]
  (let [fts-query (format-fts-query query)]
    (jdbc/execute! conn
                   [(str "SELECT f.id, "
                         "highlight(facts_fts, 0, '<b>', '</b>') as description_highlight, "
                         "highlight(facts_fts, 1, '<b>', '</b>') as content_highlight "
                         "FROM facts f "
                         "JOIN facts_fts ON f.id = facts_fts.rowid "
                         "WHERE facts_fts MATCH ? "
                         "ORDER BY rank")
                    fts-query])))
