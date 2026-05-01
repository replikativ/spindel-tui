(ns org.replikativ.spindel-tui.components.spinner
  "Animated spinner component.

   State is stored in a Spindel signal. Use spinner-state to create
   initial state, then call tick to advance the animation."
  (:require [org.replikativ.spindel-tui.style.core :as style]))

;; ---------------------------------------------------------------------------
;; Spinner Types
;; ---------------------------------------------------------------------------

(def spinner-types
  "Predefined spinner animations with frames and interval."
  {:line      {:frames ["|" "/" "-" "\\"]
               :interval 100}

   :dots      {:frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"]
               :interval 80}

   :dot       {:frames ["⣾" "⣽" "⣻" "⢿" "⡿" "⣟" "⣯" "⣷"]
               :interval 100}

   :jump      {:frames ["⢄" "⢂" "⢁" "⡁" "⡈" "⡐" "⡠"]
               :interval 100}

   :pulse     {:frames ["█" "▓" "▒" "░"]
               :interval 125}

   :points    {:frames ["∙∙∙" "●∙∙" "∙●∙" "∙∙●"]
               :interval 140}

   :globe     {:frames ["🌍" "🌎" "🌏"]
               :interval 250}

   :moon      {:frames ["🌑" "🌒" "🌓" "🌔" "🌕" "🌖" "🌗" "🌘"]
               :interval 125}

   :monkey    {:frames ["🙈" "🙉" "🙊"]
               :interval 300}

   :meter     {:frames ["▱▱▱" "▰▱▱" "▰▰▱" "▰▰▰" "▰▰▱" "▰▱▱" "▱▱▱"]
               :interval 140}

   :hamburger {:frames ["☱" "☲" "☴" "☲"]
               :interval 300}

   :ellipsis  {:frames ["" "." ".." "..."]
               :interval 300}

   :arrows    {:frames ["←" "↖" "↑" "↗" "→" "↘" "↓" "↙"]
               :interval 100}

   :bouncing-bar {:frames ["[    ]" "[=   ]" "[==  ]" "[=== ]" "[ ===]" "[  ==]" "[   =]" "[    ]"]
                  :interval 100}

   :clock     {:frames ["🕐" "🕑" "🕒" "🕓" "🕔" "🕕" "🕖" "🕗" "🕘" "🕙" "🕚" "🕛"]
               :interval 100}})

;; ---------------------------------------------------------------------------
;; Spinner State
;; ---------------------------------------------------------------------------

(defn spinner-state
  "Create initial spinner state.

   Type can be a keyword like :dots, :line, :moon, etc.
   or a map with :frames and :interval keys.

   Options:
     :style - Style to apply to spinner (optional)
     :label - Optional label text to show after spinner"
  [type & {:keys [style label]}]
  (let [spinner-type (if (keyword? type)
                       (get spinner-types type (:dots spinner-types))
                       type)]
    {:spinner-type spinner-type
     :frame 0
     :last-tick (System/currentTimeMillis)
     :style style
     :label label}))

;; ---------------------------------------------------------------------------
;; Animation
;; ---------------------------------------------------------------------------

(defn tick
  "Advance the spinner to the next frame if enough time has passed.
   Returns updated state."
  [state]
  (let [now (System/currentTimeMillis)
        {:keys [spinner-type frame last-tick]} state
        interval (:interval spinner-type)
        elapsed (- now last-tick)]
    (if (>= elapsed interval)
      (let [frames (:frames spinner-type)
            next-frame (mod (inc frame) (count frames))]
        (-> state
            (assoc :frame next-frame)
            (assoc :last-tick now)))
      state)))

(defn reset-spinner
  "Reset spinner to first frame."
  [state]
  (-> state
      (assoc :frame 0)
      (assoc :last-tick (System/currentTimeMillis))))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn view
  "Render the spinner to a string."
  [state]
  (let [{:keys [frame spinner-type style label]} state
        frames (:frames spinner-type)
        current-frame (get frames frame (first frames))
        spinner-str (if style
                      (style/render style current-frame)
                      current-frame)]
    (if label
      (str spinner-str " " label)
      spinner-str)))

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn interval
  "Get the animation interval in milliseconds."
  [state]
  (get-in state [:spinner-type :interval]))

(defn set-label
  "Set the spinner label."
  [state new-label]
  (assoc state :label new-label))

(defn set-style
  "Set the spinner style."
  [state new-style]
  (assoc state :style new-style))

;; ---------------------------------------------------------------------------
;; Signal-based API
;; ---------------------------------------------------------------------------

(defn create-signal
  "Create a spinner signal.
   Requires Spindel context to be bound."
  [make-signal-fn id type & opts]
  (make-signal-fn id (apply spinner-state type opts)))

(defn tick!
  "Advance a spinner signal."
  [signal]
  (swap! signal tick))
