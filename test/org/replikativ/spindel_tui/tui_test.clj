(ns org.replikativ.spindel-tui.tui-test
  "Regression tests for the spin-native TUI runner.

   These tests exercise the render-spin, mailbox consumer, and size
   signal without touching a real JLine terminal — every test uses a
   MockSink and drives input/size via direct mailbox posts and signal
   swaps. The integration with a real Terminal is exercised manually
   via `(tui/demo!)` and via the dvergr TUI consumer downstream.

   Most tests work with private fns inside tui.clj via
   `requiring-resolve` rather than re-exporting them, to keep the
   public surface area minimal."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.spin.sync :as sync]
            [org.replikativ.spindel-tui.sinks :as sinks]
            [org.replikativ.spindel-tui.tui :as tui]))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- make-render-spin*
  "Reach into tui's private make-render-spin without re-exporting it."
  [& args]
  (apply (requiring-resolve 'org.replikativ.spindel-tui.tui/make-render-spin)
         args))

(defn- start-input-consumer!*
  [& args]
  (apply (requiring-resolve 'org.replikativ.spindel-tui.tui/start-input-consumer!)
         args))

(defn- make-test-signal
  [ctx id initial]
  (binding [ec/*execution-context* ctx]
    (let [s (sig/->SignalRef id initial)]
      (sig/ensure-signal-initialized! s)
      s)))

(defn- spin-rc-callback
  "Builds (resolve, reject) callbacks that bump a counter on every
   resolve and print rejections to *err*."
  [rc-atom]
  [(fn [_] (swap! rc-atom inc))
   (fn [e] (binding [*out* *err*]
             (println "spin-error:" e)))])

(defn- run-spin!
  "Invoke a spin's CPS entry point with ctx bound, then sleep so the
   engine drain can finish processing the initial render."
  [ctx the-spin resolve reject]
  (binding [ec/*execution-context* ctx]
    (the-spin resolve reject))
  (Thread/sleep 50))

(defn- read-signal
  "Read a signal value with ctx bound."
  [ctx sig]
  (binding [ec/*execution-context* ctx]
    @sig))

;; =============================================================================
;; Sink protocol
;; =============================================================================

(deftest mock-sink-records-frames
  (let [s (sinks/mock-sink)]
    (is (= 0 (sinks/frame-count s)))
    (sinks/render-frame! s ["hello"] 80 24)
    (is (= 1 (sinks/frame-count s)))
    (is (= ["hello"] (:lines (last (sinks/frames s)))))
    (is (= 80 (:width  (last (sinks/frames s)))))
    (is (= 24 (:height (last (sinks/frames s)))))))

;; =============================================================================
;; Render-spin reactivity
;; =============================================================================

(deftest render-spin-fires-on-each-signal-change-once
  (let [ctx       (ctx/create-execution-context)
        sink      (sinks/mock-sink)
        cnt-sig   (make-test-signal ctx :cnt 0)
        size-sig  (make-test-signal ctx :sz  {:width 80 :height 24})
        smap      {:tui-ctx ctx
                   :org.replikativ.spindel-tui.tui/size size-sig
                   :cnt cnt-sig}
        rc        (atom 0)
        view-fn   (fn [s w _] [(str "cnt=" @(:cnt s)) (str "w=" w)])
        the-spin  (binding [ec/*execution-context* ctx]
                    (make-render-spin* sink view-fn smap size-sig rc))
        [resolve reject] (spin-rc-callback rc)]

    (run-spin! ctx the-spin resolve reject)
    (is (= 1 (sinks/frame-count sink)) "initial render")
    (is (= ["cnt=0" "w=80"] (:lines (last (sinks/frames sink)))))

    (binding [ec/*execution-context* ctx]
      (swap! cnt-sig inc))
    (Thread/sleep 100)
    (is (= 2 (sinks/frame-count sink)) "one signal change → one new frame")
    (is (= ["cnt=1" "w=80"] (:lines (last (sinks/frames sink)))))

    (binding [ec/*execution-context* ctx]
      (dotimes [_ 3] (swap! cnt-sig inc)))
    (Thread/sleep 200)
    (is (= 5 (sinks/frame-count sink)) "three more swaps → three new frames")
    (is (= ["cnt=4" "w=80"] (:lines (last (sinks/frames sink)))))))

(deftest render-spin-idle-no-wakeups
  (let [ctx      (ctx/create-execution-context)
        sink     (sinks/mock-sink)
        cnt-sig  (make-test-signal ctx :cnt 0)
        size-sig (make-test-signal ctx :sz  {:width 80 :height 24})
        smap     {:tui-ctx ctx
                  :org.replikativ.spindel-tui.tui/size size-sig
                  :cnt cnt-sig}
        rc       (atom 0)
        the-spin (binding [ec/*execution-context* ctx]
                   (make-render-spin* sink (fn [_ _ _] []) smap size-sig rc))
        [resolve reject] (spin-rc-callback rc)]
    (run-spin! ctx the-spin resolve reject)
    (let [baseline (sinks/frame-count sink)]
      (Thread/sleep 500)
      (is (= baseline (sinks/frame-count sink))
          "500ms idle window after init → zero additional frames"))))

(deftest render-spin-resize-via-size-signal
  (let [ctx      (ctx/create-execution-context)
        sink     (sinks/mock-sink)
        size-sig (make-test-signal ctx :sz  {:width 80 :height 24})
        smap     {:tui-ctx ctx
                  :org.replikativ.spindel-tui.tui/size size-sig}
        rc       (atom 0)
        view-fn  (fn [_ w h] [(str w "x" h)])
        the-spin (binding [ec/*execution-context* ctx]
                   (make-render-spin* sink view-fn smap size-sig rc))
        [resolve reject] (spin-rc-callback rc)]
    (run-spin! ctx the-spin resolve reject)
    (is (= 1 (sinks/frame-count sink)))
    (is (= ["80x24"] (:lines (last (sinks/frames sink)))))

    (binding [ec/*execution-context* ctx]
      (swap! size-sig assoc :width 100))
    (Thread/sleep 100)
    (is (= 2 (sinks/frame-count sink)) "resize → re-render")
    (is (= ["100x24"] (:lines (last (sinks/frames sink)))))

    (binding [ec/*execution-context* ctx]
      (swap! size-sig assoc :height 40))
    (Thread/sleep 100)
    (is (= 3 (sinks/frame-count sink)) "height change → re-render")
    (is (= ["100x40"] (:lines (last (sinks/frames sink)))))))

;; =============================================================================
;; Mailbox consumer
;; =============================================================================

(deftest consumer-dispatches-keys-and-drives-rerender
  (let [ctx       (ctx/create-execution-context)
        sink      (sinks/mock-sink)
        cnt-sig   (make-test-signal ctx :cnt 0)
        size-sig  (make-test-signal ctx :sz  {:width 80 :height 24})
        smap      {:tui-ctx ctx
                   :org.replikativ.spindel-tui.tui/size size-sig
                   :cnt cnt-sig}
        rc        (atom 0)
        the-spin  (binding [ec/*execution-context* ctx]
                    (make-render-spin* sink
                                       (fn [s _ _] [(str @(:cnt s))])
                                       smap size-sig rc))
        [resolve reject] (spin-rc-callback rc)
        _         (run-spin! ctx the-spin resolve reject)
        mbx       (sync/create-mailbox ctx)
        running   (atom true)
        on-key    (fn [s e]
                    (cond
                      (= "q" (:key e)) :quit
                      (= "+" (:key e)) (do (swap! (:cnt s) inc) nil)))]

    (binding [ec/*execution-context* ctx]
      (start-input-consumer!* ctx mbx on-key smap running))
    (Thread/sleep 50)

    (testing "one post → one swap → one new frame"
      (let [baseline (sinks/frame-count sink)]
        (binding [ec/*execution-context* ctx]
          (sync/post! mbx {:key "+"}))
        (Thread/sleep 150)
        (is (= 1 (read-signal ctx cnt-sig)))
        (is (= (inc baseline) (sinks/frame-count sink)))))

    (testing "three more posts → three more frames"
      (let [baseline (sinks/frame-count sink)]
        (binding [ec/*execution-context* ctx]
          (dotimes [_ 3] (sync/post! mbx {:key "+"})))
        (Thread/sleep 300)
        (is (= 4 (read-signal ctx cnt-sig)))
        (is (= (+ baseline 3) (sinks/frame-count sink)))))

    (testing "idle window after posts → zero new frames"
      (let [baseline (sinks/frame-count sink)]
        (Thread/sleep 500)
        (is (= baseline (sinks/frame-count sink)))))

    (testing "quit flips running to false"
      (binding [ec/*execution-context* ctx]
        (sync/post! mbx {:key "q"}))
      (Thread/sleep 150)
      (is (false? @running)))

    (testing "post-shutdown events do not invoke on-key"
      (let [cnt-before (read-signal ctx cnt-sig)]
        (binding [ec/*execution-context* ctx]
          (sync/post! mbx {:key "+"}))
        (Thread/sleep 150)
        (is (= cnt-before (read-signal ctx cnt-sig))
            "consumer is shut down — count should not change")))))

;; =============================================================================
;; Controller-map API
;; =============================================================================

(deftest start!-returns-controller-and-stop-is-idempotent
  (let [ctx  (ctx/create-execution-context)
        sink (sinks/mock-sink)
        t    (tui/start!
               {:execution-context ctx
                :sink              sink
                :signals           {:cnt 0}
                :render            (fn [s _ _] [(str @(:cnt s))])
                :on-key            (fn [s e]
                                     (cond
                                       (= "q" (:key e)) :quit
                                       (= "+" (:key e)) (do (swap! (:cnt s) inc) nil)))})]
    (testing "controller has the documented keys"
      (is (= #{:running :stop! :await-quit :ctx :sink :signals :render-count}
             (set (keys t)))))
    (testing "running starts true; ctx and sink are the ones we passed"
      (is (true? @(:running t)))
      (is (= ctx (:ctx t)))
      (is (= sink (:sink t))))
    (testing "stop! is idempotent and flips running"
      ((:stop! t))
      (is (false? @(:running t)))
      ((:stop! t))
      (is (false? @(:running t))))))
