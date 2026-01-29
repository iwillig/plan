(ns plan.db
  (:require
   [clojure.string :as str]
   [honey.sql :as sql]
   [malli.core :as m]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(set! *warn-on-reflection* true)

;; Malli schemas for database operations
(def Connection :any)

(def HoneySQL :any)

(def QueryResult
  "Schema for query results from next.jdbc"
  [:sequential :map])

(def SingleResult
  "Schema for single row result"
  [:maybe :map])

(def QueryOpts :map)

(def SearchResult
  "Schema for FTS search results with rank"
  [:sequential
   [:map
    [:id :int]
    [:rank :double]]])

(def HighlightResult
  "Schema for FTS highlight results"
  [:sequential
   [:map
    [:id :int]
    [:description_highlight {:optional true} :string]
    [:content_highlight {:optional true} :string]]])

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
   and executes with jdbc/execute!.
   Returns unqualified maps (e.g., :id instead of :plans/id)."
  ([conn honeysql]
   (jdbc/execute! conn (sql/format honeysql) {:builder-fn rs/as-unqualified-maps}))
  ([conn honeysql opts]
   (jdbc/execute! conn (sql/format honeysql) (merge {:builder-fn rs/as-unqualified-maps} opts))))

(defn execute-one!
  "Execute a HoneySQL query and return a single result.
   Takes a connection and a HoneySQL map, formats it,
   and executes with jdbc/execute-one!.
   Returns unqualified maps (e.g., :id instead of :plans/id)."
  ([conn honeysql]
   (jdbc/execute-one! conn (sql/format honeysql) {:builder-fn rs/as-unqualified-maps}))
  ([conn honeysql opts]
   (jdbc/execute-one! conn (sql/format honeysql) (merge {:builder-fn rs/as-unqualified-maps} opts))))

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
                         "ORDER BY rank") fts-query]
                   {:builder-fn rs/as-unqualified-maps})))

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
                         "ORDER BY rank") fts-query]
                   {:builder-fn rs/as-unqualified-maps})))

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
                         "ORDER BY rank") fts-query]
                   {:builder-fn rs/as-unqualified-maps})))

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
                    fts-query]
                   {:builder-fn rs/as-unqualified-maps})))

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
                    fts-query]
                   {:builder-fn rs/as-unqualified-maps})))

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
                    fts-query]
                   {:builder-fn rs/as-unqualified-maps})))

;; Malli function schemas - register only if not already registered
(try
  (m/=> with-connection [:=> [:cat :string [:=> [:cat :any] :any]] :any])
  (m/=> execute! [:=> [:cat :any :any :any] [:sequential :map]])
  (m/=> execute-one! [:=> [:cat :any :any :any] [:maybe :map]])
  (m/=> format-fts-query [:=> [:cat :string] :string])
  (m/=> search-plans [:=> [:cat :any :string] SearchResult])
  (m/=> search-tasks [:=> [:cat :any :string] SearchResult])
  (m/=> search-facts [:=> [:cat :any :string] SearchResult])
  (m/=> highlight-plans [:=> [:cat :any :string] HighlightResult])
  (m/=> highlight-tasks [:=> [:cat :any :string] HighlightResult])
  (m/=> highlight-facts [:=> [:cat :any :string] HighlightResult])
  (catch Exception _ nil))
