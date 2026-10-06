(ns pretypist.scene.legends
  (:require ["three" :as THREE]))

(defonce texture-cache (atom {}))

(defn- fit-font-size [context text maximum-width preferred]
  (set! (.-font context) (str "600 " preferred "px system-ui, sans-serif"))
  (let [measured (.-width (.measureText context text))]
    (max 15 (min preferred (* preferred (/ maximum-width (max 1 measured)))))))

(defn- legend-texture [legend color]
  (let [cache-key [legend color]]
    (or (get @texture-cache cache-key)
        (let [canvas (js/document.createElement "canvas")
              _ (set! (.-width canvas) 160)
              _ (set! (.-height canvas) 160)
              context (.getContext canvas "2d")
              texture (THREE/CanvasTexture. canvas)
              lines (if (.includes legend " / ")
                      (vec (.split legend " / "))
                      [legend])]
          (set! (.-textAlign context) "center")
          (set! (.-textBaseline context) "middle")
          (set! (.-fillStyle context) color)
          (doseq [[index line] (map-indexed vector lines)]
            (let [preferred (if (= 1 (count lines)) 46 34)
                  size (fit-font-size context line 132 preferred)
                  y (if (= 1 (count lines)) 82 (+ 59 (* index 46)))]
              (set! (.-font context) (str "600 " size "px system-ui, sans-serif"))
              (.fillText context line 80 y)))
          (set! (.-colorSpace texture) (.-SRGBColorSpace THREE))
          (set! (.-needsUpdate texture) true)
          (swap! texture-cache assoc cache-key texture)
          texture))))
(defn legend-plane [texture surface-y]
  (let [material (THREE/MeshBasicMaterial.
                  #js {:map texture
                       :transparent true
                       :depthWrite false
                       :opacity 0.94})
        geometry (THREE/PlaneGeometry. 0.0145 0.0145)
        plane (THREE/Mesh. geometry material)]
    ;; Billboards straddle low-profile caps at oblique views, clipping the
    ;; second line. Keep the whole legend on the physical top surface instead.
    (.rotateX geometry (- (/ js/Math.PI 2)))
    (set! (.-name plane) "pretypist_runtime_legend")
    (.set (.-position plane) 0 (+ (or surface-y 0.0044) 0.00015) 0)
    plane))

(defn make-legend! [node legend color surface-y]
  (let [plane (legend-plane (legend-texture legend color) surface-y)]
    (.add node plane)
    plane))

(defn clear-legends! [key-records]
  (doseq [[_ {:keys [node]}] key-records]
    (doseq [child (array-seq (.-children node))]
      (when (= "pretypist_runtime_legend" (.-name child))
        (.remove node child)
        (when-let [geometry (.-geometry child)] (.dispose geometry))
        (when-let [material (.-material child)] (.dispose material))))))

(defn set-legends!
  "Visibility is either :all or a set of GLB node names."
  [key-records node->legend color visibility]
  (clear-legends! key-records)
  (when (or (= visibility :all) (seq visibility))
    (doseq [[node-name {:keys [node surface-y]}] key-records]
      (when (or (= visibility :all) (contains? visibility node-name))
        (when-let [legend (not-empty (get node->legend node-name))]
          (make-legend! node legend color surface-y))))))

(defn dispose-cache! []
  (doseq [[_ texture] @texture-cache] (.dispose texture))
  (reset! texture-cache {}))
