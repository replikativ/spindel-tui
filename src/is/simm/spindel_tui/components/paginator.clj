(ns is.simm.spindel-tui.components.paginator
  "Pagination component for displaying page indicators.

   State is stored in a Spindel signal. Use paginator-state to create
   initial state, then use navigation functions to change pages."
  (:require [is.simm.spindel-tui.style.core :as style]))

;; ---------------------------------------------------------------------------
;; Paginator State
;; ---------------------------------------------------------------------------

(defn paginator-state
  "Create paginator state.

   Options:
     :total-pages   - Total number of pages (default 1)
     :per-page      - Items per page (default 10)
     :page          - Current page, 0-indexed (default 0)
     :type          - :dots or :arabic (default :dots)
     :active-dot    - String for active page dot (default \"•\")
     :inactive-dot  - String for inactive page dot (default \"○\")
     :arabic-format - Format string for arabic type (default \"%d/%d\")
     :active-style  - Style for active indicator
     :inactive-style - Style for inactive indicator"
  [& {:keys [total-pages per-page page type
             active-dot inactive-dot arabic-format
             active-style inactive-style]
      :or {total-pages 1
           per-page 10
           page 0
           type :dots
           active-dot "•"
           inactive-dot "○"
           arabic-format "%d/%d"}}]
  {:total-pages (max 1 total-pages)
   :per-page (max 1 per-page)
   :page (max 0 (min page (dec (max 1 total-pages))))
   :display-type type
   :active-dot active-dot
   :inactive-dot inactive-dot
   :arabic-format arabic-format
   :active-style (or active-style (style/style :bold true :fg style/cyan))
   :inactive-style (or inactive-style (style/style :fg (style/ansi256 240)))})

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn page [state] (:page state))
(defn total-pages [state] (:total-pages state))
(defn per-page [state] (:per-page state))

(defn set-page
  "Set current page."
  [state p]
  (assoc state :page (max 0 (min p (dec (:total-pages state))))))

(defn set-total-pages
  "Set total pages."
  [state n]
  (let [total (max 1 n)]
    (-> state
        (assoc :total-pages total)
        (update :page #(min % (dec total))))))

(defn set-per-page
  "Set items per page."
  [state n]
  (assoc state :per-page (max 1 n)))

(defn set-total-items
  "Set total pages based on item count."
  [state total-items]
  (let [per (:per-page state)
        pages (if (pos? total-items)
                (+ (quot total-items per)
                   (if (pos? (rem total-items per)) 1 0))
                1)]
    (set-total-pages state pages)))

;; ---------------------------------------------------------------------------
;; Navigation
;; ---------------------------------------------------------------------------

(defn on-first-page? [state] (zero? (:page state)))
(defn on-last-page? [state] (= (:page state) (dec (:total-pages state))))

(defn prev-page
  "Go to previous page."
  [state]
  (if (on-first-page? state)
    state
    (update state :page dec)))

(defn next-page
  "Go to next page."
  [state]
  (if (on-last-page? state)
    state
    (update state :page inc)))

(defn go-to-first
  "Go to first page."
  [state]
  (assoc state :page 0))

(defn go-to-last
  "Go to last page."
  [state]
  (assoc state :page (dec (:total-pages state))))

;; ---------------------------------------------------------------------------
;; Slice Bounds
;; ---------------------------------------------------------------------------

(defn slice-bounds
  "Get [start end] bounds for slicing items for current page."
  [state total-items]
  (let [{:keys [page per-page]} state
        start (* page per-page)
        end (min (+ start per-page) total-items)]
    [start end]))

(defn items-on-page
  "Get number of items on current page."
  [state total-items]
  (let [[start end] (slice-bounds state total-items)]
    (- end start)))

;; ---------------------------------------------------------------------------
;; Key Handling
;; ---------------------------------------------------------------------------

(def default-keys
  {:next-page #{"right" "l" "pgdown"}
   :prev-page #{"left" "h" "pgup"}})

(defn handle-key
  "Handle a key event, returning updated state."
  [state event]
  (let [key (:key event)]
    (cond
      (contains? (:next-page default-keys) key) (next-page state)
      (contains? (:prev-page default-keys) key) (prev-page state)
      :else state)))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(defn- dots-view
  "Render paginator as dots."
  [state]
  (let [{:keys [page total-pages active-dot inactive-dot
                active-style inactive-style]} state]
    (apply str
           (interpose " "
                      (for [i (range total-pages)]
                        (if (= i page)
                          (style/render active-style active-dot)
                          (style/render inactive-style inactive-dot)))))))

(defn- arabic-view
  "Render paginator as page numbers."
  [state]
  (let [{:keys [page total-pages arabic-format active-style]} state
        text (format arabic-format (inc page) total-pages)]
    (style/render active-style text)))

(defn view
  "Render the paginator to a string."
  [state]
  (case (:display-type state)
    :dots (dots-view state)
    :arabic (arabic-view state)
    (dots-view state)))

;; ---------------------------------------------------------------------------
;; Signal-based API
;; ---------------------------------------------------------------------------

(defn create-signal
  "Create a paginator signal.
   Requires Spindel context to be bound."
  [make-signal-fn id & opts]
  (make-signal-fn id (apply paginator-state opts)))

(defn next-page!
  "Go to next page on a signal."
  [signal]
  (swap! signal next-page))

(defn prev-page!
  "Go to previous page on a signal."
  [signal]
  (swap! signal prev-page))
