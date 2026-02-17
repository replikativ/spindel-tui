(ns is.simm.spindel-tui.components.timer
  "Countdown/stopwatch timer component.

   State is stored in a Spindel signal. Use timer-state to create
   initial state, then call tick periodically to update."
  (:require [is.simm.spindel-tui.style.core :as style]))

;; ---------------------------------------------------------------------------
;; Timer State
;; ---------------------------------------------------------------------------

(defn timer-state
  "Create initial timer state.

   Options:
     :timeout   - Time in milliseconds (default 0, counts up if 0)
     :interval  - Tick interval in milliseconds (default 1000)
     :running   - Start running (default false)
     :style     - Style for timer display
     :count-up  - If true, counts up instead of down (default false)"
  [& {:keys [timeout interval running style count-up]
      :or {timeout 0
           interval 1000
           running false
           count-up false}}]
  {:timeout timeout
   :elapsed 0
   :interval interval
   :running running
   :count-up count-up
   :last-tick (System/currentTimeMillis)
   :style style})

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn timeout [state] (:timeout state))
(defn elapsed [state] (:elapsed state))
(defn interval [state] (:interval state))
(defn running? [state] (:running state))

(defn remaining
  "Get remaining time in milliseconds (for countdown)."
  [state]
  (max 0 (- (:timeout state) (:elapsed state))))

(defn timed-out?
  "Check if countdown has finished."
  [state]
  (and (not (:count-up state))
       (pos? (:timeout state))
       (<= (remaining state) 0)))

(defn display-time
  "Get the time to display in milliseconds."
  [state]
  (if (:count-up state)
    (:elapsed state)
    (remaining state)))

;; ---------------------------------------------------------------------------
;; Timer Control
;; ---------------------------------------------------------------------------

(defn start
  "Start the timer."
  [state]
  (-> state
      (assoc :running true)
      (assoc :last-tick (System/currentTimeMillis))))

(defn stop
  "Stop the timer."
  [state]
  (assoc state :running false))

(defn toggle
  "Toggle timer running state."
  [state]
  (if (:running state)
    (stop state)
    (start state)))

(defn reset-timer
  "Reset timer to initial state."
  [state]
  (-> state
      (assoc :elapsed 0)
      (assoc :running false)
      (assoc :last-tick (System/currentTimeMillis))))

(defn set-timeout
  "Set the timeout in milliseconds."
  [state ms]
  (assoc state :timeout ms))

;; ---------------------------------------------------------------------------
;; Animation
;; ---------------------------------------------------------------------------

(defn tick
  "Update timer if running. Call this periodically."
  [state]
  (if-not (:running state)
    state
    (let [now (System/currentTimeMillis)
          delta (- now (:last-tick state))
          new-elapsed (+ (:elapsed state) delta)
          ;; Auto-stop if countdown finished
          should-stop (and (not (:count-up state))
                           (pos? (:timeout state))
                           (>= new-elapsed (:timeout state)))]
      (-> state
          (assoc :elapsed new-elapsed)
          (assoc :last-tick now)
          (assoc :running (not should-stop))))))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn- format-duration
  "Format milliseconds as a human-readable duration."
  [ms]
  (let [total-seconds (quot (Math/abs (long ms)) 1000)
        hours (quot total-seconds 3600)
        minutes (quot (rem total-seconds 3600) 60)
        seconds (rem total-seconds 60)]
    (cond
      (pos? hours)
      (format "%d:%02d:%02d" hours minutes seconds)

      (pos? minutes)
      (format "%d:%02d" minutes seconds)

      :else
      (format "0:%02d" seconds))))

(defn view
  "Render the timer to a string."
  [state]
  (let [time-ms (display-time state)
        text (format-duration time-ms)
        indicator (cond
                    (timed-out? state) " ✓"
                    (:running state) " ▶"
                    :else " ⏸")]
    (if-let [s (:style state)]
      (str (style/render s text) indicator)
      (str text indicator))))

;; ---------------------------------------------------------------------------
;; Signal-based API
;; ---------------------------------------------------------------------------

(defn create-signal
  "Create a timer signal.
   Requires Spindel context to be bound."
  [make-signal-fn id & opts]
  (make-signal-fn id (apply timer-state opts)))

(defn tick!
  "Advance a timer signal."
  [signal]
  (swap! signal tick))

(defn start!
  "Start a timer signal."
  [signal]
  (swap! signal start))

(defn stop!
  "Stop a timer signal."
  [signal]
  (swap! signal stop))

(defn toggle!
  "Toggle a timer signal."
  [signal]
  (swap! signal toggle))
