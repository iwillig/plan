(ns plan.markdown-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [plan.markdown :as markdown]))

(deftest markdown->html-test
  (testing "basic markdown to HTML conversion"
    (is (= "<p>This is <em>italic</em> and <strong>bold</strong></p>\n"
           (markdown/markdown->html "This is *italic* and **bold**")))
    (is (= "<h1>Heading</h1>\n"
           (markdown/markdown->html "# Heading")))
    (is (= "<ul>\n<li>Item 1</li>\n<li>Item 2</li>\n</ul>\n"
           (markdown/markdown->html "- Item 1\n- Item 2")))))

(deftest parse-with-front-matter-test
  (testing "parsing markdown with YAML front matter"
    (let [input "---\ntitle: My Document\nauthor: John Doe\n---\n\n# Content\n\nHello world."
          result (markdown/parse-with-front-matter input)]
      (is (string? (:html result)))
      (is (map? (:front-matter result)))
      (is (= "My Document" (get-in result [:front-matter :title])))
      (is (= "John Doe" (get-in result [:front-matter :author])))
      (is (str/includes? (:html result) "<h1>Content</h1>")))))

(deftest front-matter-with-lists-test
  (testing "parsing front matter with list values"
    (let [input "---\ntags:\n  - clojure\n  - graalvm\n  - markdown\n---\n\nContent here."
          result (markdown/parse-with-front-matter input)]
      (is (= ["clojure" "graalvm" "markdown"]
             (get-in result [:front-matter :tags])))))

  (testing "parsing front matter with single values"
    (let [input "---\ntitle: Single Tag\n---\n\nContent."
          result (markdown/parse-with-front-matter input)]
      (is (= "Single Tag" (get-in result [:front-matter :title]))))))

(deftest test-markdown-parsing-test
  (testing "comprehensive test function"
    (let [result (markdown/test-markdown-parsing)]
      (is (true? (:success result)))
      (is (string? (:html result)))
      (is (seq (:html result)))
      (is (map? (:front-matter result)))
      (is (= "Test Document" (get-in result [:front-matter :title])))
      (is (= "Test Author" (get-in result [:front-matter :author]))))))

(deftest no-front-matter-test
  (testing "markdown without front matter"
    (let [input "# Just a heading\n\nSome content."
          result (markdown/parse-with-front-matter input)]
      (is (nil? (:front-matter result)))
      (is (str/includes? (:html result) "<h1>Just a heading</h1>")))))

(deftest code-blocks-test
  (testing "code blocks are preserved"
    (let [input "```clojure\n(defn hello []\n  (println \"Hello\"))\n```"
          result (markdown/markdown->html input)]
      (is (str/includes? result "<pre>"))
      (is (str/includes? result "<code"))
      (is (str/includes? result "defn hello")))))