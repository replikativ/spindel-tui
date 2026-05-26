(ns org.replikativ.spindel-tui.components.tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.replikativ.spindel-tui.components.tree :as tree]))

(def sample
  [{:id :a :label "Alpha"
    :children [{:id :a1 :label "Alpha-1"}
               {:id :a2 :label "Alpha-2"
                :children [{:id :a2x :label "leaf"}]}]}
   {:id :b :label "Beta"}])

(deftest builds-flat-list-respecting-expanded
  (let [s (tree/tree-state :roots sample)]
    (testing "initially only roots are visible"
      (is (= [:a :b] (mapv #(get-in % [:node :id]) (tree/flat-rows s)))))
    (testing "expanding :a reveals its direct children"
      (let [s (tree/set-expanded s #{:a})]
        (is (= [:a :a1 :a2 :b] (mapv #(get-in % [:node :id]) (tree/flat-rows s))))))
    (testing "expanding both :a and :a2 reveals nested leaf"
      (let [s (tree/set-expanded s #{:a :a2})]
        (is (= [:a :a1 :a2 :a2x :b] (mapv #(get-in % [:node :id]) (tree/flat-rows s))))))))

(deftest navigation-and-toggle
  (let [s0 (tree/tree-state :roots sample)]
    (testing "down moves cursor"
      (let [s1 (tree/handle-key s0 {:key "j"})]
        (is (= 1 (:cursor s1)))
        (is (= :b (:id (tree/selected-node s1))))))
    (testing "enter toggles expansion of node under cursor"
      (let [s1 (tree/handle-key s0 {:key "enter"})]
        (is (contains? (:expanded s1) :a))
        (is (= [:a :a1 :a2 :b] (mapv #(get-in % [:node :id]) (tree/flat-rows s1))))
        (let [s2 (tree/handle-key s1 {:key "enter"})]
          (is (empty? (:expanded s2)) "second enter collapses"))))
    (testing "right expands a closed parent, then moves into children"
      (let [s1 (tree/handle-key s0 {:key :right})]
        (is (contains? (:expanded s1) :a))
        (let [s2 (tree/handle-key s1 {:key :right})]
          (is (= :a1 (:id (tree/selected-node s2)))))))
    (testing "left on an expanded parent collapses it"
      (let [s1 (tree/handle-key s0 {:key :right})
            s2 (tree/handle-key s1 {:key :left})]
        (is (empty? (:expanded s2)))))
    (testing "left on a child moves to parent"
      (let [s1 (tree/handle-key s0 {:key :right})
            s2 (tree/handle-key s1 {:key :right})
            s3 (tree/handle-key s2 {:key :left})]
        (is (= :a (:id (tree/selected-node s3))))))
    (testing "G goes to last visible row"
      (let [s1 (tree/handle-key s0 {:key "G"})]
        (is (= :b (:id (tree/selected-node s1))))))))

(deftest set-roots-preserves-cursor-by-path
  (let [s0 (tree/tree-state :roots sample :expanded #{:a})
        s1 (-> s0 tree/move-down tree/move-down) ; cursor on :a2
        new-sample (conj sample {:id :c :label "Gamma"})
        s2 (tree/set-roots s1 new-sample)]
    (is (= :a2 (:id (tree/selected-node s2)))
        "cursor should stick to :a2 even after a new root is inserted")))

(deftest view-renders-cursor-prefix-and-indent
  (let [s (tree/tree-state :roots sample :expanded #{:a} :cursor-style nil)
        lines (tree/view s)]
    (is (= 4 (count lines)))
    (is (re-find #"^> ▾ Alpha" (first lines)) "cursor row has cursor-prefix")
    (is (re-find #"^    " (nth lines 1)) "child of :a is indented")
    (is (re-find #"^    ▸ Alpha-2" (nth lines 2))
        "Alpha-2 at depth 1: item-prefix (2) + indent (2) = 4 leading spaces")))

(deftest empty-tree
  (let [s (tree/tree-state)]
    (is (= [] (tree/flat-rows s)))
    (is (nil? (tree/selected-node s)))
    (is (= [""] (tree/view s)))))
