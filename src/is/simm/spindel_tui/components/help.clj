(ns is.simm.spindel-tui.components.help
  "Help component for displaying key bindings.

   State is stored in a Spindel signal. Use help-state to create
   initial state with bindings."
  (:require [is.simm.spindel-tui.style.core :as style]
            [is.simm.spindel-tui.style.width :as w]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Help State
;; ---------------------------------------------------------------------------

(defn help-state
  "Create help component state.

   Bindings is a sequence of maps with :key and :desc keys, or
   a sequence of [key desc] pairs.

   Options:
     :width          - Maximum width (0 = unlimited)
     :separator      - Separator between bindings (default \" • \")
     :show-all       - Show full help instead of short (default false)
     :key-style      - Style for key text
     :desc-style     - Style for description text
     :separator-style - Style for separator
     :ellipsis       - Ellipsis when truncated (default \"…\")"
  [bindings & {:keys [width separator show-all
                      key-style desc-style separator-style ellipsis]
               :or {width 0
                    separator " • "
                    show-all false
                    ellipsis "…"}}]
  {:bindings (vec (map (fn [b]
                         (if (vector? b)
                           {:key (first b) :desc (second b)}
                           b))
                       bindings))
   :width width
   :separator separator
   :show-all show-all
   :key-style (or key-style (style/style :bold true))
   :desc-style (or desc-style (style/style :fg (style/ansi256 240)))
   :separator-style (or separator-style (style/style :fg (style/ansi256 240)))
   :ellipsis ellipsis})

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn bindings [state] (:bindings state))
(defn show-all? [state] (:show-all state))

(defn set-bindings
  "Set the bindings."
  [state bs]
  (assoc state :bindings (vec bs)))

(defn add-binding
  "Add a binding."
  [state key desc]
  (update state :bindings conj {:key key :desc desc}))

(defn set-width
  "Set the width constraint."
  [state w]
  (assoc state :width w))

(defn set-show-all
  "Set whether to show full help."
  [state show?]
  (assoc state :show-all show?))

(defn toggle-show-all
  "Toggle between short and full help."
  [state]
  (update state :show-all not))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn- render-binding
  "Render a single binding."
  [state binding]
  (let [{:keys [key-style desc-style]} state
        {:keys [key desc]} binding]
    (str (style/render key-style key)
         " "
         (style/render desc-style desc))))

(defn- short-help-view
  "Render short help (single line)."
  [state]
  (let [{:keys [bindings width separator separator-style ellipsis]} state
        sep (style/render separator-style separator)]
    (if (zero? width)
      ;; No width constraint
      (str/join sep (map #(render-binding state %) bindings))
      ;; With width constraint - truncate as needed
      (loop [result []
             remaining bindings
             current-width 0]
        (if (empty? remaining)
          (str/join sep result)
          (let [binding (first remaining)
                rendered (render-binding state binding)
                sep-width (if (empty? result) 0 (w/string-width separator))
                item-width (+ (count (:key binding)) 1 (count (:desc binding)))
                new-width (+ current-width sep-width item-width)]
            (if (and (pos? width) (> new-width width) (seq result))
              (str (str/join sep result) sep ellipsis)
              (recur (conj result rendered)
                     (rest remaining)
                     new-width))))))))

(defn- full-help-view
  "Render full help (multi-line)."
  [state]
  (let [{:keys [bindings key-style desc-style]} state
        max-key-len (reduce max 0 (map #(count (:key %)) bindings))]
    (str/join "\n"
              (for [{:keys [key desc]} bindings]
                (let [padded-key (w/pad-right key (+ max-key-len 2))]
                  (str (style/render key-style padded-key)
                       (style/render desc-style desc)))))))

(defn view
  "Render the help to a string."
  [state]
  (if (:show-all state)
    (full-help-view state)
    (short-help-view state)))

;; ---------------------------------------------------------------------------
;; Convenience
;; ---------------------------------------------------------------------------

(defn from-pairs
  "Create bindings from pairs.

   Examples:
     (from-pairs [\"j/k\" \"up/down\"] [\"q\" \"quit\"])
     (from-pairs \"j/k\" \"up/down\" \"q\" \"quit\")"
  [& args]
  (let [pairs (cond
                (and (= 1 (count args))
                     (sequential? (first args))
                     (sequential? (first (first args))))
                (first args)

                (and (seq args)
                     (every? sequential? args)
                     (every? #(= 2 (count %)) args))
                args

                :else
                (partition 2 args))]
    (mapv (fn [[k d]] {:key k :desc d}) pairs)))

;; ---------------------------------------------------------------------------
;; Signal-based API
;; ---------------------------------------------------------------------------

(defn create-signal
  "Create a help signal.
   Requires Spindel context to be bound."
  [make-signal-fn id bindings & opts]
  (make-signal-fn id (apply help-state bindings opts)))

(defn toggle!
  "Toggle show-all on a help signal."
  [signal]
  (swap! signal toggle-show-all))
