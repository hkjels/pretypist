(ns pretypist.scene.floor
  (:require ["three" :as THREE]))

(def strengths {:landing 0.85 :menu 1 :layers 1.05 :practice 0.55})

(defn create! []
  (let [geometry (THREE/PlaneGeometry. 0.68 0.48)
        uniforms #js {:ink #js {:value (THREE/Color. "#f1eee7")}
                      :accent #js {:value (THREE/Color. "#8b83ff")}
                      :viewport #js {:value (THREE/Vector2. 1 1)}
                      :strength #js {:value 1}}
        material (THREE/ShaderMaterial.
                  #js {:uniforms uniforms
                       :transparent true
                       :depthWrite false
                       :vertexShader "varying vec2 floorPosition;
                         void main() {
                           floorPosition = position.xy;
                           gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
                         }"
                       :fragmentShader "uniform vec3 ink;
                         uniform vec3 accent;
                         uniform float strength;
                         uniform vec2 viewport;
                         varying vec2 floorPosition;
                         float grid(vec2 coordinate) {
                           vec2 width = max(fwidth(coordinate), vec2(0.0001));
                           vec2 distance = abs(fract(coordinate - 0.5) - 0.5) / width;
                           return 1.0 - min(min(distance.x, distance.y), 1.0);
                         }
                         void main() {
                           float minor = grid(floorPosition / 0.012);
                           float major = grid(floorPosition / 0.048);
                           float edge = length(floorPosition / vec2(0.32, 0.22));
                           float fade = 1.0 - smoothstep(0.3, 1.0, edge);
                           vec2 screen = gl_FragCoord.xy / viewport;
                           fade *= smoothstep(0.0, 0.09, screen.x)
                                 * smoothstep(0.0, 0.09, 1.0 - screen.x);
                           float alpha = (minor * 0.065 + major * 0.09) * fade * strength;
                           gl_FragColor = vec4(mix(ink, accent, major * 0.16), alpha);
                           #include <tonemapping_fragment>
                           #include <colorspace_fragment>
                         }"})
        mesh (THREE/Mesh. geometry material)
        mode* (atom :menu)
        theme* (atom nil)
        theme-strength* (atom 1)
        sync! #(set! (.-value (aget uniforms "strength"))
                     (* @theme-strength* (get strengths @mode* 1)))]
    (set! (.-name mesh) "pretypist_drafting_floor")
    (set! (.-x (.-rotation mesh)) (- (/ js/Math.PI 2)))
    ;; Just below the contact-shadow receiver, keeping the existing shadows
    ;; composited over the grid. The plane never intercepts pointer input.
    (set! (.-y (.-position mesh)) -0.0038)
    (set! (.-z (.-position mesh)) 0.008)
    (set! (.-renderOrder mesh) -2)
    {:mesh mesh
     :set-size! (fn [width height]
                  (.set (.-value (aget uniforms "viewport")) width height))
     :set-mode! (fn [mode] (reset! mode* mode) (sync!))
     :set-theme! (fn [theme]
                   (let [signature [(:scheme theme) (select-keys (:tokens theme) [:foreground :accent])]]
                     (when (not= signature @theme*)
                       (reset! theme* signature)
                       (.set (.-value (aget uniforms "ink")) (get-in theme [:tokens :foreground] "#f1eee7"))
                       (.set (.-value (aget uniforms "accent")) (get-in theme [:tokens :accent] "#8b83ff"))
                       (reset! theme-strength* (if (= :light (:scheme theme)) 0.68 1))
                       (sync!)
                       true)))
     :dispose! (fn [] (.dispose geometry) (.dispose material))}))
