(ns org.replikativ.spindel-tui.harness
  "Headless test harness for Spindel TUIs.

   Reuses `tui/start!` with a MockSink: passing a sink means `start!`
   owns no terminal, so the raw-mode / input-reader / size-poller threads
   are all skipped — but the reactive render-spin runs exactly as in
   production. Tests can therefore drive `:on-key`, mutate signals, and
   inspect the rendered frames without a real terminal.

   Usage:
     (let [h (harness {:signals {:n 0}
                       :render (fn [sm w h] [(str \"n=\" @(:n sm))])
                       :on-key (fn [sm _ev] (swap! (:n sm) inc))})]
       (is (re-find #\"n=0\" ((:text h))))
       ((:send-key h) {:key \"x\"})
       (is (re-find #\"n=1\" ((:text h))))
       ((:stop! h)))"
  (:require [clojure.string :as str]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel-tui.sinks :as sinks]
            [org.replikativ.spindel-tui.tui :as tui]))

;; The internal ::size signal lives under tui's namespace-qualified key.
(def ^:private size-key :org.replikativ.spindel-tui.tui/size)

(defn strip-ansi
  "Remove ANSI escape sequences so rendered frames can be asserted on as
   plain text."
  [s]
  (str/replace s #"\[[0-9;?]*[A-Za-z]" ""))

(defn harness
  "Start a headless TUI over the same `{:signals :render :on-key}` spec
   `tui/start!` accepts, plus optional `:size {:width :height}` (default
   80x24). Returns a controller map:

     :stop!    (fn []) — stop the controller (call in a finally)
     :ctx      the execution context
     :signals  the resolved signal-map
     :frames   (fn []) → every recorded frame [{:lines :width :height} …]
     :last     (fn []) → the last frame's lines (vector, ANSI intact)
     :text     (fn []) → last frame joined + ANSI-stripped (for assertions)
     :send-key (fn [event]) → invoke on-key with ctx bound; settles render
     :get      (fn [k]) → deref signal k
     :set!     (fn [k v]) → reset signal k; settles render
     :resize   (fn [w h]) → set the size signal; settles render

   Render is asynchronous (it runs on the engine executor), so the
   mutating helpers sleep briefly to let the render-spin re-fire before
   returning."
  [{:keys [render on-key size] :as opts}]
  (let [sink   (sinks/mock-sink)
        ctrl   (tui/start! (assoc (dissoc opts :size) :sink sink))
        ctx    (:ctx ctrl)
        sm     (:signals ctrl)
        settle #(Thread/sleep 30)]
    (when size
      (binding [ec/*execution-context* ctx] (reset! (get sm size-key) size)))
    (settle)
    {:stop!    (:stop! ctrl)
     :ctx      ctx
     :signals  sm
     :sink     sink
     :frames   (fn [] (sinks/frames sink))
     :last     (fn [] (:lines (last (sinks/frames sink))))
     :text     (fn [] (->> (sinks/frames sink) last :lines (str/join "\n") strip-ansi))
     :send-key (fn [event]
                 (binding [ec/*execution-context* ctx]
                   (let [r (when on-key (on-key sm event))] (settle) r)))
     :get      (fn [k] (binding [ec/*execution-context* ctx] @(get sm k)))
     :set!     (fn [k v] (binding [ec/*execution-context* ctx]
                           (reset! (get sm k) v) (settle)))
     :resize   (fn [w h] (binding [ec/*execution-context* ctx]
                           (reset! (get sm size-key) {:width w :height h})
                           (settle)))
     :with-suspended (:with-suspended ctrl)}))
