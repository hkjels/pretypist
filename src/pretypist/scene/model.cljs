(ns pretypist.scene.model
  (:require ["three" :as THREE]))

(def material-presets
  "Runtime materials used after an untrusted GLB has been loaded.

  Three.js converts these sRGB colours to linear working space. Keeping the
  palette as data makes the contrast boundary reviewable: near-black plastic
  retains useful midtones while pale nylon remains visibly lighter without
  becoming a clipped white light source."
  {:keycap {:color 0x25262a
            :roughness 0.68
            :metalness 0
            :clearcoat 0.035
            :clearcoatRoughness 0.78
            :emissive 0x8b83ff
            :emissiveIntensity 0}
   :case {:color 0x202126
          :roughness 0.84
          :metalness 0}
   :pcb {:color 0x11251f
         :roughness 0.74
         :metalness 0}
   :display-screen {:color 0x2a3032
                    :roughness 0.3
                    :metalness 0.05
                    :clearcoat 0.56
                    :clearcoatRoughness 0.24
                    :emissive 0x1d2427
                    :emissiveIntensity 0.12}
   :glass {:color 0xa4aaa8
           :roughness 0.1
           :metalness 0
           :transmission 0.72
           :thickness 0.00045
           :ior 1.49
           :transparent true
           :opacity 0.2
           :depthWrite false
           :clearcoat 1
           :clearcoatRoughness 0.08}
   :keyswitch {:color 0x17181b
               :roughness 0.72
               :metalness 0}
   :contact {:color 0xa67b30
             :roughness 0.3
             :metalness 0.78}
   :wire-red {:color 0x7d171a
              :roughness 0.54
              :metalness 0}
   :wire-black {:color 0x111216
                :roughness 0.62
                :metalness 0}
   ;; Battery headers are moulded nylon and deliberately warmer/darker than
   ;; the display FPC socket. At this scale roughness communicates the plastic
   ;; more reliably than a bright albedo.
   :connector-battery {:color 0xaaa69b
                       :roughness 0.78
                       :metalness 0}
   :connector-display {:color 0xb4afa3
                       :roughness 0.7
                       :metalness 0}
   :connector {:color 0xaaa69b
               :roughness 0.76
               :metalness 0}
   :metal {:color 0x777a7b
           :roughness 0.4
           :metalness 0.72}
   :accent {:color 0xb36516
            :roughness 0.46
            :metalness 0.05}
   :led-blue {:color 0x4b7388
              :roughness 0.34
              :metalness 0
              :emissive 0x3f8daf
              :emissiveIntensity 0.34}
   :led-amber {:color 0xa45516
               :roughness 0.36
               :metalness 0
               :emissive 0xc16a1d
               :emissiveIntensity 0.22}
   :ceramic {:color 0x918b80
             :roughness 0.72
             :metalness 0}
   :component {:color 0x292b2f
               :roughness 0.58
               :metalness 0.02}
   :screw {:color 0x777a7b
           :roughness 0.32
           :metalness 0.86}})

(defn- material-options [material-id]
  (clj->js (get material-presets material-id)))

(def surface-detail-size 32)

(defn surface-detail-sample
  "A deterministic, bounded moulding variation. The resulting normal offsets
  are intentionally tiny; this should break up mathematically perfect
  highlights, not make the keycaps look textured from reading distance."
  [x y]
  (let [fraction (fn [value] (- value (js/Math.floor value)))
        noise (fn [offset]
                (- (* 2 (fraction (* (js/Math.sin (+ (* (+ x offset) 12.9898)
                                                        (* (+ y offset) 78.233)))
                                      43758.5453)))
                   1))
        first-noise (noise 1)
        second-noise (noise 9)]
    {:roughness (js/Math.round (+ 235 (* first-noise 12)))
     :normal-x (js/Math.round (+ 128 (* first-noise 3)))
     :normal-y (js/Math.round (+ 128 (* second-noise 3)))}))

(defn- configure-detail-texture! [texture repeat]
  (aset texture "wrapS" (.-RepeatWrapping THREE))
  (aset texture "wrapT" (.-RepeatWrapping THREE))
  (.set (aget texture "repeat") repeat repeat)
  (aset texture "needsUpdate" true)
  texture)

(defn- surface-detail-textures []
  (let [pixel-count (* surface-detail-size surface-detail-size)
        roughness-data (js/Uint8Array. (* pixel-count 4))
        normal-data (js/Uint8Array. (* pixel-count 4))]
    (doseq [y (range surface-detail-size)
            x (range surface-detail-size)]
      (let [{:keys [roughness normal-x normal-y]} (surface-detail-sample x y)
            offset (* 4 (+ x (* y surface-detail-size)))]
        (doseq [channel [0 2 3]] (aset roughness-data (+ offset channel) 255))
        (aset roughness-data (+ offset 1) roughness)
        (aset normal-data offset normal-x)
        (aset normal-data (+ offset 1) normal-y)
        (aset normal-data (+ offset 2) 255)
        (aset normal-data (+ offset 3) 255)))
    {:roughness (configure-detail-texture!
                 (THREE/DataTexture. roughness-data surface-detail-size surface-detail-size
                                     (.-RGBAFormat THREE) (.-UnsignedByteType THREE))
                 3)
     :normal (configure-detail-texture!
              (THREE/DataTexture. normal-data surface-detail-size surface-detail-size
                                  (.-RGBAFormat THREE) (.-UnsignedByteType THREE))
              3)}))

(defn- contact-occlusion-texture []
  (let [size 48
        data (js/Uint8Array. (* size size 4))]
    (doseq [y (range size)
            x (range size)]
      (let [nx (/ (- x (/ (dec size) 2)) (/ (dec size) 2))
            ny (/ (- y (/ (dec size) 2)) (/ (dec size) 2))
            radius (js/Math.sqrt (+ (* nx nx) (* ny ny)))
            strength (js/Math.round (* 255 (js/Math.pow (max 0 (- 1 radius)) 2.4)))
            offset (* 4 (+ x (* y size)))]
        (doseq [channel (range 4)] (aset data (+ offset channel) strength))))
    (let [texture (THREE/DataTexture. data size size
                                      (.-RGBAFormat THREE) (.-UnsignedByteType THREE))]
      (aset texture "needsUpdate" true)
      texture)))

(def texture-properties
  ["map" "lightMap" "aoMap" "emissiveMap" "bumpMap" "normalMap"
   "displacementMap" "roughnessMap" "metalnessMap" "alphaMap" "envMap"])

(defn- each-material [material f]
  (cond
    (nil? material) nil
    (array? material) (doseq [item (array-seq material)] (f item))
    :else (f material)))

(defn- collect-material! [materials textures images material]
  (when (and material (not (.has materials material)))
    (.add materials material)
    (doseq [property texture-properties]
      (when-let [texture (aget material property)]
        (.add textures texture)
        (when-let [image (some-> texture .-source .-data)]
          (.add images image))))))

(defn- dispose-collected! [geometries materials textures images]
  (.forEach textures #(.dispose %))
  ;; ImageBitmap allocations live outside the JS heap and need an explicit
  ;; close when a third-party embedded model uses them.
  (.forEach images #(when (fn? (.-close %)) (.close %)))
  (.forEach materials #(.dispose %))
  (.forEach geometries #(.dispose %)))

(defn dispose-material! [material]
  (let [materials (js/Set.)
        textures (js/Set.)
        images (js/Set.)]
    (each-material material #(collect-material! materials textures images %))
    (dispose-collected! (js/Set.) materials textures images)))

(defn dispose-object! [root]
  (when root
    (let [geometries (js/Set.)
          materials (js/Set.)
          textures (js/Set.)
          images (js/Set.)]
      (.traverse root
                 (fn [object]
                   (when-let [geometry (.-geometry object)]
                     (.add geometries geometry))
                   (each-material (.-material object)
                                  #(collect-material! materials textures images %))
                   (when-let [skeleton (.-skeleton object)]
                     (when (fn? (.-dispose skeleton)) (.dispose skeleton)))))
      (dispose-collected! geometries materials textures images))))

(defn key-surface-y
  "Top of the authored cap in its metre-scale animation pivot's coordinates."
  [node]
  (let [bounds (THREE/Box3.)
        top (THREE/Vector3.)]
    (.updateWorldMatrix node true true)
    (.setFromObject bounds node)
    (.getCenter bounds top)
    (set! (.-y top) (.-y (.-max bounds)))
    (.worldToLocal node top)
    (.-y top)))

(defn- normalize-materials! [root key-names]
  (let [keys* (atom {})
        key-meshes (js/Set.)
        detailed-key-mesh?* (atom false)
        original-materials (js/Set.)
        original-textures (js/Set.)
        original-images (js/Set.)
        key-material (THREE/MeshPhysicalMaterial. (material-options :keycap))
        surface-detail (surface-detail-textures)
        case-material (THREE/MeshStandardMaterial. (material-options :case))
        pcb-material (THREE/MeshStandardMaterial. (material-options :pcb))
        display-screen-material (THREE/MeshPhysicalMaterial.
                                 (material-options :display-screen))
        glass-material (THREE/MeshPhysicalMaterial. (material-options :glass))
        keyswitch-material (THREE/MeshStandardMaterial. (material-options :keyswitch))
        contact-material (THREE/MeshStandardMaterial. (material-options :contact))
        wire-red-material (THREE/MeshStandardMaterial. (material-options :wire-red))
        wire-black-material (THREE/MeshStandardMaterial. (material-options :wire-black))
        battery-connector-material (THREE/MeshStandardMaterial.
                                    (material-options :connector-battery))
        display-connector-material (THREE/MeshStandardMaterial.
                                    (material-options :connector-display))
        connector-material (THREE/MeshStandardMaterial. (material-options :connector))
        metal-material (THREE/MeshStandardMaterial. (material-options :metal))
        accent-material (THREE/MeshStandardMaterial. (material-options :accent))
        led-blue-material (THREE/MeshStandardMaterial. (material-options :led-blue))
        led-amber-material (THREE/MeshStandardMaterial. (material-options :led-amber))
        ceramic-material (THREE/MeshStandardMaterial. (material-options :ceramic))
        component-material (THREE/MeshStandardMaterial. (material-options :component))
        screw-material (THREE/MeshStandardMaterial. (material-options :screw))]
    ;; Key mappings may name a Mesh or a transform Group. Press animation is
    ;; applied at that named origin; every descendant mesh receives key styling.
    (doseq [name key-names]
      (when-let [node (.getObjectByName root name)]
        (let [meshes (atom [])]
          (.traverse node (fn [child]
                            (when (.-isMesh child)
                              (.add key-meshes child)
                              (swap! meshes conj child))))
          (when (seq @meshes)
            (swap! keys* assoc name {:node node
                                     :meshes @meshes
                                     :base-y (.-y (.-position node))
                                     :surface-y (key-surface-y node)
                                     :pressed 0
                                     :glow 0})))))
    (.traverse root
               (fn [object]
                 (when (.-isMesh object)
                   ;; Detailed STL meshes receive shading but use lightweight
                   ;; controller-owned proxies for their cached ground shadow.
                   ;; The small key meshes can cast directly.
                   (set! (.-castShadow object) (.has key-meshes object))
                   (set! (.-receiveShadow object) false)
                   (each-material (.-material object)
                                  #(collect-material! original-materials
                                                      original-textures
                                                      original-images %))
                   (let [name (or (.-name object) "")]
                     (let [key-mesh? (.has key-meshes object)
                           replacement
                           (.clone (cond
                                     key-mesh? key-material
                                     (.startsWith name "glass_") glass-material
                                     (.startsWith name "display_screen_") display-screen-material
                                     (or (.startsWith name "display_")
                                         (.startsWith name "pcb_")) pcb-material
                                     (.startsWith name "keyswitch_") keyswitch-material
                                     (.startsWith name "contact_") contact-material
                                     (.startsWith name "wire_red_") wire-red-material
                                     (.startsWith name "wire_black_") wire-black-material
                                     (.startsWith name "connector_battery_") battery-connector-material
                                     (.startsWith name "connector_display_") display-connector-material
                                     (.startsWith name "connector_") connector-material
                                     (.startsWith name "metal_") metal-material
                                     (.startsWith name "accent_led_blue_") led-blue-material
                                     (.startsWith name "accent_led_amber_") led-amber-material
                                     (.startsWith name "accent_") accent-material
                                     (.startsWith name "component_ceramic_") ceramic-material
                                     (.startsWith name "component_radio_") metal-material
                                     (.startsWith name "component_") component-material
                                     (.startsWith name "screw_") screw-material
                                     :else case-material))]
                       ;; Texture maps require UVs. Packages without them keep
                       ;; the same physically based base material and remain
                       ;; fully valid rather than compiling a broken shader.
                       (when (and key-mesh?
                                  (some-> (.-geometry object)
                                          (js-invoke "getAttribute" "uv")))
                         (reset! detailed-key-mesh?* true)
                         (aset replacement "roughnessMap" (:roughness surface-detail))
                         (aset replacement "normalMap" (:normal surface-detail))
                         (aset replacement "normalScale" (THREE/Vector2. 0.11 0.11)))
                       (set! (.-material object) replacement))))))
    (swap! keys*
           (fn [records]
             (into {}
                   (map (fn [[name record]]
                          [name (assoc record :materials
                                      (mapv #(.-material %) (:meshes record)))])
                        records))))
    (dispose-collected! (js/Set.) original-materials original-textures original-images)
    (.dispose key-material)
    (.dispose case-material)
    (.dispose pcb-material)
    (.dispose display-screen-material)
    (.dispose glass-material)
    (.dispose keyswitch-material)
    (.dispose contact-material)
    (.dispose wire-red-material)
    (.dispose wire-black-material)
    (.dispose battery-connector-material)
    (.dispose display-connector-material)
    (.dispose connector-material)
    (.dispose metal-material)
    (.dispose accent-material)
    (.dispose led-blue-material)
    (.dispose led-amber-material)
    (.dispose ceramic-material)
    (.dispose component-material)
    (.dispose screw-material)
    (when-not @detailed-key-mesh?*
      (.dispose (:roughness surface-detail))
      (.dispose (:normal surface-detail)))
    @keys*))

(defn- preserve-materials! [root key-names]
  (let [records* (atom {})
        key-meshes (js/Set.)
        original-key-materials (js/Set.)
        retained-materials (js/Set.)]
    (doseq [name key-names]
      (when-let [node (.getObjectByName root name)]
        (let [meshes* (atom [])
              materials* (atom [])]
          (.traverse node
                     (fn [child]
                       (when (.-isMesh child)
                         (.add key-meshes child)
                         (swap! meshes* conj child)
                         (let [clone! (fn [original]
                                        (.add original-key-materials original)
                                        (let [material (.clone original)]
                                          ;; Keep the authored albedo, maps and shading. Each
                                          ;; key needs its own emissive channel for feedback.
                                          (when-let [emissive (.-emissive material)]
                                            (.setHex emissive 0x8b83ff)
                                            (set! (.-emissiveIntensity material) 0))
                                          (swap! materials* conj material)
                                          material))]
                           (set! (.-material child)
                                 (if (array? (.-material child))
                                   (.map (.-material child) clone!)
                                   (clone! (.-material child))))))))
          (when (seq @meshes*)
            (swap! records* assoc name
                   {:node node :meshes @meshes* :materials @materials*
                    :base-y (.-y (.-position node)) :surface-y (key-surface-y node)
                    :pressed 0 :glow 0})))))
    (.traverse root
               (fn [object]
                 (when (.-isMesh object)
                   (set! (.-castShadow object) (.has key-meshes object))
                   (set! (.-receiveShadow object) false)
                   (each-material (.-material object)
                                  (fn [material]
                                    (.add retained-materials material)
                                    ;; Transparent covers must not occlude the live LCD
                                    ;; with an opaque depth value. Keep their authored alpha.
                                    (when (.-transparent material)
                                      (set! (.-depthWrite material) false)))))))
    (.forEach original-key-materials
              (fn [material]
                (when-not (.has retained-materials material)
                  ;; The clones still own any shared texture maps.
                  (.dispose material))))
    @records*))

(defn replace-materials!
  ([root key-names] (replace-materials! root key-names :normalized))
  ([root key-names material-policy]
   (if (= :preserve material-policy)
     (preserve-materials! root key-names)
     (normalize-materials! root key-names))))

(defn add-key-ambient-occlusion!
  "Adds one very soft contact proxy beneath each mapped key. This is a static,
  model-owned approximation of local ambient occlusion—not a screen-space
  post-process—so it cannot create the old canvas-edge/DOF rim artifacts."
  [key-records]
  (let [geometry (THREE/PlaneGeometry. 0.0158 0.0152)
        texture (contact-occlusion-texture)
        material (THREE/MeshBasicMaterial.
                  #js {:color 0x000000
                       :alphaMap texture
                       :transparent true
                       :opacity 0.17
                       :depthWrite false
                       :toneMapped false
                       :side (.-DoubleSide THREE)})
        installed* (atom 0)]
    (doseq [[node-name {:keys [node base-y]}] key-records
            :let [parent (aget node "parent")]
            :when parent]
      (let [proxy (THREE/Mesh. geometry material)]
        (aset proxy "name" (str "pretypist_key_ao_" node-name))
        (.copy (aget proxy "position") (aget node "position"))
        (aset (aget proxy "position") "y" (- base-y 0.00028))
        (.copy (aget proxy "quaternion") (aget node "quaternion"))
        (.rotateX proxy (- (/ js/Math.PI 2)))
        (aset proxy "renderOrder" 1)
        (.add parent proxy)
        (swap! installed* inc)))
    (when (zero? @installed*)
      (.dispose geometry)
      (.dispose material)
      (.dispose texture))
    @installed*))

(defn fit-model! [root]
  (let [box (THREE/Box3.)
        center (THREE/Vector3.)
        size (THREE/Vector3.)]
    (.setFromObject box root)
    (.getCenter box center)
    (.getSize box size)
    (set! (.-x (.-position root)) (- (.-x center)))
    (set! (.-z (.-position root)) (- (.-z center)))
    {:center center :size size}))
