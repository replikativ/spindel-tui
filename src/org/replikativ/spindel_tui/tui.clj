(ns org.replikativ.spindel-tui.tui
  "Spindel-native TUI library.

   Architecture (post spin-native refactor):
   - State lives in Spindel signals — including terminal size.
   - A render-spin tracks the user's signals + the size signal and
     writes a frame to the sink at its leaf. It re-fires only when a
     tracked signal changes (no idle wakeups).
   - Input is read by a dedicated thread that posts key events to a
     Spindel mailbox; a consume callback drains the mailbox and calls
     on-key with `*execution-context*` rebound.
   - Terminal size is polled by a dedicated thread (~500ms cadence)
     that swaps the size signal on change. The render-spin tracks the
     signal, so resizes are picked up reactively without any main-loop
     coupling.

   The public API of `start!` is unchanged: callers still pass
   :signals, :view, :on-key, :execution-context.

   IMPORTANT — semantic change for downstream callers:

   The view fn must be a pure function of its arguments. Specifically,
   it must NOT call swap!/reset! on a signal that is in the signal-map,
   because every such signal is tracked by the render-spin. A write
   from inside view would trigger an immediate re-fire of the view —
   unbounded recursion. This was tolerated by the previous polling
   design (the next 16ms tick simply observed the change) but is
   incompatible with the spin-native model.

   The same applies to driving animations from inside view (e.g.
   spinner ticking). Move animation drivers to a separate side thread
   that swaps a tick signal at a fixed interval; the render-spin will
   pick it up reactively without view ever touching a signal."
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel.effects.track :refer [track]]
            [org.replikativ.spindel.incremental.interval :as iv]
            [org.replikativ.spindel-tui.sinks :as sinks])
  (:import [org.jline.terminal TerminalBuilder Terminal]
           [org.jline.utils Display AttributedString]))

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
    {:width (if (pos? w) w 80)
     :height (if (pos? h) h 24)}))

(defn enter-raw-mode! [^Terminal t]
  (.enterRawMode t))

(defn write! [^Terminal t ^String s]
  (let [w (.writer t)]
    (.write w s)
    (.flush w)))

(defn alt-screen-on! [t] (write! t "[?1049h"))
(defn alt-screen-off! [t] (write! t "[?1049l"))
(defn cursor-hide! [t] (write! t "[?25l"))
(defn cursor-show! [t] (write! t "[?25h"))

;; Mouse reporting: 1000 = button events (incl. wheel), 1006 = SGR extended
;; encoding (`ESC[<b;x;yM`), which read-key parses. Enabling captures the mouse,
;; so the terminal's own text-selection then needs Shift — hence it's toggleable
;; via start!'s :set-mouse!. (char 27) is the ESC byte these CSI strings need.
(def ^:private ^String esc (str (char 27)))
(defn mouse-on!  [t] (write! t (str esc "[?1000h" esc "[?1006h")))
(defn mouse-off! [t] (write! t (str esc "[?1000l" esc "[?1006l")))

;; =============================================================================
;; Display (legacy shim — new code should go through sinks/PTerminalSink)
;; =============================================================================

(defn create-display
  "Legacy helper retained for callers that build Display manually.
   Prefer (sinks/jline-sink terminal)."
  [^Terminal t]
  (let [d (Display. t true)]
    (.setDelayLineWrap d true)
    d))

(defn- pad-to-width
  [s width]
  (let [current-len (.columnLength (AttributedString/fromAnsi (str s)))
        padding (max 0 (- width current-len))]
    (str s (apply str (repeat padding " ")))))

(defn render-lines!
  "Legacy direct-to-Display render. Kept for backwards compatibility with
   callers that built their own loop. New code should construct a sink
   via (sinks/jline-sink terminal) and call (sinks/render-frame! ...)."
  [^Display d lines cols rows]
  (.resize d rows cols)
  (let [truncated-lines (take rows lines)
        padded-lines (mapv #(pad-to-width % cols) truncated-lines)
        empty-line (apply str (repeat cols " "))
        full-lines (into padded-lines
                         (repeat (max 0 (- rows (count padded-lines))) empty-line))
        als (mapv #(AttributedString/fromAnsi %) full-lines)
        jlist (java.util.ArrayList. ^java.util.Collection als)]
    (.update d jlist -1)))

;; =============================================================================
;; Input
;; =============================================================================

(defn- read-mouse-sgr
  "Parse an SGR mouse report after the `ESC [ <` introducer: `b;x;y(M|m)`.
   We only need the button field (the first number) to classify wheel events,
   so we accumulate it, then DRAIN through the terminating M/m so no trailing
   bytes leak into the next key. Wheel = button bit 0x40; low bit 0=up, 1=down.
   Returns {:key :scroll-up/:scroll-down} for the wheel, {:key :mouse} for a
   click/drag we don't act on, nil on a truncated sequence."
  [^org.jline.utils.NonBlockingReader r]
  (loop [btn 0 digit? false]
    (let [c (.read r 50)]
      (cond
        (< c 0) nil
        (and (>= c 48) (<= c 57)) (recur (+ (* btn 10) (- c 48)) true)
        :else
        (do ;; non-digit ends the button field; drain to the M/m terminator
          (when-not (or (= c 77) (= c 109))
            (loop [g 0]
              (let [d (.read r 50)]
                (when (and (>= d 0) (not= d 77) (not= d 109) (< g 32))
                  (recur (inc g))))))
          (if (and digit? (pos? (bit-and btn 0x40)))
            (if (zero? (bit-and btn 0x01))
              {:key :scroll-up}
              {:key :scroll-down})
            {:key :mouse}))))))

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
                  (= ch3 65) {:key :up}
                  (= ch3 66) {:key :down}
                  (= ch3 67) {:key :right}
                  (= ch3 68) {:key :left}
                  (= ch3 72) {:key "home"}
                  (= ch3 70) {:key "end"}
                  (= ch3 60) (read-mouse-sgr r) ; ESC [ < … → SGR mouse
                  (and (>= ch3 48) (<= ch3 57))
                  (let [ch4 (.read r 50)]
                    (if (= ch4 126)
                      {:key (case ch3
                              49 "home"
                              52 "end"
                              53 "page_up"
                              54 "page_down"
                              :unknown)}
                      {:key :unknown}))
                  :else {:key :unknown}))
              {:key (str "alt-" (char ch2))})))
        (= ch 13) {:key "enter"}
        (= ch 127) {:key "backspace"}
        (= ch 9) {:key "tab"}
        (= ch 3) {:key "ctrl+c"}
        ;; Any other control byte → "ctrl+<letter>" (1=ctrl+a … 26=ctrl+z). This
        ;; covers ctrl+t (20), ctrl+o (15), etc. uniformly; the few cased above
        ;; (enter/tab/ctrl+c) are matched first and keep their names.
        (and (>= ch 1) (<= ch 26)) {:key (str "ctrl+" (char (+ 96 ch)))}
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
  "Get current values of all signals as a map.
   Reads via @-deref — only use outside spins (e.g. inside on-key)."
  [signal-map]
  (into {} (keep (fn [[k v]]
                   (when-not (or (= k :tui-ctx)
                                 (= k ::size))
                     [k @v]))
                 signal-map)))

;; =============================================================================
;; Render spin
;; =============================================================================
;;
;; The render-spin tracks every user signal plus the internal ::size signal.
;; When any one changes, Spindel re-fires the spin's body, the view fn
;; computes a fresh line list, and the sink writes the frame.
;;
;; Re-render instrumentation: every frame increments render-counter, so
;; callers + tests can assert "rendered N times" rather than measuring
;; wall-clock.

(defn- make-render-spin
  "Build the render-spin. Tracks `size-sig` plus every user signal in
   `signal-map` (other than the internal entries). The doseq+track
   pattern works because partial-cps's loop/recur hazard is about
   `await` inside loops, not `track` — track installs a continuation
   and returns synchronously."
  ([sink view signal-map size-sig render-counter]
   (make-render-spin sink view signal-map size-sig render-counter nil))
  ([sink view signal-map size-sig _render-counter paused?]
   (spin
     (let [{:keys [width height]} (iv/get-new (track size-sig))
           _ (doseq [[k s] signal-map
                     :when (and (not= k :tui-ctx)
                                (not= k ::size))]
               (track s))
           lines (view signal-map width height)]
       ;; While suspended (see `:with-suspended`), keep tracking signals but DON'T
       ;; write — a child process (e.g. $EDITOR) owns the tty, so a stray frame
       ;; would corrupt its screen. The post-resume repaint emits the latest frame.
       (when-not (and paused? @paused?)
         (sinks/render-frame! sink lines width height))
       ::rendered))))

;; =============================================================================
;; Input source — dedicated reader thread + Spindel mailbox
;; =============================================================================
;;
;; JLine's reader is synchronous. To keep the spin-native model honest
;; (no busy-poll in the main thread), we run JLine reads on a dedicated
;; thread that posts each key event into a Spindel mailbox. A consume
;; callback drains the mailbox and invokes on-key with the right
;; execution context bound.
;;
;; Shutdown is cooperative: the reader thread checks `running` between
;; reads (short JLine timeout for responsiveness), and posts a
;; ::shutdown sentinel when it exits so the consume callback unblocks.

(def ^:private shutdown-sentinel ::shutdown)

(defn- start-input-reader!
  "Spawn a thread that reads JLine keys and posts them to mbx.
   Returns the thread (caller is responsible for waiting on it or letting
   the JVM reclaim it on shutdown).

   sync/post! enqueues onto the context's event queue, so the reader
   thread must have *execution-context* bound."
  [ctx ^Terminal terminal mbx running-atom paused-atom]
  (doto (Thread.
          ^Runnable
          (fn []
            (binding [ec/*execution-context* ctx]
              (try
                (while @running-atom
                  ;; Short timeout so we wake up periodically to check
                  ;; `running` even when the user isn't typing.
                  (if (and paused-atom @paused-atom)
                    ;; Suspended: don't touch the tty — a child process owns it.
                    (Thread/sleep 50)
                    (when-let [event (read-key terminal 100)]
                      (sync/post! mbx event))))
                (catch InterruptedException _ nil)
                (catch Throwable t
                  (binding [*out* *err*]
                    (println "input-reader error:" (.getMessage t))))
                (finally
                  (sync/post! mbx shutdown-sentinel)))))
          "spindel-tui-input")
    (.setDaemon true)
    (.start)))

(defn- start-size-poller!
  "Spawn a thread that polls the terminal size every `interval-ms` and
   swaps `size-sig` when the value changes. *execution-context* is
   bound so the swap can enqueue the signal-change event."
  [ctx ^Terminal terminal size-sig running-atom interval-ms]
  (doto (Thread.
          ^Runnable
          (fn []
            (binding [ec/*execution-context* ctx]
              (try
                (while @running-atom
                  (let [now (terminal-size terminal)]
                    (when (not= now @size-sig)
                      (swap! size-sig (constantly now))))
                  (Thread/sleep (long interval-ms)))
                (catch InterruptedException _ nil)
                (catch Throwable t
                  (binding [*out* *err*]
                    (println "size-poller error:" (.getMessage t)))))))
          "spindel-tui-size")
    (.setDaemon true)
    (.start)))

(defn- start-input-consumer!
  "Drain key events from mbx and call on-key with execution context bound.
   Uses the raw 2-arity mailbox CPS interface (resolve/reject) to avoid
   loop/recur+await — that combination is a known partial-cps hang risk.

   The consumer re-arms itself from inside resolve; the JVM trampoline
   inside Mailbox handles deeply-nested resumes safely.

   `*execution-context*` is rebound at every entry point that may run on
   the engine's drain thread: the resolve callback, the reject callback,
   and the recursive consume. Without this, mailbox internals (which
   call enqueue-event! to schedule resumes) crash with 'no execution
   context bound'."
  [ctx mbx on-key signal-map running-atom paused-atom]
  (letfn [(consume []
            (when @running-atom
              (binding [ec/*execution-context* ctx]
                (mbx
                  (fn [event]
                    (binding [ec/*execution-context* ctx]
                      (when @running-atom
                        (cond
                          (= event shutdown-sentinel)
                          nil

                          :else
                          (do
                            ;; Drop events while suspended (the child owns input);
                            ;; keep re-arming so the consumer survives the pause.
                            (when-not (and paused-atom @paused-atom)
                              (try
                                (when (= :quit (on-key signal-map event))
                                  (reset! running-atom false))
                                (catch Throwable t
                                  (binding [*out* *err*]
                                    (println "on-key error:" (.getMessage t))))))
                            (consume))))))
                  (fn [err]
                    (binding [ec/*execution-context* ctx
                              *out* *err*]
                      (println "mailbox error:" err)))))))]
    (consume)))

;; =============================================================================
;; TUI Runner
;; =============================================================================

(defn start!
  "Start a Spindel TUI. Returns a controller map immediately — the
   calling thread is NOT blocked.

   Options:
   - :signals    Map of {key initial-value} — converted to Spindel signals.
   - :render     (fn [signal-map width height] -> seq-of-strings)
                 Pure function. MUST NOT call swap!/reset! on tracked
                 signals — that would trigger an unbounded re-fire of
                 the render-spin. Drive animations from a side thread
                 that swaps a tick signal at a fixed cadence; the
                 render fn can then read that signal and incorporate
                 the tick into the frame.
   - :on-key     (fn [signal-map key-event] -> any) — return :quit to exit.
   - :execution-context  Optional existing Spindel execution context.
                         If absent, a fresh one is created.
   - :sink       Optional PTerminalSink. Defaults to a JLineSink over
                 a freshly-built system Terminal. Pass a MockSink for
                 tests.

   Returns a controller map:
     {:running     atom  — true while the TUI is alive
      :stop!       fn    — flip running false + clean up terminal
      :await-quit  fn    — block calling thread until running is false
      :ctx         ec    — the execution context
      :sink        sink  — the PTerminalSink in use
      :signals     map   — the resolved signal-map (including :tui-ctx
                            and the internal ::size signal)}

   Typical usage:
     (def t (start! {:signals {...} :render r :on-key h}))
     ;; ... interact with t ...
     ((:stop! t))                  ; clean shutdown
   Or to block the calling thread until the TUI quits:
     ((:await-quit (start! {...})))

   Architecture:
   - Render is a Spindel spin that tracks the user's signals + an
     internal ::size signal. Re-fires only on signal change.
   - Input is read by a dedicated thread that posts to a Spindel
     mailbox; a consumer drains and invokes on-key with ctx bound.
   - Terminal size is polled by a dedicated thread (~500ms) that
     swaps the size signal on change.
   - No work runs on the caller's thread — everything is signal-driven
     from the engine's executor."
  [{:keys [signals render on-key execution-context sink mouse?]
    :or {mouse? true}}]
  (let [ctx           (or execution-context (ctx/create-execution-context))
        terminal      (when-not sink (create-terminal))
        own-terminal? (some? terminal)
        sink          (or sink (sinks/jline-sink terminal))
        running       (atom true)
        stopped?      (atom false)
        paused?       (atom false)
        ;; The terminal's normal attributes, captured BEFORE raw mode so
        ;; `:with-suspended` can hand a child process (e.g. $EDITOR) a cooked tty.
        orig-attrs    (when own-terminal? (.getAttributes ^Terminal terminal))]

    (binding [ec/*execution-context* ctx]
      (when own-terminal? (enter-raw-mode! terminal))

      (let [render-counter (atom 0)
            size-sig (make-signal ::size
                                  (if terminal
                                    (terminal-size terminal)
                                    {:width 80 :height 24}))
            signal-map (into {:tui-ctx  ctx
                              ::size    size-sig
                              ::repaint (make-signal ::repaint 0)}
                             (map (fn [[k v]]
                                    [k (make-signal k v)])
                                  signals))]

        (when own-terminal?
          (alt-screen-on! terminal)
          (cursor-hide! terminal)
          (when mouse? (mouse-on! terminal)))

        ;; Wire the render-spin. Once invoked, Spindel drives re-runs
        ;; via the executor whenever a tracked signal changes.
        (let [the-spin (make-render-spin sink render signal-map size-sig render-counter paused?)]
          (the-spin
            (fn [_] (swap! render-counter inc))
            (fn [e]
              (when-let [w (some-> terminal .writer)]
                (.write w (str "\nrender-spin error: " (.getMessage ^Throwable e) "\n"))
                (.flush w)))))

        ;; Input: reader thread → Spindel mailbox → consume callback.
        (let [input-mbx (sync/create-mailbox ctx)]
          (when own-terminal?
            (start-input-reader! ctx terminal input-mbx running paused?)
            (start-input-consumer! ctx input-mbx on-key signal-map running paused?)))

        ;; Size: side thread polls terminal-size, swaps signal on change.
        (when own-terminal?
          (start-size-poller! ctx terminal size-sig running 500))

        (let [stop! (fn []
                     ;; Idempotent. Daemon threads exit on their next
                     ;; loop iteration once `running` flips false; the
                     ;; longest such loop is the size-poller at 500ms.
                     (when (compare-and-set! stopped? false true)
                       (reset! running false)
                       (when own-terminal?
                         (try (mouse-off! terminal) (catch Throwable _))
                         (try (cursor-show! terminal) (catch Throwable _))
                         (try (alt-screen-off! terminal) (catch Throwable _))
                         (try (.close terminal) (catch Throwable _)))))
              await-quit (fn []
                           (try
                             (while @running (Thread/sleep 200))
                             (finally (stop!))))]
          {:running      running
           :stop!        stop!
           :await-quit   await-quit
           :ctx          ctx
           :sink         sink
           :signals      signal-map
           :render-count render-counter
           ;; Toggle mouse reporting at runtime (e.g. to let the user select +
           ;; copy text with the native terminal). No-op for caller-owned sinks.
           :set-mouse!   (fn [on?]
                           (when own-terminal?
                             (if on? (mouse-on! terminal) (mouse-off! terminal))))
           ;; Run `thunk` with the TUI suspended — input + rendering paused, and
           ;; (for an owned terminal) the tty returned to its normal mode + main
           ;; screen — so a child process (e.g. `$EDITOR`) can take over the
           ;; terminal. Restores raw mode / alt-screen / mouse and forces a
           ;; repaint afterwards. Runs on a dedicated thread (so the engine
           ;; executor isn't blocked for the child's lifetime) and returns a
           ;; future of the thunk's result.
           :with-suspended
           (fn [thunk]
             (future
               (binding [ec/*execution-context* ctx]
                 (reset! paused? true)
                 (try
                   (when own-terminal?
                     (when mouse? (mouse-off! terminal))
                     (cursor-show! terminal)
                     (alt-screen-off! terminal)
                     (.setAttributes ^Terminal terminal orig-attrs))
                   (thunk)
                   (finally
                     (when own-terminal?
                       (enter-raw-mode! terminal)
                       (alt-screen-on! terminal)
                       (cursor-hide! terminal)
                       (when mouse? (mouse-on! terminal)))
                     (reset! paused? false)
                     ;; The child clobbered the screen, so JLine's diff cache is
                     ;; stale — invalidate it, then bump ::repaint to emit a full
                     ;; frame onto the freshly-restored screen.
                     (sinks/invalidate! sink)
                     (swap! (get signal-map ::repaint) inc)))))) })))))

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

(defn demo-on-key [signals {:keys [key]}]
  (case key
    "q" :quit
    "ctrl+c" :quit
    "+" (swap! (:counter signals) inc)
    "-" (swap! (:counter signals) dec)
    (swap! (:messages signals)
           #(vec (take 100 (cons (str "Key: " (pr-str key)) %))))))

(defn demo!
  "Run the built-in demo. Returns the controller; blocks on await-quit."
  []
  (let [t (start! {:signals {:counter 0
                             :messages ["Welcome to Spindel TUI!"
                                        "Press keys to interact."]}
                   :render demo-view
                   :on-key demo-on-key})]
    ((:await-quit t))
    t))

(comment
  (def t (demo!))
  ((:stop! t)))
