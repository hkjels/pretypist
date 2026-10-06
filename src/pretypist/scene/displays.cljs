(ns pretypist.scene.displays
  "Runtime nice!view surfaces.

  Display state is intentionally semantic and slow-moving. The manager redraws
  a 160 x 68 monochrome CanvasTexture only when the values shown by a display
  change; it never participates in the Three.js animation loop."
  (:require [clojure.string :as string]
            [pretypist.scene.generated.nice-view-art :as nice-view-art]
            ["three" :as THREE]))

(def native-resolution [160 68])
(def logical-resolution [68 160])
(def runtime-plane-prefix "pretypist_runtime_display_")

(def display-colors
  ;; The black-polarizer nice!view presents a dark field with pale pixels.
  {:paper [18 22 21]
   :ink [198 202 192]})

(def portrait-canvas-transform
  "Canvas 2D matrix that rotates the 68 x 160 logical display into its
  160 x 68 framebuffer without reflecting it."
  [0 -1 1 0 0 (second native-resolution)])

(def peripheral-art-top
  "The stock 140 px artwork leaves a 20 px status strip on the 160 px panel."
  (- (first native-resolution) nice-view-art/width))

(defn decode-hex-payload
  "Decodes a generated, byte-aligned lowercase hexadecimal payload."
  [hex]
  (when-not (and (string? hex)
                 (even? (count hex))
                 (re-matches #"[0-9a-f]*" hex))
    (throw (js/Error. "Invalid generated nice!view bitmap payload.")))
  (let [byte-count (quot (count hex) 2)
        bytes (js/Uint8Array. byte-count)]
    (dotimes [index byte-count]
      (aset bytes index
            (js/parseInt (.slice hex (* index 2) (+ (* index 2) 2)) 16)))
    bytes))

(def packed-art-bytes
  (reduce-kv (fn [result art-id payload]
               (assoc result art-id (decode-hex-payload payload)))
             {}
             nice-view-art/bitmaps))

(defn packed-bit-set?
  "Returns the indexed 1-bit value at a source-art coordinate.

  LVGL packs the 140 meaningful pixels of each row most-significant bit first
  into an 18-byte row; the last four bits are padding."
  [bytes source-x source-y]
  (let [byte-index (+ (* source-y nice-view-art/row-stride-bytes)
                      (quot source-x 8))
        mask (bit-shift-left 1 (- 7 (mod source-x 8)))]
    (not (zero? (bit-and (aget bytes byte-index) mask)))))

(defn source->logical-art-point
  "Maps the pre-rotated 140 x 68 ZMK bitmap into the portrait display.

  The inverse counter-clockwise rotation places the stock `nice!view`
  lettering at the physical bottom and retains the 20 px top status strip."
  [source-x source-y]
  [source-y (+ peripheral-art-top (- (dec nice-view-art/width) source-x))])

(defn- clamp [minimum maximum value]
  (max minimum (min maximum value)))

(defn- finite-number? [value]
  (and (number? value) (js/Number.isFinite value)))

(defn- whole-number [value fallback]
  (if (finite-number? value)
    (js/Math.round value)
    fallback))

(defn normalize-battery
  "Normalizes browser preview or future hardware battery telemetry.

  A missing percentage remains unavailable instead of being presented as a
  measured zero. USB power is not called charging because ZMK cannot infer the
  battery charger's state from VBUS alone."
  [battery]
  {:percent (when (finite-number? (:percent battery))
              (clamp 0 100 (whole-number (:percent battery) 0)))
   :usb-powered? (true? (:usb-powered? battery))})

(defn normalize-history
  "Returns the most recent ten finite WPM samples, left-padded for the graph."
  [history]
  (let [samples (->> history
                     (filter finite-number?)
                     (map #(clamp 0 255 (whole-number % 0)))
                     (take-last 10)
                     vec)]
    (into (vec (repeat (- 10 (count samples)) 0)) samples)))

(defn- normalize-endpoint [endpoint]
  (let [transport (if (map? endpoint) (:transport endpoint) endpoint)]
    {:transport (if (contains? #{:ble :usb :none :disconnected} transport)
                  transport
                  :none)}))

(defn- profile-by-index [profiles index]
  (or (some #(when (= index (:index %)) %) profiles)
      (nth (vec profiles) index nil)
      {}))

(defn normalize-profiles [profiles]
  (mapv (fn [index]
          (let [profile (profile-by-index (or profiles []) index)]
            {:index index
             :selected? (true? (:selected? profile))
             :bonded? (true? (:bonded? profile))
             :connected? (true? (:connected? profile))}))
        (range 5)))

(defn- layer-label [layer]
  (let [value (cond
                (keyword? layer) (name layer)
                (string? layer) layer
                (nil? layer) "--"
                :else (str layer))]
    (-> value string/upper-case (subs 0 (min 8 (count value))))))

(defn- status-source [status]
  (if (= :live (:source status)) :live :simulated))

(defn central-frame
  "Builds the canonical values rendered by a central nice!view.

  Keeping this pure also makes the redraw cache insensitive to unrelated app
  state."
  [config status]
  (let [battery-id (or (:battery config) :left)
        wpm (if (map? (:wpm status)) (:wpm status) {})]
    {:kind :central
     :source (status-source status)
     :battery (normalize-battery (get-in status [:batteries battery-id]))
     :endpoint (normalize-endpoint (:endpoint status))
     :profiles (normalize-profiles (:bluetooth-profiles status))
     :wpm {:current (clamp 0 255 (whole-number (:current wpm) 0))
           :history (normalize-history (:history wpm))}
     :layer (layer-label (:layer status))}))

(defn peripheral-frame
  "Builds the canonical values rendered by a peripheral nice!view."
  [config status]
  (let [connection (:split-connection status)]
    {:kind :peripheral
     :source (status-source status)
     :battery (normalize-battery
               (get-in status [:batteries (or (:battery config) :right)]))
     :split-connection (if (contains? #{:connected :disconnected :searching}
                                      connection)
                         connection
                         :disconnected)
     :peripheral-art (if (= :balloon (:peripheral-art status))
                       :balloon
                       :mountain)}))

(defn frame-for [config status]
  (case (:role config)
    :peripheral (peripheral-frame config status)
    (central-frame config status)))

(defn graph-heights
  "Scales ten WPM samples into integral pixel heights.

  The 60 WPM floor prevents a quiet first session from exaggerating tiny
  changes; faster histories expand the scale without clipping."
  ([history] (graph-heights history 32))
  ([history height]
   (let [samples (normalize-history history)
         ceiling (max 60 (apply max samples))]
     (mapv #(whole-number (* height (/ % ceiling)) 0) samples))))

(defn- set-font! [context weight size]
  (set! (.-font context)
        (str weight " " size "px ui-monospace, SFMono-Regular, Menlo, monospace")))

(defn- draw-text!
  ([context text x y size]
   (draw-text! context text x y size "600" "left"))
  ([context text x y size weight align]
   (set-font! context weight size)
   (set! (.-textAlign context) align)
   (set! (.-textBaseline context) "middle")
   (.fillText context text x y)))

(defn- draw-battery! [context {:keys [percent usb-powered?]}]
  (set! (.-lineWidth context) 1)
  (.strokeRect context 2.5 3.5 19 9)
  (.fillRect context 21.5 6 2 4)
  (when (some? percent)
    (.fillRect context 4.5 5.5 (* 15 (/ percent 100)) 5))
  (draw-text! context (if (some? percent) (str percent "%") "--%") 27 8 7 "600" "left")
  (when usb-powered?
    (.beginPath context)
    (.moveTo context 61 2)
    (.lineTo context 57 8)
    (.lineTo context 61 8)
    (.lineTo context 58 14)
    (.stroke context)))

(defn- endpoint-label [{:keys [transport]}]
  (case transport
    :ble "BLE"
    :usb "USB"
    "OFF"))

(defn- draw-profiles! [context profiles]
  (doseq [[position {:keys [index selected? bonded? connected?]}]
          (map-indexed vector profiles)
          :let [x (+ 6 (* position 14))]]
    ;; Match the stock nice!view vocabulary: connected is a solid ring,
    ;; bonded/disconnected is dashed, unbonded has no ring, and the selected
    ;; profile always has a filled centre.
    (when (or connected? bonded?)
      (.setLineDash context (if connected? #js [] #js [2 2]))
      (.beginPath context)
      (.arc context x 35 4.5 0 (* 2 js/Math.PI))
      (.stroke context)
      (.setLineDash context #js []))
    (when selected?
      (.beginPath context)
      (.arc context x 35 2 0 (* 2 js/Math.PI))
      (.fill context))
    (draw-text! context (str (inc index)) x 45 5 "600" "center")))

(defn- draw-wpm-graph! [context history]
  (let [heights (graph-heights history 31)]
    (.beginPath context)
    (doseq [[index height] (map-indexed vector heights)
            :let [x (+ 4 (* index 6.5))
                  y (- 126 height)]]
      (if (zero? index)
        (.moveTo context x y)
        (.lineTo context x y)))
    (.stroke context)
    (doseq [[index height] (map-indexed vector heights)
            :when (pos? height)]
      (.fillRect context (+ 3 (* index 6.5)) (- 126 height) 2 2))))

(defn- draw-preview-mark!
  ([context source]
   (draw-preview-mark! context source 155))
  ([context source y]
   (when (= :simulated source)
     (draw-text! context "SIM" 66 y (if (< y 20) 4 5) "600" "right"))))

(defn- draw-central! [context {:keys [source battery endpoint profiles wpm layer]}]
  (draw-battery! context battery)
  (draw-text! context (endpoint-label endpoint) 66 20 7 "600" "right")
  (draw-profiles! context profiles)
  (draw-text! context (str (:current wpm)) 34 67 23 "700" "center")
  (draw-text! context "WPM" 34 83 6 "600" "center")
  (draw-wpm-graph! context (:history wpm))
  (.fillRect context 2 135 64 1)
  (draw-text! context layer 2 146 10 "700" "left")
  (draw-preview-mark! context source))

(defn- draw-link! [context connection]
  (set! (.-lineWidth context) 1)
  (case connection
    (:connected :searching)
    (do
      (.setLineDash context (if (= :searching connection) #js [1 1] #js []))
      (doseq [radius [4 8]]
        (.beginPath context)
        (.arc context 57 14 radius js/Math.PI (* 2 js/Math.PI))
        (.stroke context))
      (.setLineDash context #js [])
      (.beginPath context)
      (.arc context 57 14 1.25 0 (* 2 js/Math.PI))
      (.fill context))
    (do
      (.beginPath context)
      (.moveTo context 52 6)
      (.lineTo context 63 15)
      (.moveTo context 63 6)
      (.lineTo context 52 15)
      (.stroke context))))

(defn- put-rgba! [data offset [red green blue]]
  (aset data offset red)
  (aset data (+ offset 1) green)
  (aset data (+ offset 2) blue)
  (aset data (+ offset 3) 255))

(defn- make-stock-art-canvas [art-id]
  (let [source-width nice-view-art/width
        source-height nice-view-art/height
        portrait-width source-height
        portrait-height source-width
        bytes (get packed-art-bytes art-id)
        canvas (js/document.createElement "canvas")
        _ (set! (.-width canvas) portrait-width)
        _ (set! (.-height canvas) portrait-height)
        context (.getContext canvas "2d" #js {:alpha false})
        image (.createImageData context portrait-width portrait-height)
        data (.-data image)]
    (doseq [source-y (range source-height)
            source-x (range source-width)]
      (let [logical-x source-y
            local-y (- (dec source-width) source-x)
            offset (* 4 (+ logical-x (* local-y portrait-width)))
            ;; In the inverted ZMK palette, index 1 is the dark field and
            ;; index 0 is the pale reflective pixel.
            color (if (packed-bit-set? bytes source-x source-y)
                    (:paper display-colors)
                    (:ink display-colors))]
        (put-rgba! data offset color)))
    (.putImageData context image 0 0)
    canvas))

(defn- draw-stock-art! [context art-id]
  (let [resolved-id (if (contains? packed-art-bytes art-id) art-id :mountain)]
    (set! (.-imageSmoothingEnabled context) false)
    (.drawImage context (make-stock-art-canvas resolved-id) 0 peripheral-art-top)))

(defn- draw-peripheral! [context {:keys [source battery split-connection peripheral-art]}]
  (draw-battery! context battery)
  (draw-link! context split-connection)
  ;; ZMK creates the status canvas first and the 140 px artwork second. The
  ;; opaque art therefore leaves only the physical 20 px status strip visible.
  (draw-stock-art! context peripheral-art)
  (draw-preview-mark! context source 17))

(defn- color-components [color]
  (clj->js color))

(defn- quantize-monochrome! [context width height]
  (let [image (.getImageData context 0 0 width height)
        data (.-data image)
        paper (color-components (:paper display-colors))
        ink (color-components (:ink display-colors))]
    (loop [offset 0]
      (when (< offset (.-length data))
        (let [luminance (+ (* 0.2126 (aget data offset))
                           (* 0.7152 (aget data (+ offset 1)))
                           (* 0.0722 (aget data (+ offset 2))))
              ;; Drawing happens with the final dark-paper/light-ink palette,
              ;; so preserve the dark pixels as paper and the light pixels as
              ;; ink while snapping antialiased edges to two colours.
              color (if (< luminance 112) paper ink)]
          (aset data offset (aget color 0))
          (aset data (+ offset 1) (aget color 1))
          (aset data (+ offset 2) (aget color 2))
          (aset data (+ offset 3) 255)
          (recur (+ offset 4)))))
    (.putImageData context image 0 0)))

(defn draw-frame!
  "Draws a canonical frame on the native 160 x 68 nice!view canvas."
  [canvas context frame]
  (let [[native-width native-height] native-resolution
        [logical-width logical-height] logical-resolution
        [paper-r paper-g paper-b] (:paper display-colors)
        [ink-r ink-g ink-b] (:ink display-colors)
        [transform-a transform-b transform-c transform-d transform-e transform-f]
        portrait-canvas-transform]
    (.save context)
    (.setTransform context 1 0 0 1 0 0)
    (set! (.-fillStyle context) (str "rgb(" paper-r "," paper-g "," paper-b ")"))
    (.fillRect context 0 0 native-width native-height)
    ;; The physical panel is portrait. Draw in 68 x 160 logical coordinates
    ;; while retaining the panel's native 160 x 68 framebuffer dimensions.
    ;; The generated nice!view meshes place the USB end toward local -Z.
    ;; Rotate the portrait framebuffer so its logical top follows that end.
    ;; This positive-determinant matrix is a true rotation: the previous
    ;; transpose fixed top/bottom but reflected the display horizontally.
    (.setTransform context
                   transform-a transform-b transform-c
                   transform-d transform-e transform-f)
    (set! (.-imageSmoothingEnabled context) false)
    (set! (.-fillStyle context) (str "rgb(" ink-r "," ink-g "," ink-b ")"))
    (set! (.-strokeStyle context) (.-fillStyle context))
    (set! (.-lineCap context) "square")
    (case (:kind frame)
      :peripheral (draw-peripheral! context frame)
      (draw-central! context frame))
    (.restore context)
    (quantize-monochrome! context native-width native-height)
    canvas))

(defn- millimetres->metres [value]
  (* 0.001 value))

(defn surface-size
  "Returns the configured active surface dimensions in metres."
  [config]
  (let [[width depth] (or (:surface-size-mm config) [11.3 26.5])]
    [(millimetres->metres width) (millimetres->metres depth)]))

(defn surface-x-compensation
  "Returns the local X scale needed to keep a display readable when its GLB
  node inherits a mirrored world transform."
  [screen]
  (.updateWorldMatrix screen true false)
  (if (neg? (.determinant (.-matrixWorld screen))) -1 1))

(defn- local-surface-y [screen]
  (if-let [geometry (.-geometry screen)]
    (do
      (when-not (.-boundingBox geometry)
        (.computeBoundingBox geometry))
      (+ (or (some-> geometry .-boundingBox .-max .-y) 0.00032)
         0.000025))
    0.000345))

(defn- dispose-runtime-plane! [plane]
  (when-let [parent (.-parent plane)]
    (.remove parent plane))
  (when-let [material (.-material plane)]
    (when-let [texture (.-map material)]
      (.dispose texture))
    (.dispose material))
  (when-let [geometry (.-geometry plane)]
    (.dispose geometry)))

(defn- remove-stale-planes! [screen]
  (doseq [child (array-seq (.-children screen))]
    (when (string/starts-with? (or (.-name child) "") runtime-plane-prefix)
      (dispose-runtime-plane! child))))

(defn- make-record! [root display-id config status]
  (when-let [screen (.getObjectByName root (:node config))]
    (remove-stale-planes! screen)
    (let [[width depth] (surface-size config)
          canvas (js/document.createElement "canvas")
          _ (set! (.-width canvas) (first native-resolution))
          _ (set! (.-height canvas) (second native-resolution))
          context (.getContext canvas "2d" #js {:alpha false})
          frame (frame-for config status)
          _ (draw-frame! canvas context frame)
          texture (THREE/CanvasTexture. canvas)
          _ (set! (.-colorSpace texture) (.-SRGBColorSpace THREE))
          _ (set! (.-magFilter texture) (.-NearestFilter THREE))
          _ (set! (.-minFilter texture) (.-NearestFilter THREE))
          _ (set! (.-generateMipmaps texture) false)
          _ (set! (.-needsUpdate texture) true)
          ;; Canvas is native landscape but contains portrait-oriented pixels.
          ;; Rotating the geometry, rather than stretching the map, preserves
          ;; square pixels on the 11.3 x 26.5 mm active area.
          geometry (THREE/PlaneGeometry. depth width)
          _ (.rotateX geometry (- (/ js/Math.PI 2)))
          _ (.rotateY geometry (- (/ js/Math.PI 2)))
          material (THREE/MeshPhysicalMaterial.
                    #js {:map texture
                         :color 0xffffff
                         :roughness 0.38
                         :metalness 0.025
                         :clearcoat 0.42
                         :clearcoatRoughness 0.24
                         :reflectivity 0.32
                         :side (.-DoubleSide THREE)})
          plane (THREE/Mesh. geometry material)]
      (set! (.-name plane) (str runtime-plane-prefix (name display-id)))
      (.set (.-position plane) 0 (local-surface-y screen) 0)
      ;; The generated right half mirrors the complete physical assembly.
      ;; Counter-reflect the centered runtime surface so its pixels remain
      ;; readable, while preserving that mirrored hardware geometry.
      (set! (.-x (.-scale plane)) (surface-x-compensation screen))
      (set! (.-castShadow plane) false)
      (set! (.-receiveShadow plane) false)
      (set! (.-renderOrder plane) 2)
      (.add screen plane)
      {:id display-id
       :config config
       :screen screen
       :plane plane
       :canvas canvas
       :context context
       :texture texture
       :frame (atom frame)})))

(defn install!
  "Installs declarative nice!view surfaces and returns their small controller.

  `display-configs` is the layout's `:displays` map. `update!` returns redraw
  counts, which is useful for diagnostics without exposing Three.js objects to
  application state. Missing optional model nodes are reported and skipped so
  the rest of the keyboard remains usable."
  [root display-configs initial-status]
  (let [configs (or display-configs {})
        records (into {}
                      (keep (fn [[display-id config]]
                              (when (and (= :nice-view (:kind config))
                                         (string? (:node config)))
                                (when-let [record (make-record! root display-id config initial-status)]
                                  [display-id record]))))
                      configs)
        missing (->> configs
                     (keep (fn [[display-id config]]
                             (when (and (= :nice-view (:kind config))
                                        (string? (:node config))
                                        (nil? (get records display-id)))
                               (:node config))))
                     vec)
        disposed? (atom false)
        update! (fn [status]
                  (if @disposed?
                    {:updated 0 :unchanged 0 :disposed? true}
                    (reduce (fn [result [_ {:keys [config context canvas texture frame]}]]
                              (let [next-frame (frame-for config status)]
                                (if (= @frame next-frame)
                                  (update result :unchanged inc)
                                  (do
                                    (draw-frame! canvas context next-frame)
                                    (set! (.-needsUpdate texture) true)
                                    (reset! frame next-frame)
                                    (update result :updated inc)))))
                            {:updated 0 :unchanged 0 :disposed? false}
                            records)))
        dispose! (fn []
                   (when (compare-and-set! disposed? false true)
                     (doseq [[_ {:keys [plane]}] records]
                       (dispose-runtime-plane! plane))))]
    {:update! update!
     :dispose! dispose!
     :records records
     :missing-nodes missing}))

(defn update!
  "Updates an installed manager and returns its redraw counts."
  [manager status]
  ((:update! manager) status))

(defn dispose!
  "Disposes every plane, CanvasTexture, material, and geometry in a manager."
  [manager]
  ((:dispose! manager)))
