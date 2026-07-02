(ns org.replikativ.spindel-tui.sinks
  "Terminal sinks — the boundary between the reactive render-spin and the
   actual output device.

   Why a protocol?

   Spindel's DOM render layer uses a multi-method PDischarge protocol
   (create-element!/insert-child!/...) because the browser benefits from
   minimal per-element mutations. A terminal does not — JLine's
   Display.update already does its own internal line diff, so a per-cell
   discharge layer would be redundant work. The sink here is therefore a
   single method that hands a whole frame to the underlying device; the
   device (JLine or a test mock) decides what to do with it.

   This keeps the spin-side architecture clean (spin produces lines, sink
   writes them) while letting us swap in a MockSink for unit tests that
   don't want a real terminal."
  (:import [org.jline.terminal Terminal]
           [org.jline.utils Display AttributedString]))

;; dvergr issue #5 — box-drawing borders (│ ─ ┌ └ …) render as garbage under tmux.
;;
;; Our lines carry real Unicode box-drawing chars. JLine's
;; AttributedCharSequence.toAnsi(Terminal) (invoked internally by
;; Display.update) translates those code points into the terminal's terminfo
;; alternate-character-set (ACS) sequence: it wraps them in
;; enter_alt_charset_mode / exit_alt_charset_mode (smacs/rmacs — e.g. `ESC(0` …
;; `ESC(B`, or SO/SI) and substitutes the VT100 line-drawing glyphs (│→x,
;; ─→q, ┌→l, …). tmux mishandles this ACS mapping and shows garbage.
;;
;; JLine gates that translation on a JVM system property read ONCE in
;; AttributedCharSequence's static initializer:
;;   static final boolean DISABLE_ALTERNATE_CHARSET =
;;       Boolean.getBoolean("org.jline.utils.disableAlternateCharset");
;; (the property name is also exposed as TerminalBuilder/PROP_DISABLE_ALTERNATE_CHARSET).
;; When true, toAnsi skips the smacs/rmacs wrapping and emits the box chars as
;; plain UTF-8 — which tmux, and every modern UTF-8 terminal, renders correctly.
;;
;; The static field is read at first *use* of AttributedString (a runtime frame
;; render), not at class load / `:import`, so setting the property here at
;; namespace load (this ns is where AttributedString is instantiated, and
;; tui.clj requires it) is early enough. We only default it on when unset so an
;; operator can still force the legacy ACS path via
;; `-Dorg.jline.utils.disableAlternateCharset=false`.
(when (nil? (System/getProperty "org.jline.utils.disableAlternateCharset"))
  (System/setProperty "org.jline.utils.disableAlternateCharset" "true"))

(defprotocol PTerminalSink
  "A surface the render-spin can write frames to."
  (render-frame! [this lines width height]
    "Write a frame of `lines` to the sink at the given dimensions.
     Implementations must handle dimension changes between frames.")
  (invalidate! [this]
    "Drop any cached diff state so the next `render-frame!` repaints from
     scratch. Call after something external clobbers the screen (e.g. a child
     process launched via the controller's `:with-suspended`)."))

;; =============================================================================
;; JLine sink — production
;; =============================================================================

(defn- pad-to-width
  "Pad a string to exactly the given width with spaces."
  [s width]
  (let [current-len (.columnLength (AttributedString/fromAnsi (str s)))
        padding (max 0 (- width current-len))]
    (str s (apply str (repeat padding " ")))))

(defn- jline-write-frame!
  "Mirror of the legacy tui/render-lines! — resize Display, pad/truncate,
   hand the whole AttributedString list to JLine which does its own
   internal line diff."
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

(defrecord JLineSink [^Terminal terminal ^Display display last-size]
  PTerminalSink
  (render-frame! [_ lines width height]
    (let [prev @last-size]
      ;; Terminal resize between frames invalidates JLine's internal diff
      ;; — clear so the next update! repaints from scratch.
      (when (and prev (not= prev [width height]))
        (.clear display)))
    (reset! last-size [width height])
    (jline-write-frame! display lines width height))
  (invalidate! [_] (.clear display)))

(defn jline-sink
  "Build a JLineSink wrapping the given Terminal. Caller owns the
   Terminal lifecycle (raw mode / alt screen) — the sink only touches
   the Display."
  [^Terminal terminal]
  (let [d (Display. terminal true)]
    (.setDelayLineWrap d true)
    (->JLineSink terminal d (atom nil))))

;; =============================================================================
;; Mock sink — tests
;; =============================================================================

(defrecord MockSink [frames]
  PTerminalSink
  (render-frame! [_ lines width height]
    (swap! frames conj {:lines (vec lines) :width width :height height}))
  (invalidate! [_] nil))

(defn mock-sink
  "Build a MockSink that records every frame to an atom for inspection."
  []
  (->MockSink (atom [])))

(defn frames
  "Return the recorded frames from a MockSink."
  [sink]
  @(:frames sink))

(defn frame-count
  "Return the number of frames rendered to a MockSink."
  [sink]
  (count @(:frames sink)))
