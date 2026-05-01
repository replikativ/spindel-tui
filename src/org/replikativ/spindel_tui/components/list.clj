(ns org.replikativ.spindel-tui.components.list
  "Scrollable list component with item selection.

   State is stored in a Spindel signal. Use list-state to create
   initial state, then use handle-key to process navigation events."
  (:require [org.replikativ.spindel-tui.style.core :as style]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Key Bindings
;; ---------------------------------------------------------------------------

(def default-keys
  "Default key bindings for list navigation."
  {:cursor-up     #{"up" "k" "ctrl+p"}
   :cursor-down   #{"down" "j" "ctrl+n"}
   :page-up       #{"pgup" "ctrl+u"}
   :page-down     #{"pgdown" "ctrl+d"}
   :go-to-start   #{"home" "g"}
   :go-to-end     #{"end" "G"}})

(defn- matches-binding?
  [event binding-set]
  (contains? binding-set (:key event)))

;; ---------------------------------------------------------------------------
;; Item Protocol
;; ---------------------------------------------------------------------------

(defprotocol ListItem
  "Protocol for items in a list."
  (item-title [item] "Get the display title for the item.")
  (item-description [item] "Get an optional description for the item."))

(extend-protocol ListItem
  String
  (item-title [s] s)
  (item-description [_] nil)

  clojure.lang.IPersistentMap
  (item-title [m] (or (:title m) (:name m) (str m)))
  (item-description [m] (or (:description m) (:desc m))))

;; ---------------------------------------------------------------------------
;; List State
;; ---------------------------------------------------------------------------

(defn list-state
  "Create initial list state.

   Options:
     :height           - Visible height in lines (0 = show all)
     :width            - Width constraint (0 = unlimited)
     :cursor           - Initial cursor position (default 0)
     :title            - Optional list title
     :show-title       - Show title (default true if title provided)
     :cursor-style     - Style for selected item
     :item-style       - Style for unselected items
     :title-style      - Style for title
     :cursor-prefix    - Prefix for selected item (default \"> \")
     :item-prefix      - Prefix for unselected items (default \"  \")
     :show-descriptions - Show item descriptions (default false)
     :infinite-scroll  - Wrap around at ends (default false)"
  [items & {:keys [height width cursor title show-title
                   cursor-style item-style title-style
                   cursor-prefix item-prefix
                   show-descriptions infinite-scroll]
            :or {height 0
                 width 0
                 cursor 0
                 show-title true
                 cursor-prefix "> "
                 item-prefix "  "
                 show-descriptions false
                 infinite-scroll false}}]
  {:items (vec items)
   :cursor (min cursor (max 0 (dec (count items))))
   :offset 0
   :height height
   :width width
   :title title
   :show-title (and show-title title)
   :cursor-style (or cursor-style
                     (style/style :fg style/cyan :bold true))
   :item-style item-style
   :title-style (or title-style
                    (style/style :bold true))
   :cursor-prefix cursor-prefix
   :item-prefix item-prefix
   :show-descriptions show-descriptions
   :infinite-scroll infinite-scroll
   :keys default-keys})

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn items [state] (:items state))
(defn item-count [state] (count (:items state)))
(defn selected-index [state] (:cursor state))
(defn selected-item [state] (get (:items state) (:cursor state)))

(defn set-items
  "Set the items, adjusting cursor if needed."
  [state new-items]
  (let [new-items (vec new-items)
        new-cursor (min (:cursor state) (max 0 (dec (count new-items))))]
    (-> state
        (assoc :items new-items)
        (assoc :cursor new-cursor)
        (assoc :offset 0))))

(defn select
  "Select an item by index."
  [state index]
  (let [cnt (item-count state)
        new-cursor (if (zero? cnt)
                     0
                     (max 0 (min index (dec cnt))))]
    (assoc state :cursor new-cursor)))

(defn set-height [state height] (assoc state :height height))

;; ---------------------------------------------------------------------------
;; Navigation
;; ---------------------------------------------------------------------------

(defn- visible-height
  "Get the number of visible items."
  [state]
  (let [h (:height state)
        total (item-count state)]
    (if (or (zero? h) (> total h))
      (if (zero? h) total h)
      total)))

(defn- update-offset
  "Update scroll offset to keep cursor visible."
  [state]
  (let [{:keys [cursor offset height]} state
        visible (visible-height state)]
    (cond
      (zero? height) state
      (< cursor offset) (assoc state :offset cursor)
      (>= cursor (+ offset visible)) (assoc state :offset (- cursor visible -1))
      :else state)))

(defn cursor-up
  "Move cursor up."
  [state]
  (let [{:keys [cursor infinite-scroll]} state
        cnt (item-count state)]
    (if (zero? cnt)
      state
      (let [new-cursor (dec cursor)
            new-cursor (if (neg? new-cursor)
                         (if infinite-scroll (dec cnt) 0)
                         new-cursor)]
        (-> state
            (assoc :cursor new-cursor)
            update-offset)))))

(defn cursor-down
  "Move cursor down."
  [state]
  (let [{:keys [cursor infinite-scroll]} state
        cnt (item-count state)]
    (if (zero? cnt)
      state
      (let [new-cursor (inc cursor)
            new-cursor (if (>= new-cursor cnt)
                         (if infinite-scroll 0 (dec cnt))
                         new-cursor)]
        (-> state
            (assoc :cursor new-cursor)
            update-offset)))))

(defn page-up
  "Move cursor up by one page."
  [state]
  (let [page-size (max 1 (visible-height state))
        new-cursor (max 0 (- (:cursor state) page-size))]
    (-> state
        (assoc :cursor new-cursor)
        update-offset)))

(defn page-down
  "Move cursor down by one page."
  [state]
  (let [page-size (max 1 (visible-height state))
        cnt (item-count state)
        new-cursor (min (dec cnt) (+ (:cursor state) page-size))]
    (-> state
        (assoc :cursor (max 0 new-cursor))
        update-offset)))

(defn go-to-start
  "Move cursor to start."
  [state]
  (-> state
      (assoc :cursor 0)
      (assoc :offset 0)))

(defn go-to-end
  "Move cursor to end."
  [state]
  (let [cnt (item-count state)]
    (-> state
        (assoc :cursor (max 0 (dec cnt)))
        update-offset)))

;; ---------------------------------------------------------------------------
;; Key Handling
;; ---------------------------------------------------------------------------

(defn handle-key
  "Handle a key event, returning updated state."
  [state event]
  (let [keys (:keys state)]
    (cond
      (matches-binding? event (:cursor-up keys)) (cursor-up state)
      (matches-binding? event (:cursor-down keys)) (cursor-down state)
      (matches-binding? event (:page-up keys)) (page-up state)
      (matches-binding? event (:page-down keys)) (page-down state)
      (matches-binding? event (:go-to-start keys)) (go-to-start state)
      (matches-binding? event (:go-to-end keys)) (go-to-end state)
      :else state)))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn- render-item
  "Render a single list item."
  [state index item selected?]
  (let [{:keys [cursor-style item-style cursor-prefix item-prefix
                show-descriptions]} state
        prefix (if selected? cursor-prefix item-prefix)
        title (item-title item)
        desc (when show-descriptions (item-description item))
        styled-title (if selected?
                       (style/render cursor-style title)
                       (if item-style
                         (style/render item-style title)
                         title))
        line (str prefix styled-title)]
    (if desc
      (str line "\n" (apply str (repeat (count prefix) " "))
           (style/render (style/style :fg (style/ansi256 240)) desc))
      line)))

(defn view
  "Render the list to a string."
  [state]
  (let [{:keys [items cursor offset height title show-title title-style]} state
        visible-h (visible-height state)
        visible-items (if (zero? height)
                        items
                        (subvec items offset (min (count items) (+ offset visible-h))))
        title-str (when show-title
                    (str (if title-style
                           (style/render title-style title)
                           title)
                         "\n"))
        item-lines (map-indexed
                    (fn [i item]
                      (let [actual-index (+ offset i)
                            selected? (= actual-index cursor)]
                        (render-item state actual-index item selected?)))
                    visible-items)]
    (str title-str (str/join "\n" item-lines))))

;; ---------------------------------------------------------------------------
;; Convenience Functions
;; ---------------------------------------------------------------------------

(defn filter-items
  "Filter items by a predicate function."
  [state pred]
  (let [filtered (filterv pred (:items state))]
    (set-items state filtered)))

(defn find-item
  "Find the first item matching a predicate. Returns index or nil."
  [state pred]
  (first (keep-indexed
          (fn [i item]
            (when (pred item) i))
          (:items state))))

(defn select-first-match
  "Select the first item matching a predicate."
  [state pred]
  (if-let [idx (find-item state pred)]
    (select state idx)
    state))

;; ---------------------------------------------------------------------------
;; Signal-based API
;; ---------------------------------------------------------------------------

(defn create-signal
  "Create a list signal with initial items and options.
   Requires Spindel context to be bound."
  [make-signal-fn id items & opts]
  (make-signal-fn id (apply list-state items opts)))

(defn update-signal!
  "Update a list signal with a key event."
  [signal event]
  (swap! signal #(handle-key % event)))
