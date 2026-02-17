(ns is.simm.spindel-tui.core
  "Spindel-native TUI - Low-level JLine primitives.

   This provides a non-Spindel TUI runner for testing JLine directly.
   For the signal-based TUI, use is.simm.spindel-tui.tui instead."
  (:require [clojure.string :as str])
  (:import [org.jline.terminal TerminalBuilder Terminal]
           [org.jline.utils Display AttributedString AttributedStringBuilder AttributedStyle]))

;; =============================================================================
;; Terminal Management
;; =============================================================================

(defn create-terminal
  "Create a JLine terminal instance."
  []
  (-> (TerminalBuilder/builder)
      (.system true)
      (.build)))

(defn get-size
  "Get terminal dimensions as {:width w :height h}."
  [^Terminal terminal]
  (let [size (.getSize terminal)]
    {:width (.getColumns size)
     :height (.getRows size)}))

(defn enter-raw-mode!
  "Enter raw mode for character-by-character input."
  [^Terminal terminal]
  (.enterRawMode terminal))

(defn enter-alt-screen!
  "Enter alternate screen buffer."
  [^Terminal terminal]
  (let [writer (.writer terminal)]
    (.write writer "\u001b[?1049h")
    (.flush writer)))

(defn exit-alt-screen!
  "Exit alternate screen buffer."
  [^Terminal terminal]
  (let [writer (.writer terminal)]
    (.write writer "\u001b[?1049l")
    (.flush writer)))

(defn hide-cursor!
  "Hide the cursor."
  [^Terminal terminal]
  (let [writer (.writer terminal)]
    (.write writer "\u001b[?25l")
    (.flush writer)))

(defn show-cursor!
  "Show the cursor."
  [^Terminal terminal]
  (let [writer (.writer terminal)]
    (.write writer "\u001b[?25h")
    (.flush writer)))

;; =============================================================================
;; Display/Rendering
;; =============================================================================

(defn create-display
  "Create a JLine Display for efficient terminal updates."
  [^Terminal terminal]
  (Display. terminal true))

(defn render-lines!
  "Render lines to the display. Each line is a string."
  [^Display display lines]
  (let [attributed-lines (mapv #(AttributedString. (str %)) lines)
        ;; JLine 3.30 needs mutable Java List
        java-list (java.util.ArrayList. ^java.util.Collection attributed-lines)]
    (.update display java-list -1)))

(defn clear-display!
  "Clear the display."
  [^Display display]
  (.clear display))

;; =============================================================================
;; Styled Text Helpers
;; =============================================================================

(def styles
  {:bold (-> (AttributedStyle/DEFAULT) (.bold))
   :dim (-> (AttributedStyle/DEFAULT) (.faint))
   :italic (-> (AttributedStyle/DEFAULT) (.italic))
   :underline (-> (AttributedStyle/DEFAULT) (.underline))
   :inverse (-> (AttributedStyle/DEFAULT) (.inverse))
   :red (-> (AttributedStyle/DEFAULT) (.foreground AttributedStyle/RED))
   :green (-> (AttributedStyle/DEFAULT) (.foreground AttributedStyle/GREEN))
   :yellow (-> (AttributedStyle/DEFAULT) (.foreground AttributedStyle/YELLOW))
   :blue (-> (AttributedStyle/DEFAULT) (.foreground AttributedStyle/BLUE))
   :magenta (-> (AttributedStyle/DEFAULT) (.foreground AttributedStyle/MAGENTA))
   :cyan (-> (AttributedStyle/DEFAULT) (.foreground AttributedStyle/CYAN))})

(defn styled
  "Create a styled AttributedString."
  [style-key text]
  (if-let [style (get styles style-key)]
    (AttributedString. (str text) style)
    (AttributedString. (str text))))

(defn join-styled
  "Join multiple AttributedStrings into one line."
  [& parts]
  (let [builder (AttributedStringBuilder.)]
    (doseq [part parts]
      (if (instance? AttributedString part)
        (.append builder ^AttributedString part)
        (.append builder (str part))))
    (.toAttributedString builder)))

;; =============================================================================
;; Simple Input Reading
;; =============================================================================

(defn read-key
  "Read a single key from terminal. Blocking with timeout.
   Returns map with :type and :key, or nil on timeout."
  [^Terminal terminal timeout-ms]
  (let [reader (.reader terminal)
        ch (.read reader (long timeout-ms))]
    (when (>= ch 0)
      (cond
        (= ch 27) ;; Escape sequence
        (let [ch2 (.read reader 50)]
          (if (< ch2 0)
            {:type :key :key "escape"}
            (if (= ch2 91) ;; CSI
              (let [ch3 (.read reader 50)]
                (case ch3
                  65 {:type :key :key :up}
                  66 {:type :key :key :down}
                  67 {:type :key :key :right}
                  68 {:type :key :key :left}
                  {:type :key :key :unknown}))
              {:type :key :key (str "alt-" (char ch2))})))

        (= ch 13) {:type :key :key "enter"}
        (= ch 127) {:type :key :key "backspace"}
        (= ch 9) {:type :key :key "tab"}
        (= ch 3) {:type :key :key "ctrl+c"}

        :else {:type :key :key (str (char ch)) :char (char ch)}))))

;; =============================================================================
;; Simple TUI Program (Non-Spindel, for testing JLine)
;; =============================================================================

(defn run-simple
  "Run a simple TUI program without Spindel (for testing JLine).

   Options:
   - :init - Initial state
   - :view - (fn [state width height] -> seq of strings)
   - :on-key - (fn [state key-event] -> new-state or :quit)"
  [{:keys [init view on-key]}]
  (let [terminal (create-terminal)
        _ (enter-raw-mode! terminal)
        display (create-display terminal)]
    (try
      (enter-alt-screen! terminal)
      (hide-cursor! terminal)

      (loop [state init]
        (let [{:keys [width height]} (get-size terminal)
              lines (view state width height)]
          (render-lines! display lines)

          (if-let [event (read-key terminal 100)]
            (let [new-state (on-key state event)]
              (if (= new-state :quit)
                state
                (recur new-state)))
            (recur state))))

      (finally
        (show-cursor! terminal)
        (exit-alt-screen! terminal)
        (.close terminal)))))
