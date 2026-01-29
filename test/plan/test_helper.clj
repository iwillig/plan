(ns plan.test-helper
  (:require
   [next.jdbc :as jdbc]))

(def ^:dynamic *conn* nil)

(defn db-fixture
  "Creates an in-memory SQLite connection for each test.
   Binds the connection to *conn* so tests can access it."
  [test-fn]
  (with-open [conn (jdbc/get-connection (jdbc/get-datasource {:dbtype "sqlite" :dbname ":memory:"}))]
    (binding [*conn* conn]
      (test-fn))))
