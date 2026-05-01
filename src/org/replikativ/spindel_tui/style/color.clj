(ns org.replikativ.spindel-tui.style.color
  "Terminal color handling.

   Supports:
   - ANSI 16 basic colors (0-15)
   - ANSI 256 extended palette (0-255)
   - True color RGB (24-bit)"
  (:require [clojure.string :as str])
  (:import [org.jline.utils AttributedString AttributedStyle]))

;; ---------------------------------------------------------------------------
;; ANSI Basic Colors (0-15)
;; ---------------------------------------------------------------------------

(def ansi-colors
  "ANSI 16 basic color names to codes."
  {:black 0
   :red 1
   :green 2
   :yellow 3
   :blue 4
   :magenta 5
   :cyan 6
   :white 7
   :bright-black 8
   :bright-red 9
   :bright-green 10
   :bright-yellow 11
   :bright-blue 12
   :bright-magenta 13
   :bright-cyan 14
   :bright-white 15})

;; ---------------------------------------------------------------------------
;; Color Construction
;; ---------------------------------------------------------------------------

(defn ansi
  "Create an ANSI 16 color (0-15).
   Accepts a number or keyword like :red, :bright-blue."
  [color]
  (let [code (if (keyword? color)
               (get ansi-colors color color)
               color)]
    {:type :ansi :code code}))

(defn ansi256
  "Create an ANSI 256 color (0-255)."
  [code]
  {:type :ansi256 :code code})

(defn rgb
  "Create a true color from RGB values (0-255 each)."
  [r g b]
  {:type :rgb :r r :g g :b b})

(defn hex
  "Create a true color from a hex string like \"#ff0000\" or \"ff0000\"."
  [hex-str]
  (let [s (if (str/starts-with? hex-str "#")
            (subs hex-str 1)
            hex-str)
        r (Integer/parseInt (subs s 0 2) 16)
        g (Integer/parseInt (subs s 2 4) 16)
        b (Integer/parseInt (subs s 4 6) 16)]
    (rgb r g b)))

(defn no-color
  "Create a no-color (transparent) value."
  []
  {:type :none})

;; ---------------------------------------------------------------------------
;; Color Application (via JLine AttributedStyle)
;; ---------------------------------------------------------------------------

(defn apply-color-fg
  "Apply foreground color to an AttributedStyle."
  ^AttributedStyle [^AttributedStyle style color]
  (if (or (nil? color) (= :none (:type color)))
    style
    (case (:type color)
      :ansi    (.foreground style (int (:code color)))
      :ansi256 (.foreground style (int (:code color)))
      :rgb     (.foreground style (int (:r color)) (int (:g color)) (int (:b color)))
      style)))

(defn apply-color-bg
  "Apply background color to an AttributedStyle."
  ^AttributedStyle [^AttributedStyle style color]
  (if (or (nil? color) (= :none (:type color)))
    style
    (case (:type color)
      :ansi    (.background style (int (:code color)))
      :ansi256 (.background style (int (:code color)))
      :rgb     (.background style (int (:r color)) (int (:g color)) (int (:b color)))
      style)))

(defn styled-str
  "Create a styled string with foreground and/or background color.
   Returns the string with ANSI escape sequences applied."
  [text & {:keys [fg bg]}]
  (if (and (nil? fg) (nil? bg))
    text
    (let [style (-> AttributedStyle/DEFAULT
                    (apply-color-fg fg)
                    (apply-color-bg bg))]
      (.toAnsi (AttributedString. ^String text style)))))

;; ---------------------------------------------------------------------------
;; Color Conversion
;; ---------------------------------------------------------------------------

(defn rgb->ansi256
  "Convert RGB to closest ANSI 256 color."
  [{:keys [r g b]}]
  (let [gray? (and (< (Math/abs (long (- r g))) 10)
                   (< (Math/abs (long (- g b))) 10)
                   (< (Math/abs (long (- r b))) 10))
        cube-value (fn [v] (int (Math/round (/ (* v 5.0) 255.0))))
        cube-r (cube-value r)
        cube-g (cube-value g)
        cube-b (cube-value b)]
    (if gray?
      (let [gray-idx (int (Math/round (* (/ (+ r g b) 3.0 255.0) 23)))]
        (ansi256 (+ 232 gray-idx)))
      (ansi256 (+ 16 (* 36 cube-r) (* 6 cube-g) cube-b)))))

;; ---------------------------------------------------------------------------
;; Convenience Colors
;; ---------------------------------------------------------------------------

(def black (ansi :black))
(def red (ansi :red))
(def green (ansi :green))
(def yellow (ansi :yellow))
(def blue (ansi :blue))
(def magenta (ansi :magenta))
(def cyan (ansi :cyan))
(def white (ansi :white))

(def bright-black (ansi :bright-black))
(def bright-red (ansi :bright-red))
(def bright-green (ansi :bright-green))
(def bright-yellow (ansi :bright-yellow))
(def bright-blue (ansi :bright-blue))
(def bright-magenta (ansi :bright-magenta))
(def bright-cyan (ansi :bright-cyan))
(def bright-white (ansi :bright-white))
