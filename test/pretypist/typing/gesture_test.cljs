(ns pretypist.typing.gesture-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.typing.gesture :as gesture]))

(def layout
  {:keys [{:id :num} {:id :aa} {:id :sym} {:id :quote} {:id :dance}]
   :layers {:base {:bindings
                   {:num {:actions {:hold [:layer :momentary :num]}}
                    :sym {:actions {:tap [:one-shot :layer :sym]}}}}
            :num {:bindings {}}
            :sym {:bindings {}}}
   :strokes {"å" [[:hold :num] [:tap :aa] [:release :num]]
             "\"" [[:one-shot :sym] [:tap :quote]]
             "," [[:double-tap :dance]]}})

(deftest layer-hold-normalizes-to-ordered-illustrative-events-test
  (let [timeline (gesture/timeline-for layout "å")]
    (is (= :inferred (:source timeline)))
    (is (= :illustrative (:timing timeline)))
    (is (= [:base :num] (first (map (fn [layer] [:base layer]) (:layers timeline)))))
    (is (= [:hold :tap :release] (mapv :kind (:steps timeline))))
    (is (= [[0 :press :num]
            [140 :press :aa]
            [218 :release :aa]
            [280 :release :num]]
           (mapv (juxt :at-ms :kind :key-id) (:events timeline))))
    (is (= 430 (:duration-ms timeline)))
    (is (gesture/complex-timeline? timeline))))

(deftest tap-dance-shows-two-distinct-actuations-test
  (let [timeline (gesture/timeline-for layout ",")]
    (is (= [:press :release :press :release] (mapv :kind (:events timeline))))
    (is (= [0 78 150 228] (mapv :at-ms (:events timeline))))
    (is (= 378 (:duration-ms timeline)))))

(deftest one-shot-and-target-remain-separate-steps-test
  (let [timeline (gesture/timeline-for layout "\"")]
    (is (= [:one-shot :tap] (mapv :kind (:steps timeline))))
    (is (= [0 78 140 218] (mapv :at-ms (:events timeline))))
    (is (= [:base :sym] [:base (first (:layers timeline))]))))

(deftest unclosed-hold-is-released-safely-at-the-end-test
  (let [timeline (gesture/normalize-stroke "x" [[:hold :num]] #{:num})]
    (is (= [[0 :press] [140 :release]]
           (mapv (juxt :at-ms :kind) (:events timeline))))
    (is (= :implicit-release (:source-kind (last (:events timeline)))))))

(deftest disabled-unknown-and-layer-only-steps-never-invent-key-events-test
  (doseq [stroke [[[:disabled :aa]] [[:made-up :aa]] [[:layer :num]]]]
    (is (empty? (:events (gesture/normalize-stroke "x" stroke #{:aa}))))))
