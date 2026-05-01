# spindel-tui

A reactive terminal UI library for Clojure, built on [JLine 3](https://github.com/jline/jline3) and [Spindel](https://github.com/replikativ/spindel) signals.

## Overview

spindel-tui gives you the building blocks for full-screen terminal applications:

- **Reactive loop** — state lives in Spindel signals; the render loop re-draws only when state changes
- **Style system** — colors, borders, padding, margins, alignment via a composable style map API
- **Components** — spinner, progress bar, scrollable list, text input, paginator, timer, help panel
- **Markdown rendering** — parse and display Markdown with ANSI colors and syntax highlighting (Clojure via [glow](https://github.com/venantius/glow), generic languages built-in)
- **Low-level escape** — raw key reading, alternate screen, cursor control if you want to drop below the signal layer

## Coordinates

```clojure
;; deps.edn
org.replikativ/spindel-tui {:git/url "https://github.com/replikativ/spindel-tui"
                             :git/sha "<sha>"}
```

## Quick start

```clojure
(require '[org.replikativ.spindel-tui.tui :as tui])

(tui/start!
  {:signals {:counter 0}
   :view    (fn [signals width height]
              (let [n @(:counter signals)]
                [(str "Counter: " n)
                 ""
                 "+/- to change  q to quit"]))
   :on-key  (fn [signals {:keys [key]}]
              (case key
                "+" (swap! (:counter signals) inc)
                "-" (swap! (:counter signals) dec)
                "q" :quit
                nil))})
```

`start!` blocks until the user quits. Return `:quit` from `:on-key` to exit.

## Architecture

```
tui/start!
 ├─ creates an execution context (Spindel)
 ├─ wraps each value in :signals as a SignalRef
 ├─ enters raw mode + alternate screen
 └─ loop:
     ├─ deref all signals → compare with last render
     ├─ call :view when state or terminal size changes
     ├─ render lines via JLine Display (diff-based)
     └─ call :on-key on each key event
```

Everything runs on a single thread — no coordination needed between render and input.

## Style API

```clojure
(require '[org.replikativ.spindel-tui.style.core :as s])

;; Create a style
(def title-style (s/style :fg s/cyan :bold true :padding [0 1]))

;; Apply it
(s/render title-style "Hello!")

;; Compose: border + padding + alignment
(s/render (s/style :border s/rounded-border
                   :padding 1
                   :width 40
                   :align :center)
          "Centered in a box")
```

### Colors

```clojure
s/red  s/green  s/yellow  s/blue  s/magenta  s/cyan  s/white  s/black

(s/rgb 255 128 0)   ; 24-bit color
(s/hex "#ff8000")
(s/ansi256 214)     ; 256-color
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
;; Join panels side by side
(s/join-horizontal :top panel-a panel-b panel-c)

;; Stack panels vertically
(s/join-vertical :left header body footer)
```

## Components

### Spinner

```clojure
(require '[org.replikativ.spindel-tui.components.spinner :as spinner])

;; In :signals
{:spin (spinner/spinner-state :dots :label "Loading...")}

;; In :on-key or a background tick
(swap! (:spin signals) spinner/tick)

;; In :view
(spinner/view @(:spin signals))  ; => "⠙ Loading..."
```

Available types: `:dots` `:line` `:pulse` `:moon` `:globe` `:clock` `:arrows` `:bouncing-bar` and more.

### Progress bar

```clojure
(require '[org.replikativ.spindel-tui.components.progress :as prog])

;; In :signals
{:bar (prog/progress-state :width 30 :show-percent true :bar-style :thin)}

;; Update
(swap! (:bar signals) prog/increment)         ; +1%
(swap! (:bar signals) prog/set-progress 0.75) ; 75%

;; In :view
(prog/view @(:bar signals))  ; => "━━━━━━━━━━━━━━━━━━━━──────────  75%"
```

Bar styles: `:default` `:ascii` `:thin` `:thick` `:blocks` `:arrows` `:dots` `:brackets`.

### Scrollable list

```clojure
(require '[org.replikativ.spindel-tui.components.list :as lst])

;; In :signals
{:items (lst/list-state ["Alpha" "Beta" "Gamma"]
                        :title "Pick one"
                        :height 5)}

;; In :on-key — navigation handled automatically
(swap! (:items signals) #(lst/handle-key % event))

;; Query selection
(lst/selected-item @(:items signals))   ; => "Beta"
(lst/selected-index @(:items signals))  ; => 1

;; In :view
(lst/view @(:items signals))
```

Navigation: `↑`/`k`/`ctrl+p` up, `↓`/`j`/`ctrl+n` down, `g`/`G` top/bottom, `ctrl+u`/`ctrl+d` page.  
Items can be strings or maps with `:title` / `:description` keys.

### Text input

```clojure
(require '[org.replikativ.spindel-tui.components.text-input :as ti])

;; In :signals
{:input (ti/text-input-state :prompt "Search: " :placeholder "type here...")}

;; In :on-key
(swap! (:input signals) #(ti/handle-key % event))

;; Get value
(ti/value @(:input signals))

;; In :view
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

`org.replikativ.spindel-tui.core` exposes the JLine primitives without Spindel, useful for simple scripts or testing:

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

## Dependencies

| Library | Purpose |
|---|---|
| `org.jline/jline-terminal` | Terminal I/O, raw mode, display diffing |
| `org.replikativ/spindel` | Reactive signals and execution context |
| `io.github.nextjournal/markdown` | Markdown parsing |
| `venantius/glow` | Clojure syntax highlighting |

## License

MIT
