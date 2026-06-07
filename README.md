# spindel-tui

A reactive terminal UI library for Clojure, built on [JLine 3](https://github.com/jline/jline3) and [Spindel](https://github.com/replikativ/spindel) signals.

## Overview

spindel-tui is a spin-native TUI runner: the render is a Spindel spin that tracks signals, and re-fires *only* when a tracked signal changes — no idle wakeups, no busy-poll. Input and terminal-size changes feed back into the same reactive graph as side-thread sources.

- **Spin-native renderer** — render is a `(spin …)` that `track`s the signal-map and emits frames via a `PTerminalSink`. Zero work when nothing changes.
- **Mailbox-fed input** — keys read on a dedicated thread, posted to a Spindel mailbox, drained by a consumer that invokes your `:on-key`.
- **Reactive resize** — terminal size lives in a signal; a side thread polls it; the render-spin picks it up via `track`.
- **Components** — spinner, progress bar, scrollable list, text input, paginator, timer, help panel.
- **Style system** — colors, borders, padding, margins, alignment via a composable style map API.
- **Markdown rendering** — Markdown with ANSI colors and syntax highlighting (Clojure via [glow](https://github.com/venantius/glow), generic languages built-in).
- **Low-level escape** — raw key reading, alternate screen, cursor control if you want to drop below the signal layer.

## Coordinates

Released on Clojars by CI (CircleCI deploys `org.replikativ/spindel-tui` from
`main`; the version is git-derived). Use the latest version from
[Clojars](https://clojars.org/org.replikativ/spindel-tui):

```clojure
;; deps.edn
org.replikativ/spindel-tui {:mvn/version "RELEASE"}
```

Or track `main` directly:

```clojure
org.replikativ/spindel-tui {:git/url "https://github.com/replikativ/spindel-tui"
                             :git/sha "<sha>"}
```

## Quick start

```clojure
(require '[org.replikativ.spindel-tui.tui :as tui])

(def t
  (tui/start!
    {:signals {:counter 0}
     :render  (fn [signals width height]
                (let [n @(:counter signals)]
                  [(str "Counter: " n)
                   ""
                   "+/- to change  q to quit"]))
     :on-key  (fn [signals {:keys [key]}]
                (case key
                  "+" (swap! (:counter signals) inc)
                  "-" (swap! (:counter signals) dec)
                  "q" :quit
                  nil))}))

;; start! returns immediately. Block until the user quits:
((:await-quit t))

;; …or stop it from any thread:
((:stop! t))
```

`start!` returns a controller map; nothing about the calling thread is blocked. Return `:quit` from `:on-key` to flip the controller's `:running` atom to false.

## Architecture

```
   JLine reader thread  ──► sync/post! ──► Spindel Mailbox
                                                │
                                                ▼
                                       consume callback
                                                │  (binds *execution-context*)
                                                ▼
                                            :on-key
                                                │  swap! on user signal
                                                ▼
   size poller thread ───► swap! ::size signal
                                                │
                                                ▼
                              Spindel engine drain (executor)
                                                │
                                                ▼
                            render-spin re-fires ──► PTerminalSink.render-frame!
                                                          │
                                                          ▼
                                                  JLine Display
```

Four threads, no shared mutable polling loop:

| Thread | Job | Wakes on |
|---|---|---|
| `spindel-tui-input` | JLine `read-key` with 100ms timeout | actual keypress |
| `spindel-tui-size` | `terminal-size` poll every 500ms | resize (rare) |
| engine executor (virtual) | drain `:signal-change` / `:mailbox-post` events | producer post |
| caller (optional) | `((:await-quit t))` parks until `:running` flips | controller `:stop!` |

The render-spin re-fires only when a tracked signal actually changes. A typical idle TUI does zero rendering work between events.

### The `:render` fn must be pure w.r.t. signals

The render fn MUST NOT call `swap!`/`reset!` on a signal that's in the signal-map — every such signal is tracked by the render-spin, and a write from inside render triggers an immediate re-fire of itself (unbounded recursion). Drive animations from a **side thread** that swaps a tick signal at a fixed cadence; the render-spin will pick it up reactively. See the [Spinner section](#spinner) for the canonical pattern.

## Controller map

`start!` returns:

```clojure
{:running      atom            ; true while alive
 :stop!        (fn [])         ; idempotent shutdown; tears down terminal
 :await-quit   (fn [])         ; parks calling thread until running flips false
 :ctx          <ec>            ; the execution context
 :sink         <PTerminalSink> ; the sink in use (JLine by default)
 :signals      map             ; resolved signal-map (incl. :tui-ctx + ::size)
 :render-count atom            ; frames rendered (test instrumentation)
 :set-mouse!   (fn [on?])      ; toggle mouse reporting at runtime
 :with-suspended (fn [thunk])} ; run thunk with the TUI suspended (see below)
```

You can run multiple TUI instances per JVM; nothing is held in a global.

### Suspending for a child process (`$EDITOR`, pagers, …)

`(:with-suspended ctrl)` takes a thunk and runs it with the TUI suspended: input
and rendering are paused, and (for an owned terminal) the tty is returned to its
normal mode + main screen so a child process can take it over. Raw mode,
alt-screen, cursor, and mouse are restored afterwards and a repaint is forced. It
runs the thunk on a dedicated thread (so the engine executor isn't blocked for the
child's lifetime) and returns a `future` of the thunk's result.

```clojure
@((:with-suspended ctrl)
  (fn []
    (-> (ProcessBuilder. [(or (System/getenv "EDITOR") "vi") path])
        (.inheritIO) (.start) (.waitFor))))
```

## Sinks

The render-spin writes frames to a `PTerminalSink`:

```clojure
(defprotocol PTerminalSink
  (render-frame! [this lines width height]))
```

Two impls ship:
- `(sinks/jline-sink terminal)` — default. Wraps a JLine `Display` (which does its own internal line diff).
- `(sinks/mock-sink)` — records every frame to an atom. Use in tests; inspect via `(sinks/frames sink)`.

## Style API

```clojure
(require '[org.replikativ.spindel-tui.style.core :as s])

(def title-style (s/style :fg s/cyan :bold true :padding [0 1]))
(s/render title-style "Hello!")

(s/render (s/style :border s/rounded-border
                   :padding 1
                   :width 40
                   :align :center)
          "Centered in a box")
```

### Colors

```clojure
s/red  s/green  s/yellow  s/blue  s/magenta  s/cyan  s/white  s/black

(s/rgb 255 128 0)
(s/hex "#ff8000")
(s/ansi256 214)
```

### Borders

```clojure
s/normal-border   ; ┌─┐│└─┘
s/rounded-border  ; ╭─╮│╰─╯
s/thick-border    ; ┏━┓┃┗━┛
s/double-border   ; ╔═╗║╚═╝
s/hidden-border   ; invisible (space-only)
```

### Layout

```clojure
(s/join-horizontal :top panel-a panel-b panel-c)
(s/join-vertical :left header body footer)
```

## Components

### Spinner

The spinner needs a tick driver. The driver MUST live on its own thread — calling `(swap! (:spin signals) spinner/tick)` from inside `:render` is the canonical footgun.

```clojure
(require '[org.replikativ.spindel-tui.components.spinner :as spinner])

;; In :signals
{:spin (spinner/spinner-state :dots :label "Loading...")}

;; Spawn a side-thread tick driver after start!:
(defn- start-spinner! [{:keys [ctx running signals]}]
  (doto (Thread.
          (fn []
            (binding [org.replikativ.spindel.engine.core/*execution-context* ctx]
              (while @running
                (swap! (:spin signals) spinner/tick)
                (Thread/sleep 80))))
          "spindel-tui-spinner")
    (.setDaemon true)
    (.start)))

(def t (tui/start! {...}))
(start-spinner! t)

;; In :render
(spinner/view @(:spin signals))  ; => "⠙ Loading..."
```

Available types: `:dots` `:line` `:pulse` `:moon` `:globe` `:clock` `:arrows` `:bouncing-bar` and more.

### Progress bar

```clojure
(require '[org.replikativ.spindel-tui.components.progress :as prog])

{:bar (prog/progress-state :width 30 :show-percent true :bar-style :thin)}

(swap! (:bar signals) prog/increment)          ; +1% — from :on-key or a side thread
(swap! (:bar signals) prog/set-progress 0.75)  ; 75%

(prog/view @(:bar signals))   ; "━━━━━━━━━━━━━━━━━━━━──────────  75%"
```

Bar styles: `:default` `:ascii` `:thin` `:thick` `:blocks` `:arrows` `:dots` `:brackets`.

### Scrollable list

```clojure
(require '[org.replikativ.spindel-tui.components.list :as lst])

{:items (lst/list-state ["Alpha" "Beta" "Gamma"]
                        :title "Pick one"
                        :height 5)}

;; In :on-key — navigation handled automatically
(swap! (:items signals) #(lst/handle-key % event))

(lst/selected-item @(:items signals))   ; => "Beta"
(lst/selected-index @(:items signals))  ; => 1

;; In :render
(lst/view @(:items signals))
```

Navigation: `↑`/`k`/`ctrl+p` up, `↓`/`j`/`ctrl+n` down, `g`/`G` top/bottom, `ctrl+u`/`ctrl+d` page.  
Items can be strings or maps with `:title` / `:description` keys.

### Text input

```clojure
(require '[org.replikativ.spindel-tui.components.text-input :as ti])

{:input (ti/text-input-state :prompt "Search: " :placeholder "type here...")}

;; In :on-key
(swap! (:input signals) #(ti/handle-key % event))

(ti/value @(:input signals))

;; In :render
(ti/view @(:input signals))
```

Supports full readline-style editing: word movement, kill/yank, `ctrl+a`/`ctrl+e`, password echo mode.

## Markdown rendering

```clojure
(require '[org.replikativ.spindel-tui.markdown :as md])

(println (md/render "# Hello\n\nSome **bold** and `code`.\n\n```clojure\n(+ 1 2)\n```"))
```

Supports headings, bold, italic, strikethrough, inline code, fenced code blocks (with syntax highlighting), blockquotes, bullet and numbered lists, tables, links, and images (as alt-text).

## Low-level API

`org.replikativ.spindel-tui.core` exposes the JLine primitives without Spindel — useful for simple scripts or testing JLine in isolation:

```clojure
(require '[org.replikativ.spindel-tui.core :as c])

(c/run-simple
  {:init    {:n 0}
   :view    (fn [state w h] [(str "n = " (:n state))])
   :on-key  (fn [state {:keys [key]}]
              (case key
                "+" (update state :n inc)
                "q" :quit
                state))})
```

This is a synchronous loop — no Spindel, no signals — and it blocks until `:quit`. Use it when you want raw JLine without the reactive machinery.

## Testing

```bash
clj -M:test
```

The test suite uses `MockSink` and drives the renderer/consumer directly — no real terminal needed. See `test/org/replikativ/spindel_tui/tui_test.clj` for the shape.

## Dependencies

| Library | Purpose |
|---|---|
| `org.jline/jline-terminal` | Terminal I/O, raw mode, display diffing |
| `org.replikativ/spindel` | Reactive signals, spins, mailboxes, execution context |
| `io.github.nextjournal/markdown` | Markdown parsing |
| `venantius/glow` | Clojure syntax highlighting |

## License

MIT
