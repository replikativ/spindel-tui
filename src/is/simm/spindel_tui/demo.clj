(ns is.simm.spindel-tui.demo
  "Integrated demo showcasing all spindel-tui components."
  (:require [is.simm.spindel-tui.tui :as tui]
            [is.simm.spindel-tui.style.core :as s]
            [is.simm.spindel-tui.style.border :as b]
            [is.simm.spindel-tui.components.text-input :as ti]
            [is.simm.spindel-tui.components.list :as lst]
            [is.simm.spindel-tui.components.spinner :as spinner]
            [is.simm.spindel-tui.components.progress :as prog]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.signal :as sig]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Demo State Setup
;; ---------------------------------------------------------------------------

(defn- make-signal [id initial]
  (let [s (sig/->SignalRef id initial)]
    (sig/ensure-signal-initialized! s)
    s))

;; ---------------------------------------------------------------------------
;; Demo View
;; ---------------------------------------------------------------------------

(defn- pad-line
  "Pad a line to exactly the given width."
  [line target-width]
  (let [current-width (s/string-width line)
        padding (max 0 (- target-width current-width))]
    (str line (apply str (repeat padding " ")))))

(defn- box-line
  "Create a line inside a box: │ content ... │"
  [content inner-width]
  (let [content-width (s/string-width content)
        padding (max 0 (- inner-width content-width))]
    (str "│ " content (apply str (repeat padding " ")) " │")))

(defn demo-view
  "Render demo with all components."
  [signals width height]
  (let [;; Read all signal values
        mode @(:mode signals)
        input-state @(:input signals)
        list-state @(:list signals)
        spinner-state @(:spinner signals)
        progress-state @(:progress signals)
        messages @(:messages signals)

        ;; Layout calculations - use full width
        inner-width (- width 4)  ; Account for "│ " and " │"
        panel-height (max 5 (quot (- height 10) 2))

        ;; Header
        title "Spindel TUI Component Demo"
        header (str "╭─ " (s/render (s/style :fg s/cyan :bold true) title) " "
                    (apply str (repeat (max 0 (- width (count title) 6)) "─")) "╮")

        ;; Mode indicator
        mode-str (pad-line (str "  Mode: " (name mode) " │ Tab: switch │ +/-: progress │ q: quit") width)

        ;; Separator
        separator (str "├" (apply str (repeat (- width 2) "─")) "┤")

        ;; Content based on mode
        content-lines
        (case mode
          :input
          (let [input-view (ti/view input-state)
                label "Text Input"]
            [(str "│ " (s/render (s/style :bold true) label)
                  (apply str (repeat (max 0 (- inner-width (count label))) " ")) " │")
             (box-line input-view inner-width)
             (box-line "" inner-width)])

          :list
          (let [list-view (lst/view list-state)
                lines (str/split-lines list-view)
                label (str "List (" (inc (lst/selected-index list-state)) "/" (lst/item-count list-state) ")")]
            (concat
              [(str "│ " (s/render (s/style :bold true) label)
                    (apply str (repeat (max 0 (- inner-width (count label))) " ")) " │")]
              (map #(box-line % inner-width) (take panel-height lines))))

          [(box-line "" inner-width)])

        ;; Status section
        spinner-view (spinner/view spinner-state)
        progress-view (prog/view progress-state)
        status-lines
        [(str "│ " (s/render (s/style :bold true) "Status")
              (apply str (repeat (max 0 (- inner-width 6)) " ")) " │")
         (box-line spinner-view inner-width)
         (box-line progress-view inner-width)]

        ;; Messages section
        msg-label "Messages"
        msg-lines
        (concat
          [(str "│ " (s/render (s/style :bold true :fg s/yellow) msg-label)
                (apply str (repeat (max 0 (- inner-width (count msg-label))) " ")) " │")]
          (map #(box-line (str "  " %) inner-width) (take 3 messages)))

        ;; Footer
        footer (str "╰" (apply str (repeat (- width 2) "─")) "╯")]

    ;; Combine all lines, padding each to full width
    (map #(pad-line % width)
         (concat
           [header]
           [mode-str]
           [separator]
           content-lines
           [separator]
           status-lines
           [separator]
           msg-lines
           [footer]))))

;; ---------------------------------------------------------------------------
;; Demo Key Handler
;; ---------------------------------------------------------------------------

(defn demo-on-key
  "Handle keys for demo."
  [signals {:keys [key] :as event}]
  (let [mode @(:mode signals)]
    (cond
      ;; Quit
      (or (= key "q") (= key "ctrl+c"))
      :quit

      ;; Switch mode
      (= key "tab")
      (do
        (swap! (:mode signals) #(if (= % :input) :list :input))
        (swap! (:messages signals) #(cons "Switched mode" (take 10 %))))

      ;; Progress controls
      (= key "+")
      (do
        (swap! (:progress signals) prog/increment)
        (swap! (:messages signals) #(cons "Progress +1%" (take 10 %))))

      (= key "-")
      (do
        (swap! (:progress signals) prog/decrement)
        (swap! (:messages signals) #(cons "Progress -1%" (take 10 %))))

      ;; Mode-specific handling
      (= mode :input)
      (do
        (swap! (:input signals) #(ti/handle-key % event))
        (when (= key "enter")
          (let [val (ti/value @(:input signals))]
            (swap! (:messages signals) #(cons (str "Input: " val) (take 10 %)))
            (swap! (:input signals) ti/reset))))

      (= mode :list)
      (do
        (swap! (:list signals) #(lst/handle-key % event))
        (when (= key "enter")
          (let [item (lst/selected-item @(:list signals))]
            (swap! (:messages signals) #(cons (str "Selected: " item) (take 10 %)))))))))

;; ---------------------------------------------------------------------------
;; Main Loop with Spinner Animation
;; ---------------------------------------------------------------------------

(defonce ^:private running (atom false))

(defn stop! [] (reset! running false))

(defn demo!
  "Run the component demo."
  []
  (let [exec-ctx (ctx/create-execution-context)
        terminal (tui/create-terminal)]

    (binding [ec/*execution-context* exec-ctx]
      (tui/enter-raw-mode! terminal)

      (let [display (tui/create-display terminal)
            signals {:mode (make-signal :mode :input)
                     :input (make-signal :input (ti/text-input-state
                                                  :prompt "Enter text: "
                                                  :placeholder "Type here..."))
                     :list (make-signal :list (lst/list-state
                                                ["Option 1" "Option 2" "Option 3"
                                                 "Option 4" "Option 5" "Option 6"]
                                                :title "Select Item"
                                                :height 5))
                     :spinner (make-signal :spinner (spinner/spinner-state :dots :label "Processing..."))
                     :progress (make-signal :progress (prog/progress-state :width 20 :show-percent true))
                     :messages (make-signal :messages ["Welcome to the demo!"
                                                       "Press Tab to switch modes"
                                                       "+/- to adjust progress"])}]

        (reset! running true)

        (try
          (tui/alt-screen-on! terminal)
          (tui/cursor-hide! terminal)

          (loop [last-state nil
                 last-tick (System/currentTimeMillis)]
            (when @running
              (let [;; Tick spinner
                    now (System/currentTimeMillis)
                    _ (when (>= (- now last-tick) 80)
                        (swap! (:spinner signals) spinner/tick))

                    ;; Get current state
                    current-state {:mode @(:mode signals)
                                   :input (ti/value @(:input signals))
                                   :list (lst/selected-index @(:list signals))
                                   :spinner (:frame @(:spinner signals))
                                   :progress (prog/percent @(:progress signals))}
                    {:keys [width height]} (tui/terminal-size terminal)]

                ;; Render if state changed
                (when (or (nil? last-state) (not= current-state last-state))
                  (let [lines (demo-view signals width height)]
                    (tui/render-lines! display lines width height)))

                ;; Handle input
                (when-let [event (tui/read-key terminal 16)]
                  (when (= :quit (demo-on-key signals event))
                    (reset! running false)))

                (when @running
                  (recur current-state
                         (if (>= (- now last-tick) 80) now last-tick))))))

          :done

          (finally
            (reset! running false)
            (tui/cursor-show! terminal)
            (tui/alt-screen-off! terminal)
            (.close terminal)))))))

(defn -main [& _args]
  (demo!))

(comment
  (demo!)
  (stop!))
