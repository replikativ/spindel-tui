(ns org.replikativ.spindel-tui.style.border
  "Border rendering for styled boxes."
  (:require [org.replikativ.spindel-tui.style.width :as w]
            [org.replikativ.spindel-tui.style.color :as color]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Border Definitions
;; ---------------------------------------------------------------------------

(defn border
  "Create a border definition."
  [{:keys [top bottom left right
           top-left top-right bottom-left bottom-right]}]
  {:top top
   :bottom bottom
   :left left
   :right right
   :top-left top-left
   :top-right top-right
   :bottom-left bottom-left
   :bottom-right bottom-right})

;; ---------------------------------------------------------------------------
;; Standard Border Styles
;; ---------------------------------------------------------------------------

(def no-border
  "No border (empty strings)."
  (border {:top "" :bottom "" :left "" :right ""
           :top-left "" :top-right "" :bottom-left "" :bottom-right ""}))

(def normal
  "Normal single-line border."
  (border {:top "─" :bottom "─" :left "│" :right "│"
           :top-left "┌" :top-right "┐" :bottom-left "└" :bottom-right "┘"}))

(def rounded
  "Rounded corner border."
  (border {:top "─" :bottom "─" :left "│" :right "│"
           :top-left "╭" :top-right "╮" :bottom-left "╰" :bottom-right "╯"}))

(def block
  "Block/filled border."
  (border {:top "█" :bottom "█" :left "█" :right "█"
           :top-left "█" :top-right "█" :bottom-left "█" :bottom-right "█"}))

(def thick
  "Thick double-line border."
  (border {:top "━" :bottom "━" :left "┃" :right "┃"
           :top-left "┏" :top-right "┓" :bottom-left "┗" :bottom-right "┛"}))

(def double-border
  "Double-line border."
  (border {:top "═" :bottom "═" :left "║" :right "║"
           :top-left "╔" :top-right "╗" :bottom-left "╚" :bottom-right "╝"}))

(def hidden
  "Hidden border (spaces)."
  (border {:top " " :bottom " " :left " " :right " "
           :top-left " " :top-right " " :bottom-left " " :bottom-right " "}))

;; ---------------------------------------------------------------------------
;; Border Rendering
;; ---------------------------------------------------------------------------

(defn- render-horizontal-edge
  "Render a horizontal border edge (top or bottom)."
  [left-corner middle right-corner width]
  (let [middle (if (empty? middle) " " middle)
        left-width (w/string-width left-corner)
        right-width (w/string-width right-corner)
        middle-width (- width left-width right-width)
        pattern-width (w/string-width middle)]
    (loop [result (StringBuilder. ^String left-corner)
           current-width (long 0)]
      (if (>= current-width middle-width)
        (str result right-corner)
        (do
          (.append result middle)
          (recur result (+ current-width pattern-width)))))))

(defn apply-border
  "Apply a border around text content.

   Options:
     :border      - Border definition (default: normal)
     :top?        - Show top border (default: true)
     :right?      - Show right border (default: true)
     :bottom?     - Show bottom border (default: true)
     :left?       - Show left border (default: true)
     :fg          - Border foreground color
     :bg          - Border background color"
  [text & {:keys [border top? right? bottom? left? fg bg]
           :or {border normal
                top? true right? true bottom? true left? true}}]
  (let [lines (str/split-lines text)
        content-width (reduce max 0 (map w/string-width lines))
        {:keys [top bottom left right
                top-left top-right bottom-left bottom-right]} border
        left (if (and left? (empty? left)) " " left)
        right (if (and right? (empty? right)) " " right)
        top-left (if (and top? left? (empty? top-left)) " " top-left)
        top-right (if (and top? right? (empty? top-right)) " " top-right)
        bottom-left (if (and bottom? left? (empty? bottom-left)) " " bottom-left)
        bottom-right (if (and bottom? right? (empty? bottom-right)) " " bottom-right)
        left-width (if left? (w/string-width left) 0)
        right-width (if right? (w/string-width right) 0)
        total-width (+ content-width left-width right-width)
        style-border (fn [s] (if (or fg bg) (color/styled-str s :fg fg :bg bg) s))]
    (str/join
     "\n"
     (concat
      (when top?
        [(style-border
          (render-horizontal-edge
           (if left? top-left "")
           top
           (if right? top-right "")
           total-width))])
      (for [line lines]
        (str (when left? (style-border left))
             line
             (when right? (style-border right))))
      (when bottom?
        [(style-border
          (render-horizontal-edge
           (if left? bottom-left "")
           bottom
           (if right? bottom-right "")
           total-width))])))))

;; ---------------------------------------------------------------------------
;; Border Size Calculation
;; ---------------------------------------------------------------------------

(defn border-width
  "Calculate the total horizontal width added by a border."
  [border & {:keys [left? right?] :or {left? true right? true}}]
  (+ (if left? (w/string-width (:left border)) 0)
     (if right? (w/string-width (:right border)) 0)))

(defn border-height
  "Calculate the total vertical height added by a border."
  [& {:keys [top? bottom?] :or {top? true bottom? true}}]
  (+ (if top? 1 0)
     (if bottom? 1 0)))
