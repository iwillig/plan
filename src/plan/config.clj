(ns plan.config
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [malli.core :as m]))

(set! *warn-on-reflection* true)

;; Malli schemas for configuration
(def Config
  "Schema for application configuration"
  [:map
   [:db-path :string]])

(def ConfigFile
  "Schema for config file content"
  [:map
   [:db-path {:optional true} :string]])

(def default-config
  {:db-path "~/.local/share/plan/plan.db"})

(def config-dir
  "The configuration directory path"
  "~/.config/plan")

(def config-file
  "The configuration file path"
  (str config-dir "/config.json"))

(defn- expand-home
  "Expand ~ in a path to the user's home directory"
  [^String path]
  (if (.startsWith path "~/")
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn load-config
  "Load configuration from the config file.
   Returns a map with :db-path and other settings.
   If the config file doesn't exist, returns default-config."
  []
  (let [config-path (expand-home config-file)
        ^java.io.File config-file-obj (io/file config-path)]
    (if (.exists config-file-obj)
      (try
        (let [config (json/read-str (slurp config-path) :key-fn keyword)]
          (merge default-config config))
        (catch Exception e
          (println (str "Warning: Could not read config file " config-path ": " (.getMessage e)))
          default-config))
      default-config)))

(defn db-path
  "Get the database path from config.
   Environment variable PLAN_DB_PATH overrides config file."
  []
  (or (System/getenv "PLAN_DB_PATH")
      (-> (load-config) :db-path expand-home)))

(defn ensure-config-dir
  "Ensure the config directory exists. Creates it if necessary."
  []
  (let [^java.io.File dir (io/file (expand-home config-dir))]
    (when-not (.exists dir)
      (.mkdirs dir)))
  nil)

(defn save-config
  "Save configuration to the config file.
   Creates the config directory if it doesn't exist."
  [config]
  (ensure-config-dir)
  (let [config-path (expand-home config-file)]
    (spit config-path (json/write-str config :escape-slash false)))
  nil)

;; Malli function schemas - register only if not already registered
(try
  (m/=> load-config [:=> [:cat] Config])
  (m/=> db-path [:=> [:cat] :string])
  (m/=> ensure-config-dir [:=> [:cat] nil])
  (m/=> save-config [:=> [:cat ConfigFile] nil])
  (catch Exception _ nil))
