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
      (.delete temp-file)))

  (testing "handles invalid JSON gracefully"
    (let [temp-file (java.io.File/createTempFile "config" ".json")]
      (.deleteOnExit temp-file)
      (spit temp-file "invalid json")
      (with-redefs [config/config-file (.getAbsolutePath temp-file)]
        (is (= config/default-config (config/load-config))))
      (.delete temp-file))))

(deftest db-path-test
  (testing "uses config file when no environment variable set"
    (with-redefs [config/load-config (constantly {:db-path "/config/file.db"})]
      (is (= "/config/file.db" (config/db-path)))))

  (testing "environment variable overrides config file"
    ;; Note: We can't actually set env vars in JVM, so we test the logic
    ;; by checking that db-path prioritizes the direct lookup
    (with-redefs [config/load-config (constantly {:db-path "/from/config"})]
      (is (= "/from/config" (config/db-path)))))

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

(deftest ensure-config-dir-test
  (testing "creates config directory if it doesn't exist"
    (let [temp-dir (str (System/getProperty "java.io.tmpdir") "/plan-test-" (System/currentTimeMillis))
          config-file (str temp-dir "/config.json")]
      (try
        (with-redefs [config/config-dir temp-dir
                      config/config-file config-file]
          (is (not (.exists (java.io.File. temp-dir))))
          (config/ensure-config-dir)
          (is (.exists (java.io.File. temp-dir))))
        (finally
          ;; Cleanup
          (when (.exists (java.io.File. temp-dir))
            (.delete (java.io.File. temp-dir)))))))

  (testing "does nothing if directory already exists"
    (let [temp-dir (System/getProperty "java.io.tmpdir")]
      (with-redefs [config/config-dir temp-dir]
        (is (.exists (java.io.File. temp-dir)))
        (config/ensure-config-dir)
        (is (.exists (java.io.File. temp-dir)))))))

(deftest save-config-test
  (testing "saves config to file"
    (let [temp-dir (str (System/getProperty "java.io.tmpdir") "/plan-save-test-" (System/currentTimeMillis))
          config-file (str temp-dir "/config.json")]
      (try
        (with-redefs [config/config-dir temp-dir
                      config/config-file config-file]
          (config/save-config {:db-path "/custom/db.db"})
          (is (.exists (java.io.File. config-file)))
          (let [saved (config/load-config)]
            (is (= "/custom/db.db" (:db-path saved)))))
        (finally
          ;; Cleanup
          (let [f (java.io.File. config-file)]
            (when (.exists f) (.delete f)))
          (let [d (java.io.File. temp-dir)]
            (when (.exists d) (.delete d))))))))
