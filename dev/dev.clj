(ns dev
  (:require
   [clj-kondo.core :as clj-kondo]
   [clj-reload.core :as reload]
   [clojure.repl :refer [doc]]
   [io.aviso.repl :as repl]
   [kaocha.repl :as k]))

(repl/install-pretty-exceptions)

(reload/init
 {:dirs ["src" "dev" "test"]})

(defn reload
  "Reloads and compiles the Clojure namespaces."
  []
  (reload/reload))

(defn lint
  "Lint the entire project (src and test directories)."
  []
  (-> (clj-kondo/run! {:lint ["src" "test" "dev"]})
      (clj-kondo/print!)))

(println (doc k/run))
(println (doc reload))
(println (doc lint))
