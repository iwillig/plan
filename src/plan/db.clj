(ns plan.db
  (:require
   [clojure.string :as str]
   [honey.sql :as sql]
   [hugsql.adapter.next-jdbc :as next-jdbc-adapter]
   [hugsql.core :as hugsql]
   [malli.core :as m]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]))

(set! *warn-on-reflection* true)

;; Load FTS SQL queries from external file
(hugsql/def-db-fns "sql/fts.sql"
  {:adapter (next-jdbc-adapter/hugsql-adapter-next-jdbc {:builder-fn rs/as-unqualified-maps})})

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

(def ^:private default-highlight-marks {:start-mark "<b>" :end-mark "</b>"})

(defn search-plans
  "Search plans using FTS5 with BM25 ranking.
   Returns results ordered by relevance (best matches first).
   Supports prefix matching (e.g., 'plan' matches 'planning')."
  [conn query]
  (fts-search-plans conn {:query (format-fts-query query)}))

(defn search-tasks
  "Search tasks using FTS5 with BM25 ranking.
   Returns results ordered by relevance (best matches first).
   Supports prefix matching."
  [conn query]
  (fts-search-tasks conn {:query (format-fts-query query)}))

(defn search-facts
  "Search facts using FTS5 with BM25 ranking.
   Returns results ordered by relevance (best matches first).
   Supports prefix matching."
  [conn query]
  (fts-search-facts conn {:query (format-fts-query query)}))

;; Highlight functions for search result snippets

(defn highlight-plans
  "Return highlighted snippets for plan search results.
   Shows context around matched terms with <b></b> markup."
  [conn query]
  (fts-highlight-plans conn (assoc default-highlight-marks :query (format-fts-query query))))

(defn highlight-tasks
  "Return highlighted snippets for task search results."
  [conn query]
  (fts-highlight-tasks conn (assoc default-highlight-marks :query (format-fts-query query))))

(defn highlight-facts
  "Return highlighted snippets for fact search results."
  [conn query]
  (fts-highlight-facts conn (assoc default-highlight-marks :query (format-fts-query query))))

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
