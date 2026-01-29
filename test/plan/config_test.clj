(ns plan.config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [plan.config :as config]))

(deftest load-config-test
  (testing "returns default config when file doesn't exist"
    (with-redefs [config/config-file "/nonexistent/config.json"]
      (is (= config/default-config (config/load-config)))))

  (testing "loads config from file when it exists"
    (let [temp-file (java.io.File/createTempFile "config" ".json")]
      (.deleteOnExit temp-file)
      (spit temp-file "{\"db-path\":\"/custom/path.db\"}")
      (with-redefs [config/config-file (.getAbsolutePath temp-file)]
        (is (= "/custom/path.db" (:db-path (config/load-config)))))
      (.delete temp-file)))

  (testing "merges with defaults for missing keys"
    (let [temp-file (java.io.File/createTempFile "config" ".json")]
      (.deleteOnExit temp-file)
      (spit temp-file "{}")
      (with-redefs [config/config-file (.getAbsolutePath temp-file)]
        (is (= (:db-path config/default-config) (:db-path (config/load-config)))))
      (.delete temp-file))))

(deftest db-path-test
  (testing "uses config file when no environment variable set"
    (with-redefs [config/load-config (constantly {:db-path "/config/file.db"})]
      (is (= "/config/file.db" (config/db-path)))))

  (testing "expands home directory in path"
    (with-redefs [config/load-config (constantly {:db-path "~/test.db"})]
      (let [^String path (config/db-path)]
        (is (.startsWith path (System/getProperty "user.home")))
        (is (.endsWith path "/test.db"))))))

(deftest expand-home-test
  (testing "expands ~ to user home"
    (let [^String result (#'config/expand-home "~/test.db")]
      (is (.startsWith result (System/getProperty "user.home")))
      (is (.endsWith result "/test.db"))))

  (testing "leaves absolute paths unchanged"
    (is (= "/absolute/path.db" (#'config/expand-home "/absolute/path.db"))))

  (testing "leaves relative paths unchanged"
    (is (= "relative/path.db" (#'config/expand-home "relative/path.db")))))
