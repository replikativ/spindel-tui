(ns org.replikativ.spindel-tui.style.core
  "Main styling API.

   Create styles as maps and apply them to text.

   Example:
     (def my-style (style :fg (rgb 255 0 0) :bold true :padding [1 2]))
     (render my-style \"Hello!\")  ; => styled text"
  (:require [org.replikativ.spindel-tui.style.color :as c]
            [org.replikativ.spindel-tui.style.border :as b]
            [org.replikativ.spindel-tui.style.layout :as l]
            [org.replikativ.spindel-tui.style.width :as w]
            [clojure.string :as str])
  (:import [org.jline.utils AttributedString AttributedStringBuilder AttributedStyle]))

;; ---------------------------------------------------------------------------
;; Style Definition
;; ---------------------------------------------------------------------------

(defn style
  "Create a style map.

   Options:
     ;; Colors
     :fg         - Foreground color
     :bg         - Background color

     ;; Text attributes
     :bold       - Bold text
     :italic     - Italic text
     :underline  - Underline text
     :blink      - Blinking text
     :faint      - Faint/dim text
     :reverse    - Reverse video

     ;; Dimensions
     :width      - Fixed width (pads/truncates)
     :height     - Fixed height

     ;; Alignment
     :align      - Horizontal alignment (:left :center :right)
     :valign     - Vertical alignment (:top :center :bottom)

     ;; Spacing
     :padding    - Padding [top right bottom left] or single value
     :margin     - Margin [top right bottom left] or single value

     ;; Border
     :border     - Border style (from org.replikativ.spindel-tui.style.border)
     :border-fg  - Border foreground color
     :border-bg  - Border background color

     ;; Rendering
     :inline     - Remove newlines when true"
  [& {:as opts}]
  (merge
   {:fg nil
    :bg nil
    :bold false
    :italic false
    :underline false
    :blink false
    :faint false
    :reverse false
    :width nil
    :height nil
    :align :left
    :valign :top
    :padding nil
    :margin nil
    :border nil
    :border-fg nil
    :border-bg nil
    :inline false}
   opts))

;; ---------------------------------------------------------------------------
;; Style Modifiers
;; ---------------------------------------------------------------------------

(defn with-fg [s color] (assoc s :fg color))
(defn with-bg [s color] (assoc s :bg color))
(defn with-bold [s] (assoc s :bold true))
(defn with-italic [s] (assoc s :italic true))
(defn with-underline [s] (assoc s :underline true))
(defn with-padding [s padding]
  (assoc s :padding (if (number? padding) [padding] (vec padding))))
(defn with-margin [s margin]
  (assoc s :margin (if (number? margin) [margin] (vec margin))))
(defn with-border [s border-style] (assoc s :border border-style))
(defn with-width [s width] (assoc s :width width))
(defn with-height [s height] (assoc s :height height))
(defn with-align [s align] (assoc s :align align))
(defn with-valign [s valign] (assoc s :valign valign))

;; ---------------------------------------------------------------------------
;; ANSI Sequence Generation (via JLine AttributedStyle)
;; ---------------------------------------------------------------------------

(defn style->attributed-style
  "Convert style map to JLine AttributedStyle."
  ^AttributedStyle [{:keys [fg bg bold italic underline blink faint reverse]}]
  (cond-> AttributedStyle/DEFAULT
    bold      (.bold)
    faint     (.faint)
    italic    (.italic)
    underline (.underline)
    blink     (.blink)
    reverse   (.inverse)
    fg        (c/apply-color-fg fg)
    bg        (c/apply-color-bg bg)))

(defn attributed-string
  "Create a JLine AttributedString with the given style applied."
  ^AttributedString [style-map text]
  (let [attr-style (style->attributed-style style-map)]
    (AttributedString. ^String text attr-style)))

(defn- apply-text-style
  "Apply text styling (colors and attributes) to a string."
  [text style]
  (let [attr-style (style->attributed-style style)]
    (if (= attr-style AttributedStyle/DEFAULT)
      text
      (->> (str/split-lines text)
           (map #(.toAnsi (AttributedString. ^String % attr-style)))
           (str/join "\n")))))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn render
  "Render text with a style applied."
  [style & strings]
  (let [text (str/join " " strings)
        {:keys [width height align valign padding margin
                border border-fg border-bg inline bg]} style

        text (if inline
               (str/replace text #"\n" "")
               text)

        text (apply-text-style text style)

        text (if padding
               (let [[t r b l] (l/expand-box-values padding)]
                 (l/pad text t r b l :bg bg))
               text)

        text (if height
               (l/align-vertical text height valign)
               text)

        text (if width
               (l/align-horizontal text width align :bg bg)
               text)

        text (if border
               (b/apply-border text
                               :border border
                               :fg border-fg
                               :bg border-bg)
               text)

        text (if margin
               (let [[t r b l] (l/expand-box-values margin)]
                 (l/margin text t r b l))
               text)]
    text))

;; ---------------------------------------------------------------------------
;; Convenience Functions
;; ---------------------------------------------------------------------------

(defn styled
  "Apply style directly to text. Shorthand for (render (style opts...) text)."
  [text & style-opts]
  (render (apply style style-opts) text))

;; Re-export commonly used functions
(def rgb c/rgb)
(def hex c/hex)
(def ansi c/ansi)
(def ansi256 c/ansi256)

;; Common colors
(def black c/black)
(def red c/red)
(def green c/green)
(def yellow c/yellow)
(def blue c/blue)
(def magenta c/magenta)
(def cyan c/cyan)
(def white c/white)

;; Border styles
(def normal-border b/normal)
(def rounded-border b/rounded)
(def thick-border b/thick)
(def double-border b/double-border)
(def hidden-border b/hidden)

;; Join functions
(def join-horizontal l/join-horizontal)
(def join-vertical l/join-vertical)

;; Width utilities
(def string-width w/string-width)
(def truncate w/truncate)

;; ---------------------------------------------------------------------------
;; Frame Size Calculation
;; ---------------------------------------------------------------------------

(defn frame-size
  "Calculate the frame size (padding + border + margin) of a style.
   Returns [width height]."
  [{:keys [padding margin border]}]
  (let [[pt pr pb pl] (if padding (l/expand-box-values padding) [0 0 0 0])
        [mt mr mb ml] (if margin (l/expand-box-values margin) [0 0 0 0])
        border-h (if border (b/border-width border) 0)
        border-v (if border (b/border-height) 0)]
    [(+ pl pr ml mr border-h)
     (+ pt pb mt mb border-v)]))
