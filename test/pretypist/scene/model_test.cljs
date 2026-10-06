(ns pretypist.scene.model-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.scene.model :as model]
            ["three" :as THREE]))

(defn- color-channels [hex]
  [(bit-and (bit-shift-right hex 16) 0xff)
   (bit-and (bit-shift-right hex 8) 0xff)
   (bit-and hex 0xff)])

(defn- linear-channel [channel]
  (let [value (/ channel 255)]
    (if (<= value 0.04045)
      (/ value 12.92)
      (js/Math.pow (/ (+ value 0.055) 1.055) 2.4))))

(defn- relative-luminance [hex]
  (let [[red green blue] (map linear-channel (color-channels hex))]
    (+ (* 0.2126 red) (* 0.7152 green) (* 0.0722 blue))))

(deftest dark-plastic-and-nylon-contrast-stays-photographic-test
  (let [case-preset (:case model/material-presets)
        keycap-preset (:keycap model/material-presets)
        switch-preset (:keyswitch model/material-presets)
        battery-preset (:connector-battery model/material-presets)
        diffuse-ratio (/ (relative-luminance (:color battery-preset))
                         (relative-luminance (:color case-preset)))]
    (testing "nylon remains pale without recreating the former clipped contrast"
      (is (< 15 diffuse-ratio 30))
      (is (>= (:roughness battery-preset) 0.75)))
    (testing "black plastics retain distinct, non-metallic midtones"
      (is (< (relative-luminance (:color switch-preset))
             (relative-luminance (:color case-preset))
             (relative-luminance (:color keycap-preset))))
      (is (zero? (:metalness case-preset)))
      (is (zero? (:metalness keycap-preset))))))

(deftest connector-materials-reflect-their-different-polymers-test
  (let [battery (:connector-battery model/material-presets)
        display (:connector-display model/material-presets)]
    (is (not= (:color battery) (:color display)))
    (is (< (relative-luminance (:color battery))
           (relative-luminance (:color display))))
    (is (> (:roughness battery) (:roughness display)))))

(deftest glass-and-fastener-materials-are-physically-distinct-test
  (let [glass (:glass model/material-presets)
        screw (:screw model/material-presets)]
    (testing "the nice!view cover transmits light without writing a fake opaque depth"
      (is (> (:transmission glass) 0.65))
      (is (true? (:transparent glass)))
      (is (false? (:depthWrite glass)))
      (is (< (:opacity glass) 0.3)))
    (testing "fasteners read as restrained metal rather than black plastic"
      (is (> (:metalness screw) 0.8))
      (is (> (relative-luminance (:color screw))
             (relative-luminance (:color (:case model/material-presets))))))))

(deftest keycap-microdetail-is-deterministic-and-subtle-test
  (let [first-sample (model/surface-detail-sample 4 9)
        same-sample (model/surface-detail-sample 4 9)
        samples (for [y (range model/surface-detail-size)
                      x (range model/surface-detail-size)]
                  (model/surface-detail-sample x y))]
    (is (= first-sample same-sample))
    (is (every? #(<= 223 (:roughness %) 247) samples))
    (is (every? #(<= 125 (:normal-x %) 131) samples))
    (is (every? #(<= 125 (:normal-y %) 131) samples))))

(deftest key-contact-occlusion-is-model-owned-and-disposable-test
  (let [root (THREE/Group.)
        key (THREE/Group.)]
    (set! (.-name key) "key_00")
    (.set (.-position key) 0.01 0.008 -0.02)
    (.add root key)
    (is (= 1 (model/add-key-ambient-occlusion!
              {"key_00" {:node key :base-y 0.008}})))
    (let [proxy (.getObjectByName root "pretypist_key_ao_key_00")]
      (is (some? proxy))
      (is (< (.-y (.-position proxy)) 0.008))
      (is (false? (.-depthWrite (.-material proxy))))
      (is (some? (.-alphaMap (.-material proxy)))))
    (model/dispose-object! root)))

(deftest shared-render-resources-are-disposed-exactly-once-test
  (let [root (THREE/Group.)
        first-object (THREE/Object3D.)
        second-object (THREE/Object3D.)
        counts (atom {:geometry 0 :material 0 :texture 0})
        texture #js {:dispose #(swap! counts update :texture inc)}
        material #js {:normalMap texture
                      :dispose #(swap! counts update :material inc)}
        geometry #js {:dispose #(swap! counts update :geometry inc)}]
    (doseq [object [first-object second-object]]
      (aset object "geometry" geometry)
      (aset object "material" material)
      (.add root object))
    (model/dispose-object! root)
    (is (= {:geometry 1 :material 1 :texture 1} @counts))))

(deftest authored-materials-and-metre-pivots-survive-runtime-installation-test
  (let [root (THREE/Group.)
        key-a (THREE/Group.) key-b (THREE/Group.)
        geometry (THREE/BoxGeometry. 17.5 3.45 16.5)
        authored (THREE/MeshStandardMaterial. #js {:color 0x243326 :roughness 0.41 :flatShading true})
        texture (THREE/Texture.)
        first-mesh (THREE/Mesh. geometry authored)
        second-mesh (THREE/Mesh. geometry authored)
        board (THREE/Mesh. geometry authored)
        disposed* (atom 0)]
    (set! (.-map authored) texture)
    (.addEventListener texture "dispose" #(swap! disposed* inc))
    (doseq [[key name mesh] [[key-a "key_00" first-mesh] [key-b "key_01" second-mesh]]]
      (set! (.-name key) name)
      (.set (.-position key) (if (= name "key_00") -0.1 0.1) 0.012 0)
      (.setScalar (.-scale mesh) 0.001)
      (set! (.-y (.-position mesh)) 0.001725)
      (.add key mesh)
      (.add root key))
    (.add root board)
    (let [records (model/replace-materials! root #{"key_00" "key_01"} :preserve)]
      (is (= 2 (count records)))
      (is (< (js/Math.abs (- 0.00345 (get-in records ["key_00" :surface-y]))) 1e-8))
      (is (= 0.41 (.-roughness (.-material first-mesh))))
      (is (true? (.-flatShading (.-material first-mesh))))
      (is (= 0x243326 (.getHex (.-color (.-material first-mesh)))))
      (is (identical? authored (.-material board)))
      (is (not (identical? (.-material first-mesh) (.-material second-mesh))))
      (is (identical? texture (.-map (.-material first-mesh))))
      (is (zero? @disposed*)))
    (model/dispose-object! root)
    (is (= 1 @disposed*))))
