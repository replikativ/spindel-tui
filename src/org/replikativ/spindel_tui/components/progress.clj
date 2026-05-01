(ns org.replikativ.spindel-tui.components.progress
  "Progress bar component.

   State is stored in a Spindel signal. Use progress-state to create
   initial state, then use set-progress to update."
  (:require [org.replikativ.spindel-tui.style.core :as style]))

;; ---------------------------------------------------------------------------
;; Progress Bar Styles
;; ---------------------------------------------------------------------------

(def bar-styles
  "Predefined progress bar styles."
  {:default    {:full "█" :empty "░"}
   :ascii      {:full "#" :empty "-"}
   :thin       {:full "━" :empty "─"}
   :thick      {:full "█" :empty "▒"}
   :blocks     {:full "▓" :empty "░"}
   :arrows     {:full ">" :empty " "}
   :dots       {:full "●" :empty "○"}
   :brackets   {:full "=" :empty " " :left "[" :right "]"}})

;; ---------------------------------------------------------------------------
;; Progress Bar State
;; ---------------------------------------------------------------------------

(defn progress-state
  "Create initial progress bar state.

   Options:
     :width           - Total width in characters (default 40)
     :percent         - Initial progress 0.0-1.0 (default 0)
     :bar-style       - Keyword from bar-styles or custom map (default :default)
     :show-percent    - Show percentage text (default false)
     :full-style      - Style for filled portion
     :empty-style     - Style for empty portion
     :percent-style   - Style for percentage text"
  [& {:keys [width percent bar-style show-percent
             full-style empty-style percent-style]
      :or {width 40
           percent 0.0
           bar-style :default
           show-percent false}}]
  (let [style-map (if (keyword? bar-style)
                    (get bar-styles bar-style (:default bar-styles))
                    bar-style)]
    {:width width
     :percent (max 0.0 (min 1.0 percent))
     :bar-style style-map
     :show-percent show-percent
     :full-style (or full-style (style/style :fg style/cyan))
     :empty-style empty-style
     :percent-style percent-style}))

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn percent
  "Get current progress as 0.0-1.0."
  [state]
  (:percent state))

(defn percent-int
  "Get current progress as 0-100 integer."
  [state]
  (int (* 100 (:percent state))))

(defn set-progress
  "Set progress (0.0 to 1.0)."
  [state p]
  (assoc state :percent (max 0.0 (min 1.0 (double p)))))

(defn set-progress-int
  "Set progress as 0-100 integer."
  [state p]
  (set-progress state (/ p 100.0)))

(defn increment
  "Increment progress by amount (default 0.01)."
  ([state] (increment state 0.01))
  ([state amount]
   (set-progress state (+ (:percent state) amount))))

(defn decrement
  "Decrement progress by amount (default 0.01)."
  ([state] (decrement state 0.01))
  ([state amount]
   (set-progress state (- (:percent state) amount))))

(defn complete?
  "Check if progress is complete (100%)."
  [state]
  (>= (:percent state) 1.0))

(defn reset-progress
  "Reset progress to 0."
  [state]
  (assoc state :percent 0.0))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn view
  "Render the progress bar to a string."
  [state]
  (let [{:keys [width percent bar-style show-percent
                full-style empty-style percent-style]} state
        {:keys [full empty left right]
         :or {left "" right ""}} bar-style

        percent-text (when show-percent (format " %3d%%" (int (* 100 percent))))
        bracket-width (+ (count left) (count right))
        percent-width (if show-percent (count percent-text) 0)
        bar-width (- width bracket-width percent-width)

        filled-count (int (* bar-width percent))
        empty-count (- bar-width filled-count)

        filled-str (apply str (repeat filled-count full))
        empty-str (apply str (repeat empty-count empty))

        styled-filled (if full-style
                        (style/render full-style filled-str)
                        filled-str)
        styled-empty (if empty-style
                       (style/render empty-style empty-str)
                       empty-str)
        styled-percent (when show-percent
                         (if percent-style
                           (style/render percent-style percent-text)
                           percent-text))]

    (str left styled-filled styled-empty right styled-percent)))

;; ---------------------------------------------------------------------------
;; Signal-based API
;; ---------------------------------------------------------------------------

(defn create-signal
  "Create a progress bar signal.
   Requires Spindel context to be bound."
  [make-signal-fn id & opts]
  (make-signal-fn id (apply progress-state opts)))

(defn set-progress!
  "Set progress on a signal."
  [signal p]
  (swap! signal set-progress p))

(defn increment!
  "Increment progress on a signal."
  ([signal] (swap! signal increment))
  ([signal amount] (swap! signal increment amount)))
