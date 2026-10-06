(ns pretypist.ui.completion-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pretypist.ui.completion :as completion]))

(deftest chart-points-are-projected-into-the-svg-viewbox-test
  (is (= "2.00,2.00 98.00,34.00"
         (completion/chart-points-attribute [{:x 0 :y 1} {:x 1 :y 0}]))))

(deftest chart-symbols-have-small-uniform-viewports-and-equilateral-geometry
  (doseq [kind [:stall :error :gesture]]
    (let [attrs (second (completion/marker-symbol kind {}))]
      (is (= [8 8 "0 0 8 8"] ((juxt :width :height :viewBox) attrs)))
      (is (not= "none" (:preserveAspectRatio attrs)))))
  (is (= [:circle {:cx 4 :cy 4 :r 3}]
         (nth (completion/marker-symbol :stall {}) 2)))
  (let [path (:d (second (nth (completion/marker-symbol :gesture {}) 2)))
        [x1 y1 x2 y2 x3 y3] (map js/parseFloat (re-seq #"[\d.]+" path))
        distance (fn [a b c d] (js/Math.hypot (- a c) (- b d)))
        sides [(distance x1 y1 x2 y2) (distance x2 y2 x3 y3) (distance x3 y3 x1 y1)]]
    (is (< (- (apply max sides) (apply min sides)) 0.001))))

(deftest chart-markers-use-percentage-positions-without-stretching-their-symbols
  (doseq [[x expected] [[0 "2%"] [0.5 "50%"] [1 "98%"]]]
    (let [marker (completion/chart-marker 0 {:kind :stall :x x :cursor 3 :latency-ms 1800})
          attrs (second marker)]
      (is (= expected (get-in attrs [:style :left])))
      (is (= 8 (:width attrs)))
      (is (= "0" (:tabindex attrs)))
      (is (= "false" (:aria-hidden attrs)))
      (is (= :analysis/event-stall (second (:aria-label attrs)))))))
