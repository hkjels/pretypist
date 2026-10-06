(ns pretypist.support.image-bitmap
  "Node-only image boundary for GLTFLoader tests. Decode the generated RGBA PNG
  pixels without a DOM; real browser image decoding/GPU upload is covered by
  surface-model.spec.mjs. This is not shipped or used by package validation."
  (:require ["zlib" :refer [inflateSync]]))

(defn- decode-generated-png [buffer]
  (let [view (js/DataView. buffer)
        bytes (js/Uint8Array. buffer)
        width (.getUint32 view 16 false)
        height (.getUint32 view 20 false)]
    (when-not (and (= 0x89504e47 (.getUint32 view 0 false))
                   (= 8 (aget bytes 24)) (= 6 (aget bytes 25))
                   (zero? (aget bytes 28))
                   (<= 1 width 256) (<= 1 height 256))
      (throw (js/Error. "Node image adapter only accepts small generated RGBA PNGs; use browser tests for other images.")))
    (let [chunks (loop [offset 8 result []]
                   (if (>= offset (.-byteLength buffer))
                     result
                     (let [length (.getUint32 view offset false)
                           type (.getUint32 view (+ offset 4) false)]
                       (recur (+ offset length 12)
                              (cond-> result
                                (= type 0x49444154)
                                (conj (js/Buffer.from (.slice buffer (+ offset 8) (+ offset 8 length)))))))))
          decoded (inflateSync (js/Buffer.concat (to-array chunks)))
          stride (* width 4)
          pixels (js/Uint8ClampedArray. (* stride height))]
      (when-not (= (.-length decoded) (* (inc stride) height))
        (throw (js/Error. "Invalid generated PNG pixel length.")))
      (doseq [y (range height)]
        (let [offset (* y (inc stride))]
          (when-not (zero? (aget decoded offset))
            (throw (js/Error. "Unexpected PNG filter in generated texture.")))
          (.set pixels (.subarray decoded (inc offset) (+ offset 1 stride)) (* y stride))))
      #js {:width width :height height :data pixels :close (fn [])})))

(defn install! []
  (set! (.-self js/globalThis) js/globalThis)
  (set! (.-createImageBitmap js/globalThis)
        (fn [blob _options]
          (.then (.arrayBuffer blob) decode-generated-png))))
