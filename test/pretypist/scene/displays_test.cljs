(ns pretypist.scene.displays-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.scene.displays :as displays]
            ["three" :as THREE]))

(def central-config
  {:kind :nice-view
   :node "display_screen_left"
   :role :central
   :resolution [160 68]
   :surface-size-mm [11.3 26.5]
   :battery :left})

(def peripheral-config
  {:kind :nice-view
   :node "display_screen_right"
   :role :peripheral
   :resolution [160 68]
   :surface-size-mm [11.3 26.5]
   :battery :right})

(def status
  {:source :simulated
   :batteries {:left {:percent 82 :usb-powered? true}
               :right {:percent 76 :usb-powered? false}}
   :endpoint {:transport :ble}
   :bluetooth-profiles [{:index 0
                         :selected? true
                         :bonded? true
                         :connected? true}
                        {:index 2 :bonded? true}]
   :wpm {:current 42.4 :history [21 24 28 30 33 36 39 40 41 42 44 46]}
   :layer :win
   :split-connection :connected
   :peripheral-art :mountain})

(deftest battery-normalization-is-honest-and-bounded-test
  (is (= {:percent nil :usb-powered? false}
         (displays/normalize-battery nil)))
  (is (= {:percent 100 :usb-powered? true}
         (displays/normalize-battery {:percent 140.4 :usb-powered? true})))
  (is (= {:percent 0 :usb-powered? false}
         (displays/normalize-battery {:percent -12 :usb-powered? :yes}))))

(deftest wpm-history-keeps-ten-recent-finite-samples-test
  (is (= [0 0 0 0 0 0 12 15 19 22]
         (displays/normalize-history [12 js/NaN 15 19 22])))
  (is (= [3 4 5 6 7 8 9 10 11 12]
         (displays/normalize-history (range 1 13))))
  (is (= [0 0 0 0 0 0 0 0 0 255]
         (displays/normalize-history [-4 900]))))

(deftest central-frame-contains-only-visible-semantics-test
  (let [frame (displays/central-frame central-config status)]
    (is (= :central (:kind frame)))
    (is (= :simulated (:source frame)))
    (is (= {:percent 82 :usb-powered? true} (:battery frame)))
    (is (= {:transport :ble} (:endpoint frame)))
    (is (= {:index 0 :selected? true :bonded? true :connected? true}
           (first (:profiles frame))))
    (is (= {:index 2 :selected? false :bonded? true :connected? false}
           (nth (:profiles frame) 2)))
    (is (= 5 (count (:profiles frame))))
    (is (= 42 (get-in frame [:wpm :current])))
    (is (= [28 30 33 36 39 40 41 42 44 46]
           (get-in frame [:wpm :history])))
    (is (= "WIN" (:layer frame)))
    (testing "unrelated application changes do not invalidate the signature"
      (is (= frame
             (displays/central-frame central-config
                                     (assoc status :unrelated/value 99)))))))

(deftest peripheral-frame-uses-its-own-battery-and-link-test
  (is (= {:kind :peripheral
          :source :simulated
          :battery {:percent 76 :usb-powered? false}
          :split-connection :connected
          :peripheral-art :mountain}
         (displays/peripheral-frame peripheral-config status)))
  (is (= :disconnected
         (:split-connection
          (displays/peripheral-frame peripheral-config
                                     (dissoc status :split-connection)))))
  (is (= :balloon
         (:peripheral-art
          (displays/peripheral-frame peripheral-config
                                     (assoc status :peripheral-art :balloon))))))

(deftest graph-scaling-is-stable-and-bounded-test
  (is (= [0 0 0 0 0 0 0 0 0 0]
         (displays/graph-heights [] 30)))
  (is (= [3 6 9 12 15 18 21 24 27 30]
         (displays/graph-heights [10 20 30 40 50 60 70 80 90 100] 30)))
  (is (= 10 (count (displays/graph-heights (range 100) 32))))
  (is (every? #(<= 0 % 32)
              (displays/graph-heights (range 100) 32))))

(deftest generated-stock-art-payloads-are-exact-test
  (is (= 20 displays/peripheral-art-top))
  (doseq [[art-id expected-set-pixels] [[:balloon 6652]
                                         [:mountain 4700]]
          :let [bytes (get displays/packed-art-bytes art-id)]]
    (is (= 1224 (.-length bytes)))
    (is (= expected-set-pixels
           (count (for [source-y (range 68)
                        source-x (range 140)
                        :when (displays/packed-bit-set? bytes source-x source-y)]
                    true)))))
  (is (= [0 159] (displays/source->logical-art-point 0 0)))
  (is (= [67 20] (displays/source->logical-art-point 139 67))))

(deftest generated-stock-art-hex-decoder-rejects-corruption-test
  (let [bytes (displays/decode-hex-payload "00ff10")]
    (is (= [0 255 16] (vec (array-seq bytes)))))
  (is (thrown? js/Error (displays/decode-hex-payload "0")))
  (is (thrown? js/Error (displays/decode-hex-payload "00GG"))))

(deftest portrait-framebuffer-transform-rotates-without-reflecting-test
  (let [[a b c d _ _] displays/portrait-canvas-transform]
    (is (= [0 -1 1 0 0 68] displays/portrait-canvas-transform))
    (is (pos? (- (* a d) (* b c)))
        "A positive determinant proves that the transform is not mirrored")))

(deftest nice-view-palette-is-light-on-dark-test
  (let [paper-luminance (reduce + (:paper displays/display-colors))
        ink-luminance (reduce + (:ink displays/display-colors))]
    (is (< paper-luminance ink-luminance))))

(deftest mirrored-display-assembly-is-counter-reflected-test
  (let [ordinary-parent (THREE/Object3D.)
        ordinary-screen (THREE/Object3D.)
        mirrored-parent (THREE/Object3D.)
        mirrored-screen (THREE/Object3D.)]
    (.add ordinary-parent ordinary-screen)
    (.set (.-scale mirrored-parent) -1 1 1)
    (.add mirrored-parent mirrored-screen)
    (is (= 1 (displays/surface-x-compensation ordinary-screen)))
    (is (= -1 (displays/surface-x-compensation mirrored-screen)))))

(deftest display-surface-size-converts-mm-to-scene-metres-test
  (doseq [config [central-config {}]
          [actual expected] (map vector (displays/surface-size config)
                                 [0.0113 0.0265])]
    (is (< (js/Math.abs (- actual expected)) 1e-12))))
