(ns org.replikativ.spindel-tui.tui
  "Spindel-native TUI library.

   Simple architecture:
   - State in Spindel signals
   - Single-threaded render loop (no futures, no binding issues)
   - Input updates signals directly
   - View re-renders when state changes"
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.signal :as sig]
            [clojure.string :as str])
  (:import [org.jline.terminal TerminalBuilder Terminal]
           [org.jline.utils Display AttributedString AttributedStringBuilder AttributedStyle]))

;; =============================================================================
;; Terminal
;; =============================================================================

(defn create-terminal ^Terminal []
  (-> (TerminalBuilder/builder)
      (.system true)
      (.build)))

(defn terminal-size [^Terminal t]
  (let [s (.getSize t)
        w (.getColumns s)
        h (.getRows s)]
    ;; Fallback to reasonable defaults if size is 0
    {:width (if (pos? w) w 80)
     :height (if (pos? h) h 24)}))

(defn enter-raw-mode! [^Terminal t]
  (.enterRawMode t))

(defn write! [^Terminal t ^String s]
  (let [w (.writer t)]
    (.write w s)
    (.flush w)))

(defn alt-screen-on! [t] (write! t "\u001b[?1049h"))
(defn alt-screen-off! [t] (write! t "\u001b[?1049l"))
(defn cursor-hide! [t] (write! t "\u001b[?25l"))
(defn cursor-show! [t] (write! t "\u001b[?25h"))

;; =============================================================================
;; Display
;; =============================================================================

(defn create-display [^Terminal t]
  (let [d (Display. t true)]
    ;; Delay line wrap to avoid cursor jumping issues at right margin
    (.setDelayLineWrap d true)
    d))

(defn- pad-to-width
  "Pad a string to exactly the given width with spaces."
  [s width]
  (let [current-len (.columnLength (AttributedString/fromAnsi (str s)))
        padding (max 0 (- width current-len))]
    (str s (apply str (repeat padding " ")))))

(defn render-lines! [^Display d lines cols rows]
  ;; Resize display to match terminal
  (.resize d rows cols)
  ;; Pad all lines to full width, truncate to rows, and fill to full height
  (let [;; First truncate to max rows to avoid JLine diff confusion
        truncated-lines (take rows lines)
        padded-lines (mapv #(pad-to-width % cols) truncated-lines)
        ;; Fill remaining rows with empty lines
        empty-line (apply str (repeat cols " "))
        full-lines (into padded-lines
                         (repeat (max 0 (- rows (count padded-lines))) empty-line))
        als (mapv #(AttributedString/fromAnsi %) full-lines)
        jlist (java.util.ArrayList. ^java.util.Collection als)]
    (.update d jlist -1)))

;; =============================================================================
;; Input
;; =============================================================================

(defn read-key
  "Read key with timeout. Returns nil on timeout, or {:key ... :char ...}"
  [^Terminal t timeout-ms]
  (let [r (.reader t)
        ch (.read r (long timeout-ms))]
    (when (>= ch 0)
      (cond
        (= ch 27) ; ESC
        (let [ch2 (.read r 50)]
          (if (< ch2 0)
            {:key "escape"}
            (if (= ch2 91) ; CSI sequence: ESC [
              (let [ch3 (.read r 50)]
                (cond
                  ;; Arrow keys: ESC [ A/B/C/D
                  (= ch3 65) {:key :up}
                  (= ch3 66) {:key :down}
                  (= ch3 67) {:key :right}
                  (= ch3 68) {:key :left}
                  ;; Home/End (some terminals): ESC [ H / ESC [ F
                  (= ch3 72) {:key "home"}
                  (= ch3 70) {:key "end"}
                  ;; Extended sequences: ESC [ <num> ~
                  ;; Page Up: ESC [ 5 ~, Page Down: ESC [ 6 ~
                  ;; Home: ESC [ 1 ~, End: ESC [ 4 ~
                  (and (>= ch3 48) (<= ch3 57))  ; digit
                  (let [ch4 (.read r 50)]
                    (if (= ch4 126) ; ~
                      {:key (case ch3
                              49 "home"      ; ESC [ 1 ~
                              52 "end"       ; ESC [ 4 ~
                              53 "page_up"   ; ESC [ 5 ~
                              54 "page_down" ; ESC [ 6 ~
                              :unknown)}
                      {:key :unknown}))
                  :else {:key :unknown}))
              {:key (str "alt-" (char ch2))})))
        (= ch 13) {:key "enter"}
        (= ch 127) {:key "backspace"}
        (= ch 9) {:key "tab"}
        ;; Ctrl key combinations (Ctrl+A = 1, Ctrl+Z = 26)
        (= ch 3) {:key "ctrl+c"}
        (= ch 4) {:key "ctrl+d"}   ; Scroll down (vim)
        (= ch 10) {:key "ctrl+j"}  ; Scroll down line
        (= ch 11) {:key "ctrl+k"}  ; Scroll up line
        (= ch 14) {:key "ctrl+n"}  ; Scroll down line
        (= ch 16) {:key "ctrl+p"}  ; Scroll up line
        (= ch 21) {:key "ctrl+u"}  ; Scroll up (vim)
        :else {:key (str (char ch)) :char (char ch)}))))

;; =============================================================================
;; Signals
;; =============================================================================

(defn make-signal
  "Create a signal with given id and initial value. Must be called with context bound."
  [id initial]
  (let [s (sig/->SignalRef id initial)]
    (sig/ensure-signal-initialized! s)
    s))

(defn signal-values
  "Get current values of all signals as a map."
  [signal-map]
  (into {} (keep (fn [[k v]]
                   (when-not (= k :tui-ctx)  ;; Skip non-signal entries
                     [k @v]))
                 signal-map)))

;; =============================================================================
;; TUI Runner
;; =============================================================================

(defonce ^:private running (atom false))

(defn stop! []
  (reset! running false))

(defn start!
  "Start a Spindel TUI.

   Options:
   - :signals    Map of {key initial-value} - will be converted to Spindel signals
   - :view       (fn [signal-map width height] -> seq-of-strings)
   - :on-key     (fn [signal-map key-event] -> any) - return :quit to exit
   - :execution-context  Optional existing Spindel execution context to share

   Everything runs in a single thread with Spindel context bound.
   The TUI context is available as :tui-ctx in the signal-map for use in futures."
  [{:keys [signals view on-key execution-context]}]
  (let [ctx (or execution-context (ctx/create-execution-context))
        terminal (create-terminal)]

    ;; Bind context for entire TUI lifetime
    (binding [ec/*execution-context* ctx]
      (enter-raw-mode! terminal)

      (let [display (create-display terminal)
            ;; Create signals
            signal-map (into {:tui-ctx ctx}  ;; Expose context for futures
                             (map (fn [[k v]]
                                    [k (make-signal k v)])
                                  signals))]

        (reset! running true)

        (try
          (alt-screen-on! terminal)
          (cursor-hide! terminal)

          ;; Main loop - single thread, no complexity
          (loop [last-state nil
                 last-size nil]
            (when @running
              (let [current-state (signal-values signal-map)
                    {:keys [width height] :as current-size} (terminal-size terminal)
                    ;; Detect status transition from :running to :idle for full rerender
                    was-running? (= :running (:status last-state))
                    now-idle? (= :idle (:status current-state))
                    ;; Detect terminal resize
                    size-changed? (and last-size (not= current-size last-size))]

                ;; Clear display on status transition or terminal resize
                ;; This forces a full rerender to clear any rendering artifacts
                (when (or (and was-running? now-idle?) size-changed?)
                  (.clear display))

                ;; Re-render if state changed, size changed, or first render
                (when (or (nil? last-state)
                          (not= current-state last-state)
                          size-changed?)
                  (let [lines (view signal-map width height)]
                    (render-lines! display lines width height)))

                ;; Read input (with short timeout for responsive loop)
                (when-let [event (read-key terminal 16)]
                  (when (= :quit (on-key signal-map event))
                    (reset! running false)))

                (when @running
                  (recur current-state current-size)))))

          :done  ;; Don't return signals to avoid print-method conflict

          (finally
            (reset! running false)
            (cursor-show! terminal)
            (alt-screen-off! terminal)
            (.close terminal)))))))

;; =============================================================================
;; Demo
;; =============================================================================

(defn demo-view [signals width height]
  (let [cnt @(:counter signals)
        msgs @(:messages signals)
        w (max 40 width)
        h (max 10 height)
        bar (str "+" (apply str (repeat (- w 2) "-")) "+")
        empty-line (str "|" (apply str (repeat (- w 2) " ")) "|")
        pad (fn [s]
              (let [text (str "| " s)
                    padding (max 0 (- w (count text) 1))]
                (str text (apply str (repeat padding " ")) "|")))]
    (concat
      [bar
       (pad (str "Spindel TUI - Counter: " cnt))
       bar]
      (map pad (take (- h 6) msgs))
      (repeat (max 0 (- h 6 (count msgs))) empty-line)
      [(pad "q:quit  +/-:counter  other:log")
       bar])))

(defn demo-on-key [signals {:keys [key char]}]
  (case key
    "q" :quit
    "ctrl+c" :quit
    "+" (swap! (:counter signals) inc)
    "-" (swap! (:counter signals) dec)
    (swap! (:messages signals)
           #(vec (take 100 (cons (str "Key: " (pr-str key)) %))))))

(defn demo! []
  (start! {:signals {:counter 0
                   :messages ["Welcome to Spindel TUI!"
                              "Press keys to interact."]}
         :view demo-view
         :on-key demo-on-key}))

(comment
  (demo!)
  (stop!))
