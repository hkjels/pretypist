(ns pretypist.scene.controller-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pretypist.scene.controller :as controller]))

(deftest narrow-scene-framing-does-not-zoom-through-the-model
  (is (= 1 (controller/camera-aspect-scale :layers 3.2 0)))
  (is (= 2.8 (controller/camera-aspect-scale :layers 1 0)))
  (is (< (controller/camera-aspect-scale :landing 1 0)
         (controller/camera-aspect-scale :landing 1 3)))
  (is (js/Number.isFinite (controller/camera-aspect-scale :menu 0 0))))

(deftest orbit-rotation-is-two-axis-and-clamped-test
  (is (= {:yaw 0 :pitch 0}
         (controller/orbit-rotation {:yaw 0 :pitch 0} 0 0)))
  (is (= {:yaw 0.2 :pitch -0.3}
         (controller/orbit-rotation {:yaw 0 :pitch 0} 0.2 -0.3)))
  (is (= {:yaw 0.46 :pitch -0.62}
         (controller/orbit-rotation {:yaw 0.5 :pitch -0.5} 5 -5)))
  (is (= {:yaw -0.46 :pitch 0.24}
         (controller/orbit-rotation {:yaw -0.5 :pitch 0.2} -5 5))))

(deftest shadow-sampling-is-bounded-and-keeps-endpoints-test
  (is (= [] (controller/sample-indices 0 8)))
  (is (= [] (controller/sample-indices 8 0)))
  (is (= [0 1 2] (controller/sample-indices 3 8)))
  (is (= [0] (controller/sample-indices 8 1)))
  (is (= [0 2 4 6 9] (controller/sample-indices 10 5))))

(deftest shadow-footprint-convex-hull-test
  (is (= [[0 0] [2 0] [2 2] [0 2]]
         (controller/convex-hull
          [[1 1] [2 2] [0 2] [2 0] [0 0] [1 0] [0 0]])))
  (is (= [[0 0] [2 0]]
         (controller/convex-hull [[1 0] [0 0] [2 0] [1 0]])))
  (is (= [[0 0]]
         (controller/convex-hull [[0 0] [js/NaN 1] [0 js/Infinity]]))))

(deftest gesture-events-use-a-scene-owned-animation-channel-test
  (let [record {:pressed 1 :glow 0.4}
        pressed (controller/apply-gesture-event record {:kind :press} false)
        reduced (controller/apply-gesture-event record {:kind :press} true)
        released (controller/apply-gesture-event pressed {:kind :release} false)]
    (is (= 1 (:pressed pressed)) "Observed physical state is untouched")
    (is (= 1 (:gesture-pressed pressed)))
    (is (= 1 (:gesture-glow pressed)))
    (is (zero? (:gesture-pressed reduced)) "Reduced motion highlights without travel")
    (is (= 1 (:gesture-glow reduced)))
    (is (zero? (:gesture-pressed released)))
    (is (zero? (:gesture-glow released)))))

(deftest controller-exposes-one-coarse-theme-command-test
  (let [scene (controller/create-controller)]
    (is (fn? (:set-theme! scene)))
    ((:set-theme! scene) {:layer-colors {:sym "#123456"}})
    ((:dispose! scene))))

(deftest landing-pose-interpolation-is-bounded-and-deterministic-test
  (let [first-pose (controller/landing-pose-at -10)
        midpoint (controller/landing-pose-at 0.5)
        final-pose (controller/landing-pose-at 99)]
    (is (= (:camera (first controller/landing-poses)) (:camera first-pose)))
    (is (= (:camera (last controller/landing-poses)) (:camera final-pose)))
    (is (< (js/Math.abs (- 0.0365 (get-in midpoint [:left :offset 0]))) 1e-12))
    (is (= 0.04 (:explode midpoint)))))

(deftest landing-key-choreography-distinguishes-halves-and-chapters-test
  (is (pos? (controller/landing-key-glow 0.1 "key_00")))
  (is (zero? (controller/landing-key-glow 0.1 "key_05")))
  (is (= 0.92 (controller/landing-key-glow 1.05 "key_32")))
  (is (zero? (controller/landing-key-glow 4 "key_32"))))

(deftest controller-exposes-scroll-progress-as-one-coarse-command-test
  (let [scene (controller/create-controller)]
    (is (fn? (:set-landing-progress! scene)))
    ((:set-mode! scene) :landing)
    ((:set-landing-progress! scene) 2.5)
    ((:dispose! scene))))
