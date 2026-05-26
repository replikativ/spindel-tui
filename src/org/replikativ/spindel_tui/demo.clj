(ns org.replikativ.spindel-tui.demo
  "Integrated demo showcasing all spindel-tui components."
  (:require [org.replikativ.spindel-tui.tui :as tui]
            [org.replikativ.spindel-tui.style.core :as s]
            [org.replikativ.spindel-tui.components.text-input :as ti]
            [org.replikativ.spindel-tui.components.list :as lst]
            [org.replikativ.spindel-tui.components.spinner :as spinner]
            [org.replikativ.spindel-tui.components.progress :as prog]
            [org.replikativ.spindel.engine.core :as ec]
            [clojure.string :as str]))

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
;; Demo Driver
;; ---------------------------------------------------------------------------

(defn- start-spinner-driver!
  "Tick the spinner signal every 80ms on a daemon thread. Stops when
   the controller's running atom flips false.

   This is the canonical pattern for animation in the spin-native
   model: drivers live OUTSIDE the render-spin and swap! a signal at
   a fixed cadence. The render-spin tracks the spinner signal and
   re-renders only on tick (no busy-poll, no swap! inside render)."
  [{:keys [ctx running signals]}]
  (doto (Thread.
          ^Runnable
          (fn []
            (binding [ec/*execution-context* ctx]
              (while @running
                (try
                  (swap! (:spinner signals) spinner/tick)
                  (Thread/sleep 80)
                  (catch InterruptedException _ nil)))))
          "spindel-tui-demo-spinner")
    (.setDaemon true)
    (.start)))

(defn demo!
  "Run the component demo. Returns the controller; blocks on await-quit."
  []
  (let [t (tui/start!
            {:signals {:mode      :input
                       :input     (ti/text-input-state
                                    :prompt "Enter text: "
                                    :placeholder "Type here...")
                       :list      (lst/list-state
                                    ["Option 1" "Option 2" "Option 3"
                                     "Option 4" "Option 5" "Option 6"]
                                    :title "Select Item"
                                    :height 5)
                       :spinner   (spinner/spinner-state :dots :label "Processing...")
                       :progress  (prog/progress-state :width 20 :show-percent true)
                       :messages  ["Welcome to the demo!"
                                   "Press Tab to switch modes"
                                   "+/- to adjust progress"]}
             :render demo-view
             :on-key demo-on-key})]
    (start-spinner-driver! t)
    ((:await-quit t))
    t))

(defn -main [& _args]
  (demo!))

(comment
  (def t (demo!))
  ((:stop! t)))
