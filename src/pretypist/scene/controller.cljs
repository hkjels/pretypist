(ns pretypist.scene.controller
  (:require [pretypist.layout.schema :as schema]
            [pretypist.layout.theme :as layout-theme]
            [pretypist.scene.displays :as displays]
            [pretypist.scene.floor :as floor]
            [pretypist.scene.legends :as legends]
            [pretypist.scene.model :as model]
            ["three" :as THREE]
            ["three/examples/jsm/environments/RoomEnvironment.js" :refer [RoomEnvironment]]
            ["three/examples/jsm/loaders/GLTFLoader.js" :refer [GLTFLoader]]))

(def camera-presets
  {:menu {:position [0 0.145 0.182] :look-at [0 0.006 0.002]}
   :practice {:position [0 0.218 0.3] :look-at [0 0 -0.01]}
   :layers {:position [0 0.132 0.165] :look-at [0 0.006 0.002]}
   :landing {:position [-0.043 0.104 0.118] :look-at [-0.042 0.008 0.001]}})

(def landing-poses
  [{:camera (:landing camera-presets)
    :left {:offset [0.038 0.006 0.008] :rotation [-0.08 -0.16 -0.035]}
    :right {:offset [0.205 -0.012 -0.025] :rotation [0 0.08 0.04]}
    :explode 0}
   {:camera {:position [-0.045 0.192 0.082] :look-at [-0.043 0.002 0.001]}
    :left {:offset [0.035 0.008 0.003] :rotation [-0.02 -0.1 -0.015]}
    :right {:offset [0.19 -0.01 -0.018] :rotation [0 0.05 0.03]}
    :explode 0.08}
   {:camera {:position [0 0.146 0.178] :look-at [0 0.006 0.002]}
    :left {:offset [-0.006 0.002 0] :rotation [0 0.035 -0.018]}
    :right {:offset [0.006 0.002 0] :rotation [0 -0.035 0.018]}
    :explode 0.04}
   {:camera {:position [0 0.188 0.224] :look-at [0 0.009 0.001]}
    :left {:offset [-0.026 0.004 -0.006] :rotation [-0.04 0.08 -0.035]}
    :right {:offset [0.026 0.004 -0.006] :rotation [-0.04 -0.08 0.035]}
    :explode 1}
   {:camera (:menu camera-presets)
    :left {:offset [0 0 0] :rotation [0 0 0]}
    :right {:offset [0 0 0] :rotation [0 0 0]}
    :explode 0}])

(defn- mix [from to amount]
  (+ from (* (- to from) amount)))

(defn- mix-vector [from to amount]
  (mapv #(mix (nth from %) (nth to %) amount) (range (count from))))

(defn landing-pose-at
  "Interpolates the five authored hero poses without touching application state."
  [progress]
  (let [maximum (dec (count landing-poses))
        progress (max 0 (min maximum (or progress 0)))
        from-index (js/Math.floor progress)
        to-index (min maximum (inc from-index))
        amount (- progress from-index)
        from (nth landing-poses from-index)
        to (nth landing-poses to-index)
        pose-part (fn [part]
                    {:offset (mix-vector (get-in from [part :offset])
                                         (get-in to [part :offset]) amount)
                     :rotation (mix-vector (get-in from [part :rotation])
                                           (get-in to [part :rotation]) amount)})]
    {:camera {:position (mix-vector (get-in from [:camera :position])
                                    (get-in to [:camera :position]) amount)
              :look-at (mix-vector (get-in from [:camera :look-at])
                                   (get-in to [:camera :look-at]) amount)}
     :left (pose-part :left)
     :right (pose-part :right)
     :explode (mix (:explode from) (:explode to) amount)}))

(defn- key-index [node-name]
  (when (and (string? node-name) (.startsWith node-name "key_"))
    (js/parseInt (subs node-name 4) 10)))

(defn landing-key-glow [progress node-name]
  (let [index (key-index node-name)
        progress (max 0 (min 4 (or progress 0)))
        chapter (js/Math.floor progress)
        local (- progress chapter)
        left? (and index (or (<= 0 index 4) (<= 10 index 14)
                             (<= 20 index 24) (<= 30 index 32)))]
    (if (nil? index)
      0
      (case chapter
        0 (if left?
            (max 0.04 (- 0.7 (js/Math.abs (- (* local 18) (mod index 18)))))
            0)
        1 (let [sequence [32 7 32 14]
                active (nth sequence (min 3 (js/Math.floor (* local 4))))]
            (if (= index active) 0.92 0.02))
        2 (max 0.03 (- 0.72 (js/Math.abs (- (* local 36) index))))
        3 (+ 0.08 (* 0.2 (max 0 (- 1 (* 2 (js/Math.abs (- local 0.5)))))))
        0))))

(def transition-seconds 1.05)

(defn camera-aspect-scale
  "Keep a split keyboard inside a narrow scene region without scaling meshes.
  The landing's opening chapters frame one half; later chapters show both."
  [mode aspect progress]
  (let [both-halves (max 0 (min 1 (- (or progress 0) 1)))
        reference-aspect (if (= mode :landing) (+ 1.85 (* 0.95 both-halves)) 2.8)]
    (max 1 (/ reference-aspect (max 0.1 aspect)))))

(def presentation-light-multiplier
  {:landing 1.8
   :menu 1.7
   :practice 1.12
   :layers 1.85})

(def orbit-limits
  {:yaw [-0.46 0.46]
   ;; Negative pitch raises the viewpoint toward a top-down view. The lower
   ;; bound stops before the polar singularity; the upper bound never exposes
   ;; the underside of the board.
   :pitch [-0.62 0.24]})

(defn- hex-color [value]
  (js/parseInt (subs value 1) 16))

(defn- clamp [minimum maximum value]
  (max minimum (min maximum value)))

(def shadow-caster-names
  {:left ["shadow_caster_left" "shadow_caster"]
   :right ["shadow_caster_right" "shadow_caster"]})

(def max-direct-shadow-vertices 2048)
(def max-shadow-hull-meshes 8)
(def max-shadow-hull-samples 8192)

(defn sample-indices
  "Returns a deterministic, endpoint-preserving sample of a vertex range."
  [item-count sample-limit]
  (cond
    (or (not (pos? item-count)) (not (pos? sample-limit))) []
    (<= item-count sample-limit) (vec (range item-count))
    (= sample-limit 1) [0]
    :else
    (mapv (fn [sample]
            (js/Math.floor
             (/ (* sample (dec item-count))
                (dec sample-limit))))
          (range sample-limit))))

(defn- turn [[origin-x origin-y] [a-x a-y] [b-x b-y]]
  (- (* (- a-x origin-x) (- b-y origin-y))
     (* (- a-y origin-y) (- b-x origin-x))))

(defn- half-hull [points]
  (reduce (fn [hull point]
            (conj (loop [result hull]
                    (if (and (>= (count result) 2)
                             (<= (turn (nth result (- (count result) 2))
                                       (peek result)
                                       point)
                                 1e-12))
                      (recur (pop result))
                      result))
                  point))
          []
          points))

(defn convex-hull
  "Returns the counter-clockwise outer hull of finite two-dimensional points."
  [points]
  (let [points (->> points
                    (filter (fn [point]
                              (and (= 2 (count point))
                                   (js/Number.isFinite (first point))
                                   (js/Number.isFinite (second point)))))
                    distinct
                    (sort-by (juxt first second))
                    vec)]
    (if (< (count points) 3)
      points
      (let [lower (half-hull points)
            upper (half-hull (reverse points))]
        (vec (concat (butlast lower) (butlast upper)))))))

(defn- position-attribute [mesh]
  (when-let [geometry (.-geometry mesh)]
    (js-invoke geometry "getAttribute" "position")))

(defn- mesh-vertex-count [mesh]
  (or (some-> (position-attribute mesh) (aget "count")) 0))

(defn- descendant-meshes [node]
  (let [meshes (atom [])]
    (when node
      (.traverse node
                 (fn [object]
                   (when (and (.-isMesh object) (position-attribute object))
                     (swap! meshes conj object)))))
    @meshes))

(defn- clone-shadow-only-material [material]
  ;; Three.js uses a separate depth material for the shadow pass. Keeping the
  ;; caster material visible while disabling its color and depth writes makes
  ;; the mesh a caster without adding a silhouette to the Replicant canvas.
  ;; Clone first because GLTF nodes routinely share material instances.
  (when material
    (let [clone (.clone material)]
      (aset clone "visible" true)
      (aset clone "colorWrite" false)
      (aset clone "depthWrite" false)
      (aset clone "transparent" true)
      (aset clone "opacity" 0)
      (aset clone "side" (.-DoubleSide THREE))
      clone)))

(defn- clone-shadow-only-materials [material]
  (cond
    (nil? material) nil
    (array? material) (into-array (map clone-shadow-only-material
                                       (array-seq material)))
    :else (clone-shadow-only-material material)))

(defn- configure-shadow-only! [node]
  (doseq [mesh (descendant-meshes node)]
    (set! (.-material mesh)
          (clone-shadow-only-materials (.-material mesh)))
    (set! (.-castShadow mesh) true)
    (set! (.-receiveShadow mesh) false)
    (set! (.-frustumCulled mesh) false)))

(defn- configure-lightweight-casters! [meshes]
  (doseq [mesh meshes
          :when (<= (mesh-vertex-count mesh) max-direct-shadow-vertices)]
    (set! (.-castShadow mesh) true)
    (set! (.-receiveShadow mesh) false)))

(defn- sampled-shadow-footprint [half meshes]
  (let [meshes (->> meshes
                    (filter #(> (mesh-vertex-count %) max-direct-shadow-vertices))
                    (sort-by mesh-vertex-count >)
                    (take max-shadow-hull-meshes)
                    vec)
        sample-limit (when (seq meshes)
                       (max 1 (js/Math.floor (/ max-shadow-hull-samples
                                                (count meshes)))))
        inverse-half (THREE/Matrix4.)
        local-matrix (THREE/Matrix4.)
        point (THREE/Vector3.)
        points* (atom [])
        minimum-y* (atom js/Infinity)
        maximum-y* (atom js/-Infinity)]
    (when (seq meshes)
      (js-invoke inverse-half "copy" (aget half "matrixWorld"))
      (js-invoke inverse-half "invert")
      (doseq [mesh meshes
              :let [position (position-attribute mesh)]]
        (js-invoke local-matrix "multiplyMatrices"
                   inverse-half
                   (aget mesh "matrixWorld"))
        (doseq [index (sample-indices (aget position "count") sample-limit)]
          (js-invoke point "fromBufferAttribute" position index)
          (js-invoke point "applyMatrix4" local-matrix)
          (let [x (.-x point)
                y (.-y point)
                z (.-z point)]
            (when (and (js/Number.isFinite x)
                       (js/Number.isFinite y)
                       (js/Number.isFinite z))
              (swap! points* conj [x z])
              (swap! minimum-y* min y)
              (swap! maximum-y* max y))))))
    {:points @points*
     :minimum-y @minimum-y*
     :maximum-y @maximum-y*}))

(defn- add-derived-shadow-caster! [half side meshes]
  (let [{:keys [points minimum-y maximum-y]} (sampled-shadow-footprint half meshes)
        hull (convex-hull points)]
    (when (and (>= (count hull) 3)
               (js/Number.isFinite minimum-y)
               (js/Number.isFinite maximum-y))
      (let [shape (THREE/Shape.)
            [start & remaining] hull
            _ (js-invoke shape "moveTo" (first start) (second start))
            _ (doseq [[x z] remaining] (js-invoke shape "lineTo" x z))
            _ (js-invoke shape "closePath")
            geometry (THREE/ShapeGeometry. shape)
            _ (js-invoke geometry "rotateX" (- (/ js/Math.PI 2)))
            material (THREE/MeshBasicMaterial.
                      #js {:color 0x000000
                           :colorWrite false
                           :depthWrite false
                           :transparent true
                           :opacity 0
                           :side (.-DoubleSide THREE)})
            caster (THREE/Mesh. geometry material)
            height (- maximum-y minimum-y)]
        (set! (.-name caster) (str "pretypist_shadow_silhouette_" (name side)))
        (set! (.-castShadow caster) true)
        (set! (.-receiveShadow caster) false)
        (set! (.-frustumCulled caster) false)
        ;; The shell midpoint keeps the directional shadow registered with the
        ;; model without pretending that its irregular case is a solid block.
        (set! (.-y (.-position caster)) (+ minimum-y (* height 0.45)))
        (.add half caster)
        caster))))

(defn- explicit-shadow-caster [root half side]
  (let [[specific generic] (get shadow-caster-names side)]
    (or (.getObjectByName root specific)
        (.getObjectByName half generic))))

(defn- case-node-for-half [half layout]
  (some (fn [node-name]
          (when (string? node-name)
            (.getObjectByName half node-name)))
        (get-in layout [:model :case-nodes])))

(defn- install-shadow-casters! [root layout]
  (js-invoke root "updateMatrixWorld" true)
  (let [key-node-names (set (map :node (:keys layout)))]
    (doseq [[side half-name] [[:left (get-in layout [:model :left-half-node])]
                              [:right (get-in layout [:model :right-half-node])]]]
      (when-let [half (.getObjectByName root half-name)]
        (let [key-meshes (reduce (fn [result node-name]
                                   (if-let [node (.getObjectByName half node-name)]
                                     (into result (descendant-meshes node))
                                     result))
                                 #{}
                                 key-node-names)
              explicit-candidate (explicit-shadow-caster root half side)
              candidate-meshes (set (descendant-meshes explicit-candidate))
              ;; A package may accidentally use a reserved caster name for a
              ;; mapped key. Prefer a functional key over hiding it as a
              ;; shadow-only mesh, then fall back to derived case geometry.
              explicit (when (and (seq candidate-meshes)
                                  (not-any? key-meshes candidate-meshes))
                         explicit-candidate)
              explicit-meshes (if explicit candidate-meshes #{})
              excluded-meshes (into key-meshes explicit-meshes)
              source (or (case-node-for-half half layout) half)
              source-meshes (remove excluded-meshes (descendant-meshes source))]
          ;; Small source meshes are cheaper and more accurate as direct
          ;; casters. Only detailed meshes are replaced by a sampled hull.
          (configure-lightweight-casters! source-meshes)
          (if (seq explicit-meshes)
            (configure-shadow-only! explicit)
            (add-derived-shadow-caster! half side source-meshes)))))))

(defn orbit-rotation
  "Returns a clamped camera-orbit offset. Kept pure for input-level tests."
  [{:keys [yaw pitch]} delta-yaw delta-pitch]
  (let [[min-yaw max-yaw] (:yaw orbit-limits)
        [min-pitch max-pitch] (:pitch orbit-limits)]
    {:yaw (clamp min-yaw max-yaw (+ (or yaw 0) delta-yaw))
     :pitch (clamp min-pitch max-pitch (+ (or pitch 0) delta-pitch))}))

(defn apply-gesture-event
  "Updates only the scene-owned gesture channel of a key animation record."
  [record {:keys [kind]} reduced-motion?]
  (case kind
    :press (assoc record
                  :gesture-pressed (if reduced-motion? 0 1)
                  :gesture-glow 1)
    :release (assoc record :gesture-pressed 0 :gesture-glow 0)
    record))

(defn create-controller []
  (let [scene (THREE/Scene.)
        camera (THREE/PerspectiveCamera. 34 1 0.01 10)
        loader (GLTFLoader.)
        world (THREE/Group.)
        drafting-floor (floor/create!)
        renderer* (atom nil)
        lights* (atom nil)
        environment-target* (atom nil)
        canvas* (atom nil)
        model* (atom nil)
        display-manager* (atom nil)
        display-status* (atom nil)
        keys* (atom {})
        layout* (atom nil)
        animation* (atom nil)
        render-until* (atom 0)
        load-token* (atom 0)
        disposed?* (atom false)
        resize-observer* (atom nil)
        visible?* (atom true)
        reduced?* (atom false)
        mode* (atom :menu)
        ambient* (atom 0.42)
        light-level* (atom (* 0.42 (get presentation-light-multiplier :menu)))
        light-target* (atom @light-level*)
        light-transition* (atom nil)
        glow* (atom 0.72)
        layer-colors* (atom layout-theme/default-layer-colors)
        landing-progress* (atom 0)
        landing-halves* (atom {})
        landing-explode* (atom 0)
        last-landing-shadow-progress* (atom nil)
        active-layer* (atom :base)
        legend-scope* (atom #{})
        legend-signature* (atom nil)
        expected* (atom #{})
        gesture* (atom nil)
        camera-target* (atom (:menu camera-presets))
        camera-transition* (atom nil)
        current-look-at (THREE/Vector3. 0 0 -0.005)
        base-camera-position (THREE/Vector3.)
        camera-destination (THREE/Vector3.)
        look-at-destination (THREE/Vector3.)
        orbit-offset (THREE/Vector3.)
        orbit-spherical (THREE/Spherical.)
        orbit* (atom {:yaw 0 :pitch 0})
        orbit-enabled?* (atom false)
        drag* (atom nil)
        last-frame* (atom 0)
        activity-at* (atom 0)
        activity-dim* (atom 1)
        callbacks* (atom nil)
        listeners* (atom [])
        shadow-geometry (THREE/PlaneGeometry. 0.48 0.28)
        shadow-material (THREE/ShadowMaterial.
                         #js {:color 0x000000
                              :opacity 0.24
                              :transparent true
                              :depthWrite false})
        shadow-catcher (THREE/Mesh. shadow-geometry shadow-material)]
    (.add scene world)
    (.add world (:mesh drafting-floor))
    (set! (.-receiveShadow shadow-catcher) true)
    (set! (.-renderOrder shadow-catcher) -1)
    (set! (.-x (.-rotation shadow-catcher)) (- (/ js/Math.PI 2)))
    (set! (.-y (.-position shadow-catcher)) -0.0036)
    (set! (.-z (.-position shadow-catcher)) 0.008)
    (.add world shadow-catcher)
    ;; A low ambient wash supports a conventional three-point rig. The warm
    ;; key defines the caps, the neutral fill keeps the dark case readable,
    ;; and the restrained rear light separates its silhouette from the page.
    (let [hemisphere (THREE/HemisphereLight. 0xfff7eb 0x111216 0.44)
          key-light (THREE/DirectionalLight. 0xffe2c2 1.3)
          fill-light (THREE/DirectionalLight. 0xe9edf2 0.62)
          rim-light (THREE/DirectionalLight. 0xd5dde7 0.38)]
      (.set (.-position key-light) -0.24 0.34 0.26)
      (.set (.-position fill-light) 0.27 0.2 0.18)
      (.set (.-position rim-light) 0.04 0.25 -0.28)
      (set! (.-castShadow key-light) true)
      (let [shadow (.-shadow key-light)
            shadow-camera (.-camera shadow)]
        (.set (.-mapSize shadow) 1024 1024)
        (set! (.-near shadow-camera) 0.08)
        (set! (.-far shadow-camera) 0.9)
        (set! (.-left shadow-camera) -0.32)
        (set! (.-right shadow-camera) 0.32)
        (set! (.-top shadow-camera) 0.22)
        (set! (.-bottom shadow-camera) -0.2)
        (set! (.-bias shadow) -0.00015)
        (set! (.-normalBias shadow) 0.0012))
      (reset! lights* {:hemisphere hemisphere :key key-light :fill fill-light :rim rim-light})
      (.add scene hemisphere key-light fill-light rim-light))
    (letfn [(add-listener! [target event handler]
              (.addEventListener target event handler)
              (swap! listeners* conj [target event handler]))
            (invalidate! [duration-ms]
              (swap! render-until* max (+ (js/performance.now) duration-ms))
              (schedule-frame!))
            (request-shadow-update! []
              (when-let [renderer @renderer*]
                (set! (.-needsUpdate (.-shadowMap renderer)) true)
                (invalidate! 80)))
            (resize! []
              (when-let [renderer @renderer*]
                (let [canvas @canvas*
                      width (max 1 (.-clientWidth canvas))
                      height (max 1 (.-clientHeight canvas))
                      dpr (min 2 (or js/window.devicePixelRatio 1))]
                  (.setPixelRatio renderer dpr)
                  (.setSize renderer width height false)
                  ((:set-size! drafting-floor) (* width dpr) (* height dpr))
                  (set! (.-aspect camera) (/ width height))
                  (.updateProjectionMatrix camera)
                  (apply-camera-orbit!)
                  (invalidate! 80))))
            (apply-camera-orbit! []
              (let [{:keys [yaw pitch]} @orbit*
                    orbit-amount (max (/ (js/Math.abs yaw) 0.46)
                                      (/ (js/Math.abs pitch) 0.62))
                    top-down-amount (clamp 0 1 (/ (- pitch) 0.62))
                    effective-yaw (* yaw (- 1 (* top-down-amount 0.3)))]
                (.copy orbit-offset base-camera-position)
                (.sub orbit-offset current-look-at)
                (.setFromVector3 orbit-spherical orbit-offset)
                ;; A small automatic dolly at the extremes keeps both halves
                ;; framed when yaw and elevation are combined.
                (set! (.-radius orbit-spherical)
                      (* (.-radius orbit-spherical) (+ 1 (* orbit-amount 0.16))))
                (set! (.-radius orbit-spherical)
                      (* (.-radius orbit-spherical)
                         (camera-aspect-scale @mode* (.-aspect camera) @landing-progress*)))
                (set! (.-theta orbit-spherical)
                      (+ (.-theta orbit-spherical) effective-yaw))
                (set! (.-phi orbit-spherical)
                      (clamp 0.2 (- js/Math.PI 0.2)
                             (+ (.-phi orbit-spherical) pitch)))
                (.setFromSpherical orbit-offset orbit-spherical)
                (.copy (.-position camera) current-look-at)
                (.add (.-position camera) orbit-offset)
                (.lookAt camera current-look-at)))
            (set-camera-now! [preset]
              (let [{:keys [position look-at]} preset]
                (apply #(.set base-camera-position %1 %2 %3) position)
                (apply #(.set current-look-at %1 %2 %3) look-at)
                (reset! camera-transition* nil)
                (apply-camera-orbit!)))
            (capture-landing-halves! [root layout]
              (reset! landing-halves*
                      (into {}
                            (keep (fn [[side path]]
                                    (when-let [object (.getObjectByName root (get-in layout path))]
                                      [side {:object object
                                             :position (.clone (.-position object))
                                             :quaternion (.clone (.-quaternion object))}]))
                                  [[:left [:model :left-half-node]]
                                   [:right [:model :right-half-node]]]))))
            (restore-landing-model! []
              (doseq [[_ {:keys [object position quaternion]}] @landing-halves*]
                (.copy (.-position object) position)
                (.copy (.-quaternion object) quaternion))
              (reset! landing-explode* 0)
              (reset! last-landing-shadow-progress* nil))
            (apply-half-pose! [side {:keys [offset rotation]}]
              (when-let [{:keys [object position quaternion]} (get @landing-halves* side)]
                (.copy (.-position object) position)
                (set! (.-x (.-position object)) (+ (.-x position) (nth offset 0)))
                (set! (.-y (.-position object)) (+ (.-y position) (nth offset 1)))
                (set! (.-z (.-position object)) (+ (.-z position) (nth offset 2)))
                (.copy (.-quaternion object) quaternion)
                (.rotateX object (nth rotation 0))
                (.rotateY object (nth rotation 1))
                (.rotateZ object (nth rotation 2))))
            (apply-landing-pose! [progress]
              (let [progress (max 0 (min 4 (or progress 0)))
                    pose (landing-pose-at progress)
                    shadow-progress @last-landing-shadow-progress*]
                (reset! landing-progress* progress)
                (reset! landing-explode* (:explode pose))
                (when (= @mode* :landing)
                  (apply-half-pose! :left (:left pose))
                  (apply-half-pose! :right (:right pose))
                  (set-camera-now! (:camera pose))
                  (if (or (nil? shadow-progress)
                          (> (js/Math.abs (- progress shadow-progress)) 0.045))
                    (do (reset! last-landing-shadow-progress* progress)
                        (request-shadow-update!))
                    (invalidate! 140)))))
            (begin-camera-transition! [preset]
              (reset! camera-target* preset)
              (invalidate! (if @reduced?* 80 1150))
              (if @reduced?*
                (set-camera-now! preset)
                (reset! camera-transition*
                        {:elapsed 0
                         :from-position [(.-x base-camera-position)
                                         (.-y base-camera-position)
                                         (.-z base-camera-position)]
                         :from-look-at [(.-x current-look-at)
                                        (.-y current-look-at)
                                        (.-z current-look-at)]
                         :to-position (:position preset)
                         :to-look-at (:look-at preset)})))
            (animate-camera! [dt]
              (when-let [{:keys [elapsed from-position from-look-at to-position to-look-at]}
                         @camera-transition*]
                (let [elapsed (if @reduced?* transition-seconds
                                  (min transition-seconds (+ elapsed dt)))
                      progress (/ elapsed transition-seconds)
                      eased (- 1 (js/Math.pow (- 1 progress) 3))
                      blend (fn [from to component]
                              (+ (nth from component)
                                 (* (- (nth to component) (nth from component)) eased)))]
                  (.set camera-destination
                        (blend from-position to-position 0)
                        (blend from-position to-position 1)
                        (blend from-position to-position 2))
                  (.set look-at-destination
                        (blend from-look-at to-look-at 0)
                        (blend from-look-at to-look-at 1)
                        (blend from-look-at to-look-at 2))
                  (.copy base-camera-position camera-destination)
                  (.copy current-look-at look-at-destination)
                  (if (= elapsed transition-seconds)
                    (reset! camera-transition* nil)
                    (swap! camera-transition* assoc :elapsed elapsed))))
              (apply-camera-orbit!))
            (render-light-level! [value]
              (let [{:keys [hemisphere key fill rim]} @lights*]
                (when hemisphere (set! (.-intensity hemisphere) (+ 0.025 (* value 0.2))))
                (when key (set! (.-intensity key) (+ 0.18 (* value 1.1))))
                (when fill (set! (.-intensity fill) (+ 0.05 (* value 0.42))))
                (when rim (set! (.-intensity rim) (+ 0.06 (* value 0.44))))
                (set! (.-environmentIntensity scene) (+ 0.15 (* value 0.38)))
                (set! (.-opacity shadow-material) (+ 0.06 (* value 0.12)))))
            (apply-light-level! [value]
              (reset! light-level* value)
              (render-light-level! (* value @activity-dim*)))
            (presentation-light-level []
              (clamp 0 1 (* @ambient* (get presentation-light-multiplier @mode* 1))))
            (begin-light-transition! [target]
              (when (not= target @light-target*)
                (reset! light-target* target)
                (invalidate! (if @reduced?* 80 1150))
                (if @reduced?*
                  (do (apply-light-level! target)
                      (reset! light-transition* nil))
                  (reset! light-transition* {:elapsed 0 :from @light-level* :to target}))))
            (animate-lighting! [dt now]
              (when-let [{:keys [elapsed from to]} @light-transition*]
                (let [elapsed (if @reduced?* transition-seconds
                                  (min transition-seconds (+ elapsed dt)))
                      progress (/ elapsed transition-seconds)
                      eased (- 1 (js/Math.pow (- 1 progress) 3))]
                  (apply-light-level! (+ from (* (- to from) eased)))
                  (if (= elapsed transition-seconds)
                    (reset! light-transition* nil)
                    (swap! light-transition* assoc :elapsed elapsed))))
              (let [target-dim (if (and (= @mode* :practice)
                                        (< (- now @activity-at*) 650))
                                 0.84
                                 1)
                    alpha (if @reduced?* 1 (min 1 (* dt 7)))
                    next-dim (+ @activity-dim* (* (- target-dim @activity-dim*) alpha))]
                (when (not= next-dim @activity-dim*)
                  (reset! activity-dim* next-dim)
                  (render-light-level! (* @light-level* next-dim)))))
            (animate-keys! [dt _now]
              (let [expected-nodes @expected*]
                (doseq [[node-name record] @keys*
                        :let [node (:node record)]
                        :when node]
                  (let [pressed (max (or (:pressed record) 0)
                                     (or (:gesture-pressed record) 0))
                        landing-lift (if (= @mode* :landing)
                                       (* @landing-explode*
                                          (+ 0.0032 (* 0.00022 (mod (or (key-index node-name) 0) 5))))
                                       0)
                        target-y (+ (- (:base-y record) (* 0.0024 pressed)) landing-lift)
                        alpha (if @reduced?* 1 (min 1 (* dt 20)))
                        next-y (+ (.-y (.-position node)) (* (- target-y (.-y (.-position node))) alpha))
                        pulse (max 0 (- (:glow record) (* dt 4.5)))
                        expected? (and (nil? @gesture*)
                                       (contains? expected-nodes node-name))
                        glow (max pulse
                                  (or (:gesture-glow record) 0)
                                  (if expected? 0.7 0)
                                  (if (= @mode* :landing)
                                    (landing-key-glow @landing-progress* node-name)
                                    0))]
                    (set! (.-y (.-position node)) next-y)
                    (doseq [material (:materials record)]
                      (set! (.-emissiveIntensity material) (* @glow* glow)))
                    (when (not= pulse (:glow record))
                      (swap! keys* assoc-in [node-name :glow] pulse))))))
            (clear-gesture-keys! []
              (swap! keys*
                     (fn [records]
                       (into {}
                             (map (fn [[node-name record]]
                                    [node-name (assoc record
                                                      :gesture-pressed 0
                                                      :gesture-glow 0)]))
                             records))))
            (cancel-gesture! []
              (reset! gesture* nil)
              (clear-gesture-keys!)
              (invalidate! 180))
            (apply-gesture-event! [event]
              (when-let [node-name (node-for-key (:key-id event))]
                (when (contains? @keys* node-name)
                  (swap! keys* update node-name apply-gesture-event event @reduced?*))))
            (advance-gesture! [now]
              (when-let [{:keys [started-at events duration-ms] :as playback} @gesture*]
                (let [elapsed (- now started-at)
                      [due remaining] (split-with #(<= (:at-ms %) elapsed) events)]
                  (doseq [event due] (apply-gesture-event! event))
                  (if (>= elapsed duration-ms)
                    (do (reset! gesture* nil)
                        (clear-gesture-keys!))
                    (when (seq due)
                      (reset! gesture* (assoc playback :events (vec remaining))))))))
            (stop-animation! []
              (when-let [frame @animation*]
                (js/cancelAnimationFrame frame)
                (reset! animation* nil)))
            (schedule-frame! []
              (when (and @renderer* @visible?* (not @disposed?*) (nil? @animation*))
                (reset! animation* (js/requestAnimationFrame frame!))))
            (frame! [timestamp]
              (reset! animation* nil)
              (when (and @renderer* @visible?* (not @disposed?*))
                (try
                  (let [dt (min 0.05 (/ (- timestamp @last-frame*) 1000))]
                    (reset! last-frame* timestamp)
                    (animate-camera! dt)
                    (animate-lighting! dt timestamp)
                    (advance-gesture! timestamp)
                    (animate-keys! dt timestamp)
                    (.render @renderer* scene camera))
                  (catch :default error
                    (reset! visible?* false)
                    (when-let [failed (:failed @callbacks*)]
                      (failed (or (.-message error) "The keyboard scene stopped unexpectedly.")))))
                (when (< timestamp @render-until*)
                  (schedule-frame!))))
            (release-all! []
              (reset! drag* nil)
              (reset! gesture* nil)
              (swap! keys* (fn [records]
                             (into {} (map (fn [[name record]]
                                             [name (assoc record
                                                          :pressed 0
                                                          :gesture-pressed 0
                                                          :gesture-glow 0)]))
                                   records)))
              (invalidate! 280))
            (visibility-changed! []
              (let [visible? (not (.-hidden js/document))]
                (reset! visible?* visible?)
                (release-all!)
                (if visible?
                  (do (reset! last-frame* (js/performance.now))
                      (invalidate! 100))
                  (stop-animation!))))
            (unload! []
              (swap! load-token* inc)
              (when-let [manager @display-manager*]
                (displays/dispose! manager))
              (reset! display-manager* nil)
              (legends/clear-legends! @keys*)
              (when-let [root @model*]
                (.remove world root)
                (model/dispose-object! root))
              (reset! model* nil)
              (reset! keys* {})
              (reset! landing-halves* {})
              (reset! landing-explode* 0)
              (reset! expected* #{})
              (reset! gesture* nil)
              (reset! legend-signature* nil)
              (legends/dispose-cache!)
              (request-shadow-update!))
            (refresh-legends! []
              (when-let [layout @layout*]
                (let [layer @active-layer*
                      color (get @layer-colors* layer "#f1eee7")
                      node->legend (into {}
                                         (map (fn [{:keys [id node]}]
                                                [node (schema/legend-for layout layer id)]))
                                         (:keys layout))
                      visibility (if (= @mode* :layers) @legend-scope* #{})
                      signature [(:package/id layout) (:package/revision layout)
                                 layer visibility color node->legend]]
                  (when (not= signature @legend-signature*)
                    (legends/set-legends! @keys* node->legend color visibility)
                    (reset! legend-signature* signature)))))
            (mount! [canvas callbacks]
              (try
                (reset! disposed?* false)
                (reset! callbacks* callbacks)
                (reset! canvas* canvas)
                (let [renderer (THREE/WebGLRenderer.
                                #js {:canvas canvas :alpha true :antialias true
                                     :powerPreference "high-performance"})]
                  (reset! renderer* renderer)
                  (.setClearColor renderer 0x000000 0)
                  (set! (.-outputColorSpace renderer) (.-SRGBColorSpace THREE))
                  (set! (.-toneMapping renderer) (.-ACESFilmicToneMapping THREE))
                  (set! (.-toneMappingExposure renderer) 0.84)
                  (set! (.-enabled (.-shadowMap renderer)) true)
                  (set! (.-type (.-shadowMap renderer)) (.-PCFShadowMap THREE))
                  (set! (.-autoUpdate (.-shadowMap renderer)) false)
                  (let [environment (RoomEnvironment.)
                        pmrem (THREE/PMREMGenerator. renderer)
                        ;; Keep the PMREM blur within Three.js's 20-sample
                        ;; convolution budget (larger sigma values are clipped).
                        target (.fromScene pmrem environment 0.04)]
                    (reset! environment-target* target)
                    (set! (.-environment scene) (.-texture target))
                    (.dispose environment)
                    (.dispose pmrem))
                  (reset! resize-observer* (js/ResizeObserver. resize!))
                  (.observe @resize-observer* canvas)
                  (add-listener! js/document "visibilitychange" visibility-changed!)
                  (add-listener! js/window "blur" release-all!)
                  (add-listener! canvas "pointerdown"
                                 (fn [event]
                                   (when (and @orbit-enabled?*
                                              (not= false (.-isPrimary event))
                                              (zero? (.-button event)))
                                     (.preventDefault event)
                                     (.setPointerCapture canvas (.-pointerId event))
                                     (reset! drag* {:pointer-id (.-pointerId event)
                                                    :start-x (.-clientX event)
                                                    :start-y (.-clientY event)
                                                    :start-orbit @orbit*}))))
                  (add-listener! canvas "pointermove"
                                 (fn [event]
                                   (when-let [{:keys [pointer-id start-x start-y start-orbit]} @drag*]
                                     (when (= pointer-id (.-pointerId event))
                                       (reset! orbit*
                                               (orbit-rotation
                                                start-orbit
                                                (* (- (.-clientX event) start-x) 0.006)
                                                (* (- (.-clientY event) start-y) 0.006)))
                                       (apply-camera-orbit!)
                                       (invalidate! 80)))))
                  (let [finish-drag! (fn [event]
                                       (when (= (:pointer-id @drag*) (.-pointerId event))
                                         (reset! drag* nil)))]
                    (add-listener! canvas "pointerup" finish-drag!)
                    (add-listener! canvas "pointercancel" finish-drag!))
                  (add-listener! canvas "lostpointercapture" (fn [_] (reset! drag* nil)))
                  (resize!)
                  (set-camera-now! (:menu camera-presets))
                  (apply-light-level! @light-level*)
                  (request-shadow-update!)
                  (reset! last-frame* (js/performance.now))
                  (reset! visible?* (not (.-hidden js/document)))
                  (invalidate! 100)
                  ((:ready callbacks)))
                (catch :default error
                  (dispose!)
                  ((:failed callbacks) (or (.-message error) "WebGL initialization failed")))))
            (dispose! []
              (reset! disposed?* true)
              (swap! load-token* inc)
              (stop-animation!)
              (release-all!)
              (when-let [observer @resize-observer*] (.disconnect observer))
              (reset! resize-observer* nil)
              (doseq [[target event handler] @listeners*]
                (.removeEventListener target event handler))
              (reset! listeners* [])
              (unload!)
              (set! (.-environment scene) nil)
              (when-let [target @environment-target*]
                (.dispose target))
              (reset! environment-target* nil)
              (.dispose shadow-geometry)
              (.dispose shadow-material)
              ((:dispose! drafting-floor))
              (when-let [renderer @renderer*]
                (.dispose renderer)
                (.forceContextLoss renderer))
              (reset! renderer* nil)
              (reset! canvas* nil)
              (reset! callbacks* nil))
            (load! [array-buffer layout on-loaded on-error]
              (unload!)
              (reset! layout* layout)
              (let [token (swap! load-token* inc)]
                (.parse loader array-buffer ""
                        (fn [gltf]
                          (let [root (.-scene gltf)]
                            (if (or @disposed?* (not= token @load-token*))
                              (model/dispose-object! root)
                              (try
                                (let [names (set (map :node (:keys layout)))
                                      key-records (model/replace-materials!
                                                   root names (get-in layout [:model :material-policy]))]
                                  (when-not (= names (set (keys key-records)))
                                    (throw (js/Error. "Every mapped key node must be a renderable mesh.")))
                                  (set! (.-name root) "pretypist_loaded_layout")
                                  (model/fit-model! root)
                                  (reset! display-manager*
                                          (displays/install!
                                           root
                                           (:displays layout)
                                           (or @display-status*
                                               (:display/default-status layout))))
                                  (install-shadow-casters! root layout)
                                  (model/add-key-ambient-occlusion! key-records)
                                  (.add world root)
                                  (reset! model* root)
                                  (reset! keys* key-records)
                                  (capture-landing-halves! root layout)
                                  (when (= @mode* :landing)
                                    (apply-landing-pose! @landing-progress*))
                                  (reset! legend-signature* nil)
                                  (refresh-legends!)
                                  (request-shadow-update!)
                                  (on-loaded))
                                (catch :default error
                                  (when-let [manager @display-manager*]
                                    (displays/dispose! manager))
                                  (reset! display-manager* nil)
                                  (.remove world root)
                                  (reset! model* nil)
                                  (reset! keys* {})
                                  (model/dispose-object! root)
                                  (on-error error))))))
                        (fn [error]
                          (when (and (not @disposed?*) (= token @load-token*))
                            (on-error error))))))
            (node-for-key [key-id]
              (some-> (schema/key-by-id @layout* key-id) :node))
            (press! [key-id]
              (when-let [node-name (node-for-key key-id)]
                (when (contains? @keys* node-name)
                  (swap! keys* assoc-in [node-name :pressed] 1)
                  (invalidate! 280))))
            (release! [key-id]
              (when-let [node-name (node-for-key key-id)]
                (when (contains? @keys* node-name)
                  (swap! keys* assoc-in [node-name :pressed] 0)
                  (invalidate! 280))))
            (pulse! [key-id]
              (reset! activity-at* (js/performance.now))
              (when-let [node-name (node-for-key key-id)]
                (when (contains? @keys* node-name)
                  (swap! keys* assoc-in [node-name :glow] 1)
                  (invalidate! 1400))))
            (play-gesture! [timeline]
              (clear-gesture-keys!)
              (when (and (seq (:events timeline)) (pos? (:duration-ms timeline)))
                (let [now (js/performance.now)]
                  (reset! gesture* {:character (:character timeline)
                                    :source (:source timeline)
                                    :timing (:timing timeline)
                                    :started-at now
                                    :events (:events timeline)
                                    :duration-ms (:duration-ms timeline)})
                  (invalidate! (+ (:duration-ms timeline) 180)))))
            (set-expected! [key-ids]
              (reset! expected* (set (keep node-for-key key-ids)))
              (invalidate! 80))
            (set-mode! [mode]
              (when (not= mode @mode*)
                (let [previous @mode*]
                  (cancel-gesture!)
                  (reset! legend-signature* nil)
                  (when (= previous :landing)
                    (restore-landing-model!))
                  (when (and (= previous :layers) (not= mode :layers))
                  ;; Start the preset transition from the manually orbited
                  ;; camera so leaving Explore never produces a jump.
                    (.copy base-camera-position (.-position camera))
                    (reset! orbit* {:yaw 0 :pitch 0}))
                  (reset! mode* mode)
                  ((:set-mode! drafting-floor) mode)
                  (when (not= mode :layers) (reset! drag* nil))
                  (if (= mode :landing)
                    (apply-landing-pose! @landing-progress*)
                    (begin-camera-transition! (get camera-presets mode (:menu camera-presets))))
                  (begin-light-transition! (presentation-light-level)))))
            (set-layer! [layer legend-scope]
              (reset! active-layer* layer)
              (reset! legend-scope*
                      (if (= legend-scope :all)
                        :all
                        (set (keep node-for-key legend-scope))))
              (refresh-legends!)
              (doseq [[_ {:keys [materials]}] @keys*
                      material materials]
                (.setHex (.-emissive material)
                         (hex-color (get @layer-colors* layer "#8b83ff"))))
              (invalidate! 80))
            (set-theme! [resolved-theme]
              (when ((:set-theme! drafting-floor) resolved-theme)
                (invalidate! 120))
              (let [colors (merge layout-theme/default-layer-colors
                                  (:layer-colors resolved-theme))]
                (when (not= colors @layer-colors*)
                  (reset! layer-colors* colors)
                  (reset! legend-signature* nil)
                  (refresh-legends!)
                  (doseq [[_ {:keys [materials]}] @keys*
                          material materials]
                    (.setHex (.-emissive material)
                             (hex-color (get colors @active-layer* "#8b83ff"))))
                  (invalidate! 120))))
            (set-display-status! [status]
              (reset! display-status* status)
              (when-let [manager @display-manager*]
                (let [result (displays/update! manager status)]
                  (when (pos? (:updated result))
                    (invalidate! 80)))))
            (set-ambient! [value]
              (let [value (clamp 0 1 (or value 0.42))]
                (reset! ambient* value)
                (begin-light-transition! (presentation-light-level))))
            (set-orbit-enabled! [enabled?]
              (reset! orbit-enabled?* (boolean enabled?))
              (when-not enabled? (reset! drag* nil)))
            (orbit-by! [delta-yaw delta-pitch]
              (when @orbit-enabled?*
                (swap! orbit* orbit-rotation delta-yaw delta-pitch)
                (apply-camera-orbit!)
                (invalidate! 100)))
            (reset-camera! []
              (.copy base-camera-position (.-position camera))
              (reset! orbit* {:yaw 0 :pitch 0})
              (begin-camera-transition! @camera-target*))]
      {:mount! mount!
       :dispose! dispose!
       :load! load!
       :unload! unload!
       :set-mode! set-mode!
       :set-landing-progress! #(if (number? %)
                                 (apply-landing-pose! %)
                                 (do (restore-landing-model!) (invalidate! 120)))
       :set-theme! set-theme!
       :set-layer! set-layer!
       :set-display-status! set-display-status!
       :press! press!
       :release! release!
       :pulse! pulse!
       :play-gesture! play-gesture!
       :cancel-gesture! cancel-gesture!
       :set-expected! set-expected!
       :set-ambient! set-ambient!
       :set-orbit-enabled! set-orbit-enabled!
       :orbit-by! orbit-by!
       :set-glow! #(do (reset! glow* %) (invalidate! 80))
       :set-reduced-motion! #(let [value (boolean %)]
                               (when (not= value @reduced?*)
                                 (reset! reduced?* value)
                                 (when @gesture* (cancel-gesture!))
                                 (invalidate! 80)))
       :reset-camera! reset-camera!})))
