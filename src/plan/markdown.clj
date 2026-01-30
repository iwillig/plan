(ns plan.markdown
  "Markdown parsing functionality using CommonMark Java library.
   Designed to be GraalVM native-image compatible.

   Uses clj-yaml for YAML parsing to provide:
   - Full YAML spec compliance
   - Raw YAML string access
   - Better type handling (dates, booleans, numbers)"
  (:require
   [clj-yaml.core :as yaml]
   [clojure.string :as str])
  (:import
   (org.commonmark.ext.front.matter
    YamlFrontMatterBlock
    YamlFrontMatterExtension)
   (org.commonmark.node
    Document
    SourceSpan)
   (org.commonmark.parser
    IncludeSourceSpans
    Parser)
   (org.commonmark.renderer.html
    HtmlRenderer)))

(set! *warn-on-reflection* true)

(defn create-parser
  "Create a CommonMark parser with YAML front matter extension.
   Includes source spans to enable raw YAML extraction."
  []
  (let [extensions [(YamlFrontMatterExtension/create)]
        parser-builder (Parser/builder)]
    (.extensions parser-builder extensions)
    (.includeSourceSpans parser-builder IncludeSourceSpans/BLOCKS)
    (.build parser-builder)))

(defn create-renderer
  "Create an HTML renderer with YAML front matter extension."
  []
  (let [extensions [(YamlFrontMatterExtension/create)]
        renderer-builder (HtmlRenderer/builder)]
    (.extensions renderer-builder extensions)
    (.build renderer-builder)))

(defn parse-markdown
  "Parse markdown string to AST document."
  [^String input]
  (let [^Parser parser (create-parser)]
    (.parse parser input)))

(defn render-html
  "Render AST document to HTML string."
  [^Document document]
  (let [^HtmlRenderer renderer (create-renderer)]
    (.render renderer document)))

(defn extract-raw-yaml
  "Extract raw YAML front matter string from parsed document.
   Returns the raw YAML including --- delimiters, or nil if no front matter."
  [^Document document ^String original-input]
  (let [yaml-block (.getFirstChild document)]
    (when (instance? YamlFrontMatterBlock yaml-block)
      (let [source-spans (.getSourceSpans yaml-block)]
        (when (seq source-spans)
          (let [^SourceSpan first-span (first source-spans)
                ^SourceSpan last-span (last source-spans)
                start-index (.getInputIndex first-span)
                end-index (+ (.getInputIndex last-span) (.getLength last-span))]
            (subs original-input start-index end-index)))))))

(defn parse-yaml-content
  "Parse YAML content string (without delimiters) using clj-yaml.
   Returns parsed YAML as Clojure data structures."
  [^String yaml-content]
  (when (seq (str/trim yaml-content))
    (yaml/parse-string yaml-content)))

(defn extract-body-content
  "Extract body content from markdown string, given the end index of front matter."
  [^String input front-matter-end-index]
  (if front-matter-end-index
    (str/trim (subs input front-matter-end-index))
    (str/trim input)))

(defn parse-with-front-matter
  "Parse markdown and return both HTML and front matter.
   Uses clj-yaml for YAML parsing to provide full YAML spec compliance.

   Returns a map with:
   - :html - Rendered HTML body (front matter excluded)
   - :front-matter - Parsed YAML as Clojure data structures
   - :raw-yaml - Raw YAML string including --- delimiters
   - :body - Raw body content (markdown without front matter)
   - :document - The parsed AST document"
  [^String input]
  (let [extensions [(YamlFrontMatterExtension/create)]
        parser-builder (Parser/builder)]
    (.extensions parser-builder extensions)
    (.includeSourceSpans parser-builder IncludeSourceSpans/BLOCKS)
    (let [parser (.build parser-builder)
          document (.parse parser input)
          yaml-block (.getFirstChild document)]
      (if (instance? YamlFrontMatterBlock yaml-block)
        ;; Has front matter
        (let [source-spans (.getSourceSpans yaml-block)]
          (if (seq source-spans)
            (let [^SourceSpan first-span (first source-spans)
                  ^SourceSpan last-span (last source-spans)
                  start-index (.getInputIndex first-span)
                  end-index (+ (.getInputIndex last-span) (.getLength last-span))
                  raw-yaml (subs input start-index end-index)
                  ;; Parse YAML with clj-yaml (remove delimiters first)
                  yaml-content (-> raw-yaml
                                   (str/replace-first #"^---\n" "")
                                   (str/replace #"\n---$" ""))
                  parsed-yaml (parse-yaml-content yaml-content)
                  body-content (extract-body-content input end-index)
                  renderer-builder (HtmlRenderer/builder)
                  _ (.extensions renderer-builder extensions)
                  renderer (.build renderer-builder)
                  html (.render renderer document)]
              {:html html
               :front-matter parsed-yaml
               :raw-yaml raw-yaml
               :body body-content
               :document document})
            ;; No source spans available
            {:html (render-html document)
             :front-matter nil
             :raw-yaml nil
             :body input
             :document document}))
        ;; No front matter
        (let [renderer-builder (HtmlRenderer/builder)
              _ (.extensions renderer-builder extensions)
              renderer (.build renderer-builder)
              html (.render renderer document)]
          {:html html
           :front-matter nil
           :raw-yaml nil
           :body (str/trim input)
           :document document})))))

(defn markdown->html
  "Convert markdown string directly to HTML."
  [^String input]
  (-> input parse-markdown render-html))

(defn test-markdown-parsing
  "Test function to verify CommonMark works correctly.
   Returns a map with test results."
  []
  (let [test-input "---
title: Test Document
author: Test Author
date: 2024-01-15
draft: false
priority: 1
tags:
  - clojure
  - graalvm
---

# Hello World

This is a **test** of the CommonMark library.

- Item 1
- Item 2
- Item 3

## Code Example

```clojure
(defn hello []
  (println \"Hello, GraalVM!\"))
```"
        result (parse-with-front-matter test-input)]
    {:success (and (string? (:html result))
                   (seq (:html result))
                   (map? (:front-matter result))
                   (= "Test Document" (get-in result [:front-matter :title]))
                   (= "Test Author" (get-in result [:front-matter :author]))
                   (= #inst "2024-01-15T00:00:00.000-00:00" (get-in result [:front-matter :date]))
                   (= false (get-in result [:front-matter :draft]))
                   (= 1 (get-in result [:front-matter :priority]))
                   (string? (:raw-yaml result))
                   (str/starts-with? (:raw-yaml result) "---")
                   (string? (:body result))
                   (not (str/blank? (:body result))))
     :html (:html result)
     :front-matter (:front-matter result)
     :raw-yaml (:raw-yaml result)
     :body (:body result)
     :input test-input}))
