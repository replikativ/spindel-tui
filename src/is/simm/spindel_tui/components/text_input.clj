(ns is.simm.spindel-tui.components.text-input
  "Text input component with cursor movement and editing.

   State is stored in a Spindel signal. Use create-text-input to create
   a signal with initial state, then use the handle-key function to process
   input events."
  (:require [is.simm.spindel-tui.style.core :as style]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Default Key Bindings
;; ---------------------------------------------------------------------------

(def default-keys
  "Default key bindings for text input."
  {:character-forward    #{"right" "ctrl+f"}
   :character-backward   #{"left" "ctrl+b"}
   :word-forward         #{"alt+right" "ctrl+right" "alt+f"}
   :word-backward        #{"alt+left" "ctrl+left" "alt+b"}
   :delete-word-backward #{"alt+backspace" "ctrl+w"}
   :delete-word-forward  #{"alt+delete" "alt+d"}
   :delete-after-cursor  #{"ctrl+k"}
   :delete-before-cursor #{"ctrl+u"}
   :delete-char-backward #{"backspace" "ctrl+h"}
   :delete-char-forward  #{"delete" "ctrl+d"}
   :line-start           #{"home" "ctrl+a"}
   :line-end             #{"end" "ctrl+e"}})

(defn- matches-binding?
  "Check if a key event matches any key in a binding set."
  [{:keys [key]} binding-set]
  (contains? binding-set key))

;; ---------------------------------------------------------------------------
;; Text Input State
;; ---------------------------------------------------------------------------

(defn text-input-state
  "Create initial text input state.

   Options:
     :prompt           - Prompt string (default \"> \")
     :placeholder      - Placeholder text when empty
     :value            - Initial value (default \"\")
     :echo-mode        - :normal, :password, or :none (default :normal)
     :echo-char        - Character for password mode (default \\*)
     :char-limit       - Maximum characters (0 = unlimited)
     :width            - Display width (0 = unlimited)
     :prompt-style     - Style for prompt
     :text-style       - Style for text
     :placeholder-style - Style for placeholder
     :cursor-style     - Style for cursor
     :focused          - Start focused (default true)"
  [& {:keys [prompt placeholder value echo-mode echo-char char-limit width
             prompt-style text-style placeholder-style cursor-style focused]
      :or {prompt "> "
           value ""
           echo-mode :normal
           echo-char \*
           char-limit 0
           width 0
           focused true}}]
  {:prompt prompt
   :placeholder placeholder
   :value (vec value)
   :pos (count value)
   :echo-mode echo-mode
   :echo-char echo-char
   :char-limit char-limit
   :width width
   :offset 0
   :focused focused
   :prompt-style prompt-style
   :text-style text-style
   :placeholder-style (or placeholder-style
                          (style/style :fg (style/ansi256 240)))
   :cursor-style (or cursor-style
                     (style/style :reverse true))
   :keys default-keys})

;; ---------------------------------------------------------------------------
;; State Accessors
;; ---------------------------------------------------------------------------

(defn value
  "Get the current value as a string."
  [state]
  (apply str (:value state)))

(defn set-value
  "Set the value and move cursor to end."
  [state v]
  (let [chars (vec v)
        chars (if (and (pos? (:char-limit state))
                       (> (count chars) (:char-limit state)))
                (subvec chars 0 (:char-limit state))
                chars)]
    (-> state
        (assoc :value chars)
        (assoc :pos (count chars)))))

(defn position [state] (:pos state))
(defn focused? [state] (:focused state))
(defn focus [state] (assoc state :focused true))
(defn blur [state] (assoc state :focused false))

(defn reset
  "Clear the input value."
  [state]
  (-> state
      (assoc :value [])
      (assoc :pos 0)
      (assoc :offset 0)))

;; ---------------------------------------------------------------------------
;; Cursor Movement
;; ---------------------------------------------------------------------------

(defn- clamp [n min-val max-val]
  (max min-val (min max-val n)))

(defn- set-cursor
  "Set cursor position, clamping to valid range."
  [state pos]
  (assoc state :pos (clamp pos 0 (count (:value state)))))

(defn cursor-start [state] (set-cursor state 0))
(defn cursor-end [state] (set-cursor state (count (:value state))))

(defn- whitespace? [c]
  (Character/isWhitespace (char c)))

(defn- word-backward
  "Move cursor backward one word."
  [state]
  (let [{:keys [value pos]} state]
    (if (or (zero? pos) (empty? value))
      state
      (loop [i (dec pos)]
        (cond
          (neg? i)
          (set-cursor state 0)

          (whitespace? (nth value i))
          (recur (dec i))

          :else
          (loop [j i]
            (if (or (neg? j) (whitespace? (nth value j)))
              (set-cursor state (inc j))
              (recur (dec j)))))))))

(defn- word-forward
  "Move cursor forward one word."
  [state]
  (let [{:keys [value pos]} state
        len (count value)]
    (if (or (>= pos len) (empty? value))
      state
      (loop [i pos]
        (cond
          (>= i len)
          (set-cursor state len)

          (whitespace? (nth value i))
          (recur (inc i))

          :else
          (loop [j i]
            (if (or (>= j len) (whitespace? (nth value j)))
              (set-cursor state j)
              (recur (inc j)))))))))

;; ---------------------------------------------------------------------------
;; Text Editing
;; ---------------------------------------------------------------------------

(defn- delete-char-backward
  "Delete character before cursor."
  [state]
  (let [{:keys [value pos]} state]
    (if (and (pos? pos) (seq value))
      (-> state
          (assoc :value (into (subvec value 0 (dec pos))
                              (subvec value pos)))
          (update :pos dec))
      state)))

(defn- delete-char-forward
  "Delete character after cursor."
  [state]
  (let [{:keys [value pos]} state]
    (if (and (< pos (count value)) (seq value))
      (assoc state :value (into (subvec value 0 pos)
                                (subvec value (inc pos))))
      state)))

(defn- delete-word-backward
  "Delete word before cursor."
  [state]
  (let [{:keys [value pos]} state]
    (if (or (zero? pos) (empty? value))
      state
      (let [new-state (word-backward state)
            new-pos (:pos new-state)]
        (-> state
            (assoc :value (into (subvec value 0 new-pos)
                                (subvec value pos)))
            (assoc :pos new-pos))))))

(defn- delete-word-forward
  "Delete word after cursor."
  [state]
  (let [{:keys [value pos]} state]
    (if (or (>= pos (count value)) (empty? value))
      state
      (let [new-state (word-forward state)
            new-pos (:pos new-state)]
        (assoc state :value (into (subvec value 0 pos)
                                  (subvec value new-pos)))))))

(defn- delete-before-cursor
  "Delete everything before cursor."
  [state]
  (let [{:keys [value pos]} state]
    (-> state
        (assoc :value (subvec value pos))
        (assoc :pos 0)
        (assoc :offset 0))))

(defn- delete-after-cursor
  "Delete everything after cursor."
  [state]
  (let [{:keys [value pos]} state]
    (assoc state :value (subvec value 0 pos))))

(defn- insert-chars
  "Insert characters at cursor position."
  [state chars]
  (let [{:keys [value pos char-limit]} state
        chars (filterv #(or (>= (int %) 32) (= % \tab)) chars)
        chars (if (and (pos? char-limit)
                       (> (+ (count value) (count chars)) char-limit))
                (subvec chars 0 (max 0 (- char-limit (count value))))
                chars)]
    (if (empty? chars)
      state
      (-> state
          (assoc :value (into (into (subvec value 0 pos) chars)
                              (subvec value pos)))
          (update :pos + (count chars))))))

;; ---------------------------------------------------------------------------
;; Key Handling
;; ---------------------------------------------------------------------------

(defn handle-key
  "Handle a key event, returning updated state.
   Key event should have :key (string) and optionally :char."
  [state event]
  (if-not (:focused state)
    state
    (let [keys (:keys state)
          key-str (:key event)]
      (cond
        (matches-binding? event (:character-backward keys))
        (set-cursor state (dec (:pos state)))

        (matches-binding? event (:character-forward keys))
        (set-cursor state (inc (:pos state)))

        (matches-binding? event (:word-backward keys))
        (word-backward state)

        (matches-binding? event (:word-forward keys))
        (word-forward state)

        (matches-binding? event (:line-start keys))
        (cursor-start state)

        (matches-binding? event (:line-end keys))
        (cursor-end state)

        (matches-binding? event (:delete-char-backward keys))
        (delete-char-backward state)

        (matches-binding? event (:delete-char-forward keys))
        (delete-char-forward state)

        (matches-binding? event (:delete-word-backward keys))
        (delete-word-backward state)

        (matches-binding? event (:delete-word-forward keys))
        (delete-word-forward state)

        (matches-binding? event (:delete-before-cursor keys))
        (delete-before-cursor state)

        (matches-binding? event (:delete-after-cursor keys))
        (delete-after-cursor state)

        ;; Regular character input - single printable character
        (and (string? key-str)
             (= 1 (count key-str))
             (>= (int (first key-str)) 32))
        (insert-chars state (vec key-str))

        :else state))))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn- echo-transform
  "Transform text according to echo mode."
  [state text]
  (case (:echo-mode state)
    :normal text
    :password (apply str (repeat (count text) (:echo-char state)))
    :none ""))

(defn view
  "Render the text input to a string."
  [state]
  (let [{:keys [prompt placeholder value pos focused
                prompt-style text-style placeholder-style cursor-style
                echo-mode]} state
        prompt-str (if prompt-style
                     (style/render prompt-style prompt)
                     prompt)]
    (if (and (empty? value) placeholder (not (str/blank? placeholder)))
      ;; Show placeholder
      (let [placeholder-str (if focused
                              (str (style/render cursor-style
                                                 (subs placeholder 0 1))
                                   (style/render placeholder-style
                                                 (subs placeholder 1)))
                              (style/render placeholder-style placeholder))]
        (str prompt-str placeholder-str))

      ;; Show value with cursor
      (let [text (apply str value)
            transformed (echo-transform state text)
            before (subs transformed 0 (min pos (count transformed)))
            cursor-char (if (< pos (count transformed))
                          (subs transformed pos (inc pos))
                          " ")
            after (if (< pos (count transformed))
                    (subs transformed (inc pos))
                    "")

            before-str (if text-style
                         (style/render text-style before)
                         before)
            cursor-str (if focused
                         (style/render cursor-style cursor-char)
                         cursor-char)
            after-str (if text-style
                        (style/render text-style after)
                        after)]
        (str prompt-str before-str cursor-str after-str)))))

;; ---------------------------------------------------------------------------
;; Signal-based API
;; ---------------------------------------------------------------------------

(defn create-signal
  "Create a text input signal with initial options.
   Requires Spindel context to be bound."
  [make-signal-fn id & opts]
  (make-signal-fn id (apply text-input-state opts)))

(defn update-signal!
  "Update a text input signal with a key event."
  [signal event]
  (swap! signal #(handle-key % event)))
