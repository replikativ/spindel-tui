(ns org.replikativ.spindel-tui.components.tree
  "Collapsible tree component.

   The tree is data-driven: callers pass a vector of nodes via
   `(set-roots state nodes)`. A node is a map with at minimum:

     {:id      <opaque, must be unique within the tree>
      :label   <string to display>
      :children [Node ...]}        ; optional

   Additional keys are passed through to the renderer (lets callers
   attach domain payload like {:kind :room :agents [...]}). A custom
   :label-fn can be supplied to render a single line from a node.

   State is stored in a Spindel signal:

     {:roots      [Node ...]
      :flat       [{:node Node :depth N :path [...id...]} ...]  ; cached
      :expanded   #{id ...}        ; ids whose children are visible
      :cursor     N                ; index into :flat
      :height     N                ; visible viewport height (0 = unlimited)
      :offset     N                ; first visible flat-index
      :cursor-prefix \"> \"
      :item-prefix  \"  \"
      :label-fn  <fn>}

   The flat list is recomputed on every state transition that could
   change visibility (set-roots, toggle, set-expanded). Cursor
   movements are O(1).

   Navigation:
     ↑/k/ctrl+p   — previous visible row
     ↓/j/ctrl+n   — next visible row
     ←/h          — collapse (if open) or move to parent
     →/l          — expand (if has children + closed) or move to first child
     enter/space  — toggle expand/collapse
     home/g       — first row
     end/G        — last visible row
     pgup/ctrl+u  — half-page up
     pgdown/ctrl+d — half-page down"
  (:require [org.replikativ.spindel-tui.style.core :as style]))

;; ---------------------------------------------------------------------------
;; Key bindings
;; ---------------------------------------------------------------------------

(def default-keys
  {:cursor-up   #{:up "k" "ctrl+p"}
   :cursor-down #{:down "j" "ctrl+n"}
   :collapse    #{:left "h"}
   :expand      #{:right "l"}
   :toggle      #{"enter" "space" " "}
   :page-up     #{"page_up" "ctrl+u"}
   :page-down   #{"page_down" "ctrl+d"}
   :go-to-start #{"home" "g"}
   :go-to-end   #{"end" "G"}})

(defn- matches? [event binding-set]
  (contains? binding-set (:key event)))

;; ---------------------------------------------------------------------------
;; Flattening
;; ---------------------------------------------------------------------------

(defn- flatten-tree
  "Walk roots producing the visible-row list given the expanded set.
   Each row is {:node, :depth, :path, :has-children?, :expanded?}."
  [roots expanded]
  (let [out (volatile! (transient []))
        walk (fn walk [nodes depth path]
               (doseq [n nodes]
                 (let [id (:id n)
                       cs (:children n)
                       hc (boolean (seq cs))
                       ex (contains? expanded id)
                       p  (conj path id)]
                   (vswap! out conj! {:node          n
                                      :depth         depth
                                      :path          p
                                      :has-children? hc
                                      :expanded?     ex})
                   (when (and hc ex)
                     (walk cs (inc depth) p)))))]
    (walk roots 0 [])
    (persistent! @out)))

;; ---------------------------------------------------------------------------
;; Default label-fn
;; ---------------------------------------------------------------------------

(defn default-label-fn
  "Render one line for a single tree row. Callers can override via
   `:label-fn` to add badges, agent counts, etc."
  [{:keys [node has-children? expanded?] :as _row}]
  (let [chevron (cond
                  (not has-children?) " "
                  expanded?           "▾"
                  :else               "▸")]
    (str chevron " " (or (:label node) (str (:id node))))))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defn tree-state
  "Create initial tree state.

   Options:
     :roots             — initial vector of root nodes (default [])
     :expanded          — initial set of expanded ids (default #{})
     :height            — viewport height in rows, 0 = unlimited (default 0)
     :cursor            — initial cursor index into the flat list (default 0)
     :cursor-prefix     — prefix for the selected row (default \"> \")
     :item-prefix       — prefix for unselected rows (default \"  \")
     :cursor-style      — style/style for the selected line
     :label-fn          — (fn [row] -> string) override row rendering"
  [& {:keys [roots expanded height cursor
             cursor-prefix item-prefix
             cursor-style label-fn]
      :or   {roots         []
             expanded      #{}
             height        0
             cursor        0
             cursor-prefix "> "
             item-prefix   "  "
             cursor-style  (style/style :fg style/cyan :bold true)
             label-fn      default-label-fn}}]
  (let [flat (flatten-tree roots expanded)]
    {:roots         (vec roots)
     :expanded      (set expanded)
     :flat          flat
     :cursor        (max 0 (min cursor (max 0 (dec (count flat)))))
     :offset        0
     :height        height
     :cursor-prefix cursor-prefix
     :item-prefix   item-prefix
     :cursor-style  cursor-style
     :label-fn      label-fn}))

(defn- recompute-flat
  "Rebuild :flat from current :roots/:expanded. Tries to preserve the
   visible row under the cursor by id-path so external roots-change
   doesn't jump focus."
  [state]
  (let [old-flat       (:flat state)
        old-cursor     (:cursor state)
        anchor-path    (some-> old-flat (nth old-cursor nil) :path)
        new-flat       (flatten-tree (:roots state) (:expanded state))
        new-cursor     (or (some (fn [[i row]]
                                   (when (= (:path row) anchor-path) i))
                                 (map-indexed vector new-flat))
                           (min old-cursor (max 0 (dec (count new-flat)))))]
    (assoc state
           :flat   new-flat
           :cursor (max 0 new-cursor))))

;; ---------------------------------------------------------------------------
;; Public mutators
;; ---------------------------------------------------------------------------

(defn set-roots
  "Swap the visible tree to a new roots vector. Preserves expanded set
   and tries to keep the cursor anchored to the same node by id-path."
  [state roots]
  (-> state
      (assoc :roots (vec roots))
      recompute-flat))

(defn set-expanded
  "Replace the expanded set wholesale."
  [state expanded]
  (-> state
      (assoc :expanded (set expanded))
      recompute-flat))

(defn- cursor-row [state]
  (nth (:flat state) (:cursor state) nil))

(defn toggle-cursor
  "Toggle the expanded state of the node currently under the cursor."
  [state]
  (if-let [{:keys [node has-children?]} (cursor-row state)]
    (if has-children?
      (let [id (:id node)
            ex (:expanded state)
            ex' (if (contains? ex id) (disj ex id) (conj ex id))]
        (-> state
            (assoc :expanded ex')
            recompute-flat))
      state)
    state))

(defn collapse-cursor
  "If the cursor is on an expanded parent, collapse it. Otherwise, move
   the cursor to its parent row (if any)."
  [state]
  (if-let [{:keys [node has-children? expanded? path]} (cursor-row state)]
    (cond
      (and has-children? expanded?)
      (toggle-cursor state)

      (> (count path) 1)
      (let [parent-path (vec (butlast path))
            new-idx (some (fn [[i row]]
                            (when (= (:path row) parent-path) i))
                          (map-indexed vector (:flat state)))]
        (cond-> state
          new-idx (assoc :cursor new-idx)))

      :else state)
    state))

(defn expand-cursor
  "If the cursor is on a closed parent, expand it. Otherwise, move the
   cursor to its first child (no-op if no children)."
  [state]
  (if-let [{:keys [has-children? expanded?]} (cursor-row state)]
    (cond
      (and has-children? (not expanded?))
      (toggle-cursor state)

      (and has-children? expanded?)
      (update state :cursor (fn [c] (min (inc c) (dec (count (:flat state))))))

      :else state)
    state))

(defn- clamp-cursor [state]
  (let [last-idx (max 0 (dec (count (:flat state))))]
    (update state :cursor #(max 0 (min last-idx %)))))

(defn move-up    [state]     (-> state (update :cursor dec)               clamp-cursor))
(defn move-down  [state]     (-> state (update :cursor inc)               clamp-cursor))
(defn go-start   [state]     (assoc state :cursor 0))
(defn go-end     [state]     (assoc state :cursor (max 0 (dec (count (:flat state))))))

(defn page-up    [state]
  (let [step (max 1 (quot (or (:height state) 10) 2))]
    (-> state (update :cursor #(- % step)) clamp-cursor)))

(defn page-down  [state]
  (let [step (max 1 (quot (or (:height state) 10) 2))]
    (-> state (update :cursor #(+ % step)) clamp-cursor)))

(defn handle-key
  "Process a key event against the tree state. Returns the new state."
  ([state event]
   (handle-key state event default-keys))
  ([state event keys]
   (cond
     (matches? event (:cursor-up   keys)) (move-up    state)
     (matches? event (:cursor-down keys)) (move-down  state)
     (matches? event (:collapse    keys)) (collapse-cursor state)
     (matches? event (:expand      keys)) (expand-cursor   state)
     (matches? event (:toggle      keys)) (toggle-cursor   state)
     (matches? event (:page-up     keys)) (page-up    state)
     (matches? event (:page-down   keys)) (page-down  state)
     (matches? event (:go-to-start keys)) (go-start   state)
     (matches? event (:go-to-end   keys)) (go-end     state)
     :else state)))

;; ---------------------------------------------------------------------------
;; Queries
;; ---------------------------------------------------------------------------

(defn selected-row
  "Return the row map under the cursor, or nil if the tree is empty."
  [state]
  (cursor-row state))

(defn selected-node
  "Convenience: just the :node map under the cursor (or nil)."
  [state]
  (:node (selected-row state)))

(defn selected-path
  "Convenience: the id-path under the cursor (or nil)."
  [state]
  (:path (selected-row state)))

(defn flat-rows
  "Return the cached flat-row vector (visible rows in order)."
  [state]
  (:flat state))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(defn- visible-window
  "Scroll-window calculation. Returns [first-idx last-idx] into :flat."
  [state]
  (let [h         (:height state)
        cur       (:cursor state)
        total     (count (:flat state))]
    (if (or (<= h 0) (>= h total))
      [0 (max 0 (dec total))]
      (let [;; Keep cursor in view; bias toward keeping some padding
            top (max 0 (min (- cur (quot h 2))
                            (- total h)))]
        [top (+ top h -1)]))))

(defn view
  "Render the tree as a vector of strings (one per visible row)."
  [state]
  (let [flat         (:flat state)
        label-fn     (or (:label-fn state) default-label-fn)
        cursor       (:cursor state)
        cursor-pre   (:cursor-prefix state)
        item-pre     (:item-prefix state)
        cur-style    (:cursor-style state)
        [first-idx last-idx] (visible-window state)]
    (cond
      (zero? (count flat))
      [""]

      :else
      (mapv (fn [i]
              (let [row    (nth flat i)
                    depth  (:depth row)
                    indent (apply str (repeat depth "  "))
                    label  (label-fn row)
                    selected? (= i cursor)
                    prefix (if selected? cursor-pre item-pre)
                    line   (str prefix indent label)]
                (if (and selected? cur-style)
                  (style/render cur-style line)
                  line)))
            (range first-idx (inc last-idx))))))
