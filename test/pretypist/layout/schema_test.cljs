(ns pretypist.layout.schema-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pretypist.layout.schema :as schema]))

(def layout
  {:keys [{:id :k00 :index 0 :node "key_00" :observed-codes ["KeyA"]}]
   :layers {:base {:bindings {:k00 {:legend "A" :actions {:tap [:text "a"]}}}}
            :num {:bindings {:k00 {:legend "1" :actions {:tap [:text "1"]}}}}}
   :strokes {"a" [[:tap :k00]] "1" [[:hold :k00] [:tap :k00] [:release :k00]]}
   :displays {:left {:node "display_screen_left" :role :central}}
   :display/default-status {:source :simulated :layer :base}})

(deftest layer-legend-resolution-test
  (is (= "A" (schema/legend-for layout :base :k00)))
  (is (= "1" (schema/legend-for layout :num :k00)))
  (is (= {:k00 "A"} (schema/legends-for layout :base))))

(deftest reverse-character-to-stroke-test
  (is (= [[:tap :k00]] (schema/stroke-for layout "a")))
  (is (= [[:hold :k00] [:tap :k00] [:release :k00]]
         (get (schema/reverse-stroke-index layout) "1")))
  (is (= :k00 (:id (schema/key-by-code layout "KeyA")))))

(deftest display-schema-lookup-test
  (is (= :central (:role (schema/display-by-id layout :left))))
  (is (= #{"display_screen_left"} (schema/display-node-names layout)))
  (is (= :base (:layer (schema/default-display-status layout))))
  (is (contains? schema/display-templates :zmk/nice-view-status-v0.3))
  (is (= 10 schema/nice-view-wpm-history-length)))
