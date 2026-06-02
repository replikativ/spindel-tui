(ns org.replikativ.spindel-tui.harness-test
  "Self-test for the headless TUI harness."
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel-tui.harness :as h]))

(deftest harness-renders-and-drives-keys
  (testing "initial render + key-driven signal mutation re-renders"
    (let [h (h/harness
              {:signals {:n 0}
               :render  (fn [sm _w _h] [(str "n=" @(:n sm))])
               :on-key  (fn [sm _ev] (swap! (:n sm) inc))})]
      (try
        (is (re-find #"n=0" ((:text h))) "initial frame rendered")
        ((:send-key h) {:key "x"})
        (is (= 1 ((:get h) :n)) "on-key mutated the signal")
        (is (re-find #"n=1" ((:text h))) "render re-fired on signal change")
        (finally ((:stop! h)))))))

(deftest harness-resize-drives-render
  (testing "resize is reflected in the render width/height args"
    (let [h (h/harness
              {:signals {}
               :render  (fn [_sm w ht] [(str w "x" ht)])
               :on-key  (fn [_sm _ev] nil)
               :size    {:width 100 :height 40}})]
      (try
        (is (re-find #"100x40" ((:text h))))
        ((:resize h) 60 20)
        (is (re-find #"60x20" ((:text h))))
        (finally ((:stop! h)))))))
