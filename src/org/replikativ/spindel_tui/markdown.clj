(ns org.replikativ.spindel-tui.markdown
  "Markdown to ANSI terminal rendering.

   Uses nextjournal/markdown for parsing and glow for Clojure syntax highlighting."
  (:require [nextjournal.markdown :as md]
            [nextjournal.markdown.transform :as md.transform]
            [glow.core :as glow]
            [clojure.string :as str]))

;; =============================================================================
;; ANSI Escape Codes
;; =============================================================================

(def ^:private ansi-reset "\u001b[0m")
(def ^:private ansi-bold "\u001b[1m")
(def ^:private ansi-dim "\u001b[2m")
(def ^:private ansi-italic "\u001b[3m")
(def ^:private ansi-underline "\u001b[4m")
(def ^:private ansi-strikethrough "\u001b[9m")

;; Colors (foreground)
(def ^:private ansi-black "\u001b[30m")
(def ^:private ansi-red "\u001b[31m")
(def ^:private ansi-green "\u001b[32m")
(def ^:private ansi-yellow "\u001b[33m")
(def ^:private ansi-blue "\u001b[34m")
(def ^:private ansi-magenta "\u001b[35m")
(def ^:private ansi-cyan "\u001b[36m")
(def ^:private ansi-white "\u001b[37m")

;; Bright colors
(def ^:private ansi-bright-black "\u001b[90m")
(def ^:private ansi-bright-red "\u001b[91m")
(def ^:private ansi-bright-green "\u001b[92m")
(def ^:private ansi-bright-yellow "\u001b[93m")
(def ^:private ansi-bright-blue "\u001b[94m")
(def ^:private ansi-bright-magenta "\u001b[95m")
(def ^:private ansi-bright-cyan "\u001b[96m")
(def ^:private ansi-bright-white "\u001b[97m")

;; Background colors
(def ^:private ansi-bg-black "\u001b[40m")
(def ^:private ansi-bg-white "\u001b[47m")
(def ^:private ansi-bg-bright-black "\u001b[100m")

;; =============================================================================
;; Simple Syntax Highlighting for Non-Clojure Languages
;; =============================================================================

(defn- highlight-strings
  "Highlight string literals in code."
  [code]
  (str/replace code #"(\"[^\"\\]*(?:\\.[^\"\\]*)*\"|'[^'\\]*(?:\\.[^'\\]*)*')"
               (str ansi-green "$1" ansi-reset)))

(defn- highlight-comments
  "Highlight comments (# and // style)."
  [code]
  (-> code
      (str/replace #"(#.*)$" (str ansi-bright-black "$1" ansi-reset))
      (str/replace #"(//.*)$" (str ansi-bright-black "$1" ansi-reset))))

(defn- highlight-keywords-generic
  "Highlight common keywords for shell/python/js."
  [code lang]
  (let [keywords (case lang
                   ("bash" "sh" "shell" "zsh")
                   #{"if" "then" "else" "elif" "fi" "for" "while" "do" "done"
                     "case" "esac" "function" "return" "export" "local" "in"}

                   ("python" "py")
                   #{"def" "class" "if" "elif" "else" "for" "while" "try" "except"
                     "finally" "with" "as" "import" "from" "return" "yield" "raise"
                     "True" "False" "None" "and" "or" "not" "in" "is" "lambda"}

                   ("javascript" "js" "typescript" "ts")
                   #{"function" "const" "let" "var" "if" "else" "for" "while"
                     "return" "class" "extends" "import" "export" "from" "async"
                     "await" "try" "catch" "finally" "throw" "new" "this"
                     "true" "false" "null" "undefined"}

                   ("java" "kotlin" "scala")
                   #{"public" "private" "protected" "class" "interface" "extends"
                     "implements" "static" "final" "void" "return" "if" "else"
                     "for" "while" "try" "catch" "finally" "throw" "new" "import"
                     "package" "true" "false" "null" "this" "super"}

                   ("rust" "rs")
                   #{"fn" "let" "mut" "const" "if" "else" "for" "while" "loop"
                     "match" "return" "struct" "enum" "impl" "trait" "pub" "use"
                     "mod" "self" "Self" "true" "false" "Some" "None" "Ok" "Err"}

                   ("go" "golang")
                   #{"func" "var" "const" "if" "else" "for" "switch" "case"
                     "return" "struct" "interface" "type" "package" "import"
                     "go" "defer" "chan" "range" "true" "false" "nil"}

                   ;; Default - common across many languages
                   #{"if" "else" "for" "while" "return" "function" "class"
                     "true" "false" "null" "nil" "import" "export"})]
    (reduce (fn [s kw]
              (str/replace s (re-pattern (str "\\b(" kw ")\\b"))
                           (str ansi-magenta "$1" ansi-reset)))
            code
            keywords)))

(defn- highlight-numbers
  "Highlight numeric literals."
  [code]
  (str/replace code #"\b(\d+(?:\.\d+)?)\b"
               (str ansi-cyan "$1" ansi-reset)))

(defn- simple-highlight
  "Simple syntax highlighting for non-Clojure languages."
  [code lang]
  (-> code
      (highlight-strings)
      (highlight-keywords-generic lang)
      (highlight-numbers)
      (highlight-comments)))

;; =============================================================================
;; Code Block Highlighting
;; =============================================================================

(defn- clojure-lang?
  "Check if language is Clojure-like."
  [lang]
  (when lang
    (contains? #{"clojure" "clj" "cljc" "cljs" "edn" "bb"}
               (str/lower-case lang))))

(defn highlight-code
  "Highlight code block with appropriate highlighter."
  [code lang]
  (if (clojure-lang? lang)
    ;; Use glow for Clojure
    (try
      (glow/highlight code)
      (catch Exception _
        ;; Fallback if glow fails (e.g., invalid syntax)
        (str ansi-cyan code ansi-reset)))
    ;; Use simple highlighting for other languages
    (if (and lang (seq lang))
      (simple-highlight code lang)
      ;; No language specified - just dim it slightly
      (str ansi-dim code ansi-reset))))

;; =============================================================================
;; ANSI Renderers for Markdown AST
;; =============================================================================

(declare render-node)

(defn- render-children
  "Render all children of a node."
  [ctx node]
  (apply str (map #(render-node ctx %) (:content node))))

(defn- node->text
  "Extract plain text from a node."
  [node]
  (md.transform/->text node))

(def ansi-renderers
  "Map of node type -> ANSI renderer function."
  {:doc (fn [ctx node]
          (render-children ctx node))

   :paragraph (fn [ctx node]
                (str (render-children ctx node) "\n\n"))

   :heading (fn [ctx {:keys [heading-level] :as node}]
              (let [prefix (apply str (repeat (or heading-level 1) "#"))
                    content (render-children ctx node)]
                (str ansi-bold ansi-bright-cyan prefix " " content ansi-reset "\n\n")))

   :text (fn [_ {:keys [text]}]
           text)

   :softbreak (constantly " ")
   :hardbreak (constantly "\n")

   :strong (fn [ctx node]
             (str ansi-bold (render-children ctx node) ansi-reset))

   :em (fn [ctx node]
         (str ansi-italic (render-children ctx node) ansi-reset))

   :strikethrough (fn [ctx node]
                    (str ansi-strikethrough (render-children ctx node) ansi-reset))

   :monospace (fn [ctx node]
                (str ansi-bg-bright-black ansi-bright-white
                     (render-children ctx node)
                     ansi-reset))

   :code (fn [_ {:keys [language content]}]
           (let [code-text (node->text {:content content})
                 highlighted (highlight-code code-text language)
                 lines (str/split-lines highlighted)
                 ;; Simple left-border style with language label
                 lang-header (when (and language (seq language))
                               (str ansi-dim "── " language " ──" ansi-reset "\n"))
                 indented (map #(str ansi-dim "│" ansi-reset " " %) lines)]
             (str (or lang-header "")
                  (str/join "\n" indented) "\n\n")))

   :blockquote (fn [ctx node]
                 (let [content (render-children ctx node)
                       lines (str/split-lines content)]
                   (str (str/join "\n" (map #(str ansi-bright-black "│ " ansi-reset
                                                  ansi-italic % ansi-reset)
                                            lines))
                        "\n\n")))

   :bullet-list (fn [ctx node]
                  (str (str/join ""
                                 (map-indexed
                                  (fn [_ item]
                                    (str "  • " (str/trim (render-node ctx item)) "\n"))
                                  (:content node)))
                       "\n"))

   :numbered-list (fn [ctx node]
                    (str (str/join ""
                                   (map-indexed
                                    (fn [i item]
                                      (str "  " (inc i) ". " (str/trim (render-node ctx item)) "\n"))
                                    (:content node)))
                         "\n"))

   :list-item (fn [ctx node]
                (render-children ctx node))

   :plain (fn [ctx node]
            (render-children ctx node))

   :ruler (constantly (str ansi-dim (apply str (repeat 40 "─")) ansi-reset "\n\n"))

   :link (fn [ctx {:keys [attrs] :as node}]
           (let [text (render-children ctx node)
                 href (:href attrs)]
             (str ansi-underline ansi-blue text ansi-reset
                  ansi-dim " (" href ")" ansi-reset)))

   :image (fn [_ {:keys [attrs] :as node}]
            (let [alt (node->text node)
                  src (:src attrs)]
              (str ansi-dim "[Image: " alt "]" ansi-reset
                   (when src (str ansi-dim " (" src ")" ansi-reset)))))

   ;; Tables
   :table (fn [ctx node]
            (str (render-children ctx node) "\n"))

   :table-head (fn [ctx node]
                 (render-children ctx node))

   :table-body (fn [ctx node]
                 (render-children ctx node))

   :table-row (fn [ctx node]
                (let [cells (map #(str/trim (render-node ctx %)) (:content node))]
                  (str "│ " (str/join " │ " cells) " │\n")))

   :table-header (fn [ctx node]
                   (str ansi-bold (render-children ctx node) ansi-reset))

   :table-data (fn [ctx node]
                 (render-children ctx node))

   ;; Formulas (LaTeX)
   :formula (fn [_ node]
              (str ansi-yellow "$" (node->text node) "$" ansi-reset))

   :block-formula (fn [_ node]
                    (str ansi-yellow "$$" (node->text node) "$$" ansi-reset "\n"))

   ;; HTML (pass through as-is, dimmed)
   :html-inline (fn [_ node]
                  (str ansi-dim (node->text node) ansi-reset))

   :html-block (fn [_ node]
                 (str ansi-dim (node->text node) ansi-reset "\n"))

   ;; Fallback for unknown types
   :default (fn [_ node]
              (node->text node))})

(defn render-node
  "Render a single markdown AST node to ANSI string."
  [ctx node]
  (let [node-type (:type node)
        renderer (or (get ctx node-type)
                     (get ansi-renderers node-type)
                     (get ansi-renderers :default))]
    (renderer ctx node)))

;; =============================================================================
;; Public API
;; =============================================================================

(defn render
  "Render markdown string to ANSI-styled terminal output.

   Options:
   - :renderers - custom renderer overrides (merged with defaults)"
  ([markdown-str] (render markdown-str {}))
  ([markdown-str opts]
   (let [ast (md/parse markdown-str)
         ctx (merge ansi-renderers (:renderers opts))]
     (-> (render-node ctx ast)
         (str/trim-newline)))))

(defn render-inline
  "Render markdown meant for inline display (no trailing newlines, simpler formatting)."
  [markdown-str]
  (-> (render markdown-str)
      (str/replace #"\n\n+" "\n")
      (str/trim)))
