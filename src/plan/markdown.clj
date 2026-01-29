(ns plan.markdown
  "Markdown parsing functionality using CommonMark Java library.
   Designed to be GraalVM native-image compatible."
  (:require
   [clojure.string :as str])
  (:import
   (org.commonmark.ext.front.matter
    YamlFrontMatterExtension
    YamlFrontMatterVisitor)
   (org.commonmark.node
    Document)
   (org.commonmark.parser
    Parser)
   (org.commonmark.renderer.html
    HtmlRenderer)))

(set! *warn-on-reflection* true)

(defn create-parser
  "Create a CommonMark parser with YAML front matter extension."
  []
  (let [extensions [(YamlFrontMatterExtension/create)]
        parser-builder (Parser/builder)]
    (.extensions parser-builder extensions)
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

(defn extract-yaml-front-matter
  "Extract YAML front matter from markdown document.
   Returns a map of key-value pairs, or nil if no front matter found."
  [^Document document]
  (let [^YamlFrontMatterVisitor visitor (YamlFrontMatterVisitor.)]
    (.visit visitor document)
    (let [data (.getData visitor)]
      (when (seq data)
        (into {} (map (fn [[k v]]
                        [(keyword k) (if (= 1 (count v))
                                       (first v)
                                       (vec v))])
                      data))))))

(defn markdown->html
  "Convert markdown string directly to HTML."
  [^String input]
  (-> input parse-markdown render-html))

(defn parse-with-front-matter
  "Parse markdown and return both HTML and front matter."
  [^String input]
  (let [document (parse-markdown input)
        html (render-html document)
        front-matter (extract-yaml-front-matter document)]
    {:html html
     :front-matter front-matter
     :document document}))

(defn extract-body-content
  "Extract the body content (after front matter) from a markdown document.
   Returns the content as plain text, stripping the front matter."
  [^String markdown-text]
  (let [lines (str/split-lines markdown-text)
        ;; Find the second --- delimiter (end of front matter)
        front-matter-end (second (keep-indexed #(when (= "---" %2) %1) lines))]
    (if front-matter-end
      (str/trim (str/join "\n" (drop (inc front-matter-end) lines)))
      (str/trim markdown-text))))

(defn test-markdown-parsing
  "Test function to verify CommonMark works correctly.
   Returns a map with test results."
  []
  (let [test-input "---
title: Test Document
author: Test Author
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
                   (= "Test Author" (get-in result [:front-matter :author])))
     :html (:html result)
     :front-matter (:front-matter result)
     :input test-input}))
