(ns pretypist.scene.legends-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pretypist.scene.legends :as legends]
            ["three" :as THREE]))

(deftest legends-lie-above-the-cap-and-follow-key-travel-test
  (let [texture (THREE/Texture.)
        key (THREE/Group.)
        plane (legends/legend-plane texture 0.00345)
        world (THREE/Vector3.)
        bounds (THREE/Box3.)
        disposed* (atom {:geometry 0 :material 0 :texture 0})]
    (.addEventListener (.-geometry plane) "dispose" #(swap! disposed* update :geometry inc))
    (.addEventListener (.-material plane) "dispose" #(swap! disposed* update :material inc))
    (.addEventListener texture "dispose" #(swap! disposed* update :texture inc))
    (set! (.-y (.-position key)) 0.012)
    (.add key plane)
    (.updateWorldMatrix key true true)
    (.setFromObject bounds plane)
    (is (< (js/Math.abs (- (.-y (.-min bounds)) 0.0156)) 1e-8))
    (is (< (js/Math.abs (- (.-y (.-max bounds)) 0.0156)) 1e-8))
    (set! (.-y (.-position key)) (- 0.012 0.0024))
    (.getWorldPosition plane world)
    (is (< (js/Math.abs (- (.-y world) 0.0132)) 1e-8))
    (is (false? (.-depthWrite (.-material plane))))
    (legends/clear-legends! {"key_00" {:node key}})
    (is (zero? (alength (.-children key))))
    (is (= {:geometry 1 :material 1 :texture 0} @disposed*))
    ;; The shared texture belongs to the cache, not an individual legend.
    (.dispose texture)))
