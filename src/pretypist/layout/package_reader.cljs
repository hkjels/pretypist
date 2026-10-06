(ns pretypist.layout.package-reader
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [pretypist.layout.schema :as schema]
            [pretypist.layout.validate :as validate]
            [pretypist.scene.model :as scene-model]
            ["three/examples/jsm/loaders/GLTFLoader.js" :refer [GLTFLoader]]
            ["jszip" :as JSZip]))

(def mime-type "application/vnd.pretypist+zip")

(def limits
  {:layout-bytes (* 1024 1024)
   :model-bytes (* 30 1024 1024)
   :archive-uncompressed-bytes (* 60 1024 1024)
   :archive-compressed-bytes (* 40 1024 1024)})

(def allowed-root-files #{"layout.edn" "model.glb" "preview.webp" "README.md"})
(def required-root-files #{"layout.edn" "model.glb"})

(defn- read-u16 [view offset] (.getUint16 view offset true))
(defn- read-u32 [view offset] (.getUint32 view offset true))

(defn- end-of-central-directory-offset [view length]
  (let [minimum (max 0 (- length 65557))]
    (loop [offset (- length 22)]
      (cond
        (< offset minimum) nil
        (and (= 0x06054b50 (read-u32 view offset))
             (= length (+ offset 22 (read-u16 view (+ offset 20))))) offset
        :else (recur (dec offset))))))

(defn central-directory-entries
  "Reads ZIP metadata from the EOCD and central directory without inflating
  files. This detects duplicate names before JSZip's map can collapse them."
  [array-buffer]
  (let [view (js/DataView. array-buffer)
        bytes (js/Uint8Array. array-buffer)
        decoder (js/TextDecoder. "utf-8")
        length (.-byteLength array-buffer)
        eocd (when (>= length 22) (end-of-central-directory-offset view length))]
    (when-not eocd
      (throw (js/Error. "The ZIP end-of-central-directory record is missing or malformed.")))
    (when (or (not= 0 (read-u16 view (+ eocd 4)))
              (not= 0 (read-u16 view (+ eocd 6))))
      (throw (js/Error. "Multi-disk ZIP archives are not supported.")))
    (let [entry-count (read-u16 view (+ eocd 10))
          disk-entry-count (read-u16 view (+ eocd 8))
          central-size (read-u32 view (+ eocd 12))
          central-offset (read-u32 view (+ eocd 16))
          central-end (+ central-offset central-size)]
      (when (or (not= entry-count disk-entry-count)
                (> central-end eocd))
        (throw (js/Error. "The ZIP central directory has inconsistent bounds.")))
      (loop [index 0 offset central-offset entries []]
        (if (= index entry-count)
          (do
            (when (not= offset central-end)
              (throw (js/Error. "The ZIP central-directory size is inconsistent.")))
            entries)
          (do
            (when (or (> (+ offset 46) central-end)
                      (not= 0x02014b50 (read-u32 view offset)))
              (throw (js/Error. "A ZIP central-directory entry is malformed.")))
            (let [compressed (read-u32 view (+ offset 20))
                  uncompressed (read-u32 view (+ offset 24))
                  name-length (read-u16 view (+ offset 28))
                  extra-length (read-u16 view (+ offset 30))
                  comment-length (read-u16 view (+ offset 32))
                  name-start (+ offset 46)
                  next-offset (+ name-start name-length extra-length comment-length)]
              (when (> next-offset central-end)
                (throw (js/Error. "A ZIP central-directory entry is truncated.")))
              (let [name (.decode decoder (.slice bytes name-start (+ name-start name-length)))]
                (recur (inc index) next-offset
                       (conj entries {:name name
                                      :compressed-size compressed
                                      :uncompressed-size uncompressed}))))))))))

(defn archive-metadata-errors [array-buffer]
  (if (> (.-byteLength array-buffer) (:archive-compressed-bytes limits))
    [(validate/validation-error [] :archive-too-large "Compressed package size exceeds 40 MiB.")]
    (try
      (let [entries (central-directory-entries array-buffer)
            counts (frequencies (map :name entries))
            names (set (keys counts))
            total-uncompressed (reduce + 0 (map :uncompressed-size entries))
            total-compressed (reduce + 0 (map :compressed-size entries))
            entry-by-name (into {} (map (juxt :name identity) entries))]
        (vec
         (concat
          (when (empty? entries)
            [(validate/validation-error [] :invalid-zip "The file has no readable ZIP central directory.")])
          (mapcat (fn [required]
                    (case (get counts required 0)
                      0 [(validate/validation-error [required] :missing-file (str required " is required."))]
                      1 []
                      [(validate/validation-error [required] :duplicate-file (str required " must occur exactly once."))]))
                  required-root-files)
          (keep (fn [[name count]]
                  (when (> count 1)
                    (validate/validation-error [name] :duplicate-file (str name " occurs more than once."))))
                counts)
          (keep (fn [name]
                  (cond
                    (not (validate/safe-package-path? name))
                    (validate/validation-error [name] :unsafe-zip-path (str "Unsafe ZIP path: " name))
                    (str/includes? name "/")
                    (validate/validation-error [name] :nested-file "Version 1 package files must be at the ZIP root.")
                    (not (contains? allowed-root-files name))
                    (validate/validation-error [name] :unexpected-file (str "Unexpected package file: " name))
                    :else nil))
                names)
          (when (> total-compressed (:archive-compressed-bytes limits))
            [(validate/validation-error [] :archive-too-large "Compressed package size exceeds 40 MiB.")])
          (when (> total-uncompressed (:archive-uncompressed-bytes limits))
            [(validate/validation-error [] :archive-too-large "Uncompressed package size exceeds 60 MiB.")])
          (when (> (get-in entry-by-name ["layout.edn" :uncompressed-size] 0) (:layout-bytes limits))
            [(validate/validation-error ["layout.edn"] :file-too-large "layout.edn exceeds 1 MiB.")])
          (when (> (get-in entry-by-name ["model.glb" :uncompressed-size] 0) (:model-bytes limits))
            [(validate/validation-error ["model.glb"] :file-too-large "model.glb exceeds 30 MiB.")]))))
      (catch :default error
        [(validate/validation-error [] :invalid-zip
                                    (str "Could not read ZIP metadata: " (.-message error)))]))))

(defn inspect-glb [array-buffer]
  (try
    (let [view (js/DataView. array-buffer)
          length (.-byteLength array-buffer)]
      (cond
        (< length 20)
        {:errors [(validate/validation-error ["model.glb"] :invalid-glb "model.glb is too short.")]}

        (not= 0x46546c67 (read-u32 view 0))
        {:errors [(validate/validation-error ["model.glb"] :invalid-glb "model.glb has an invalid magic header.")]}

        (not= 2 (read-u32 view 4))
        {:errors [(validate/validation-error ["model.glb"] :invalid-glb "Only binary glTF 2.0 is supported.")]}

        (not= length (read-u32 view 8))
        {:errors [(validate/validation-error ["model.glb"] :invalid-glb "model.glb declares an inconsistent byte length.")]}

        (not= 0x4e4f534a (read-u32 view 16))
        {:errors [(validate/validation-error ["model.glb"] :invalid-glb "The first GLB chunk must contain JSON.")]}

        :else
        (let [json-length (read-u32 view 12)
              json-end (+ 20 json-length)]
          (if (> json-end length)
            {:errors [(validate/validation-error ["model.glb"] :invalid-glb "The GLB JSON chunk is truncated.")]}
            (let [bytes (js/Uint8Array. array-buffer 20 json-length)
                  text (-> (.decode (js/TextDecoder. "utf-8") bytes) (str/replace #"[\u0000\s]+$" ""))
                  gltf (js->clj (js/JSON.parse text) :keywordize-keys true)
                  nodes (:nodes gltf)
                  node-names (set (keep :name nodes))
                  node-name-counts (frequencies (keep :name nodes))
                  renderable-indices
                  (letfn [(renderable? [index visiting]
                            (when (and (int? index)
                                       (<= 0 index)
                                       (< index (count nodes))
                                       (not (contains? visiting index)))
                              (let [node (nth nodes index)
                                    visiting (conj visiting index)]
                                (or (some? (:mesh node))
                                    (some #(renderable? % visiting) (:children node))))))]
                    (set (keep-indexed (fn [index _]
                                         (when (renderable? index #{}) index))
                                       nodes)))
                  renderable-node-names
                  (set (keep-indexed (fn [index node]
                                       (when (contains? renderable-indices index)
                                         (:name node)))
                                     nodes))
                  external (concat (keep :uri (:buffers gltf)) (keep :uri (:images gltf)))
                  external-errors (mapv (fn [uri]
                                          (validate/validation-error ["model.glb" :uri]
                                                                     :external-resource
                                                                     (str "External GLB resource is not allowed: " uri)))
                                        external)]
              {:gltf gltf
               :node-names node-names
               :node-name-counts node-name-counts
               :renderable-node-names renderable-node-names
               :errors external-errors})))))
    (catch :default error
      {:errors [(validate/validation-error ["model.glb"] :invalid-glb
                                           (str "Could not parse model.glb: " (.-message error)))]})))

(defn- required-model-node-names [layout]
  (let [model (:model layout)]
    (set (remove nil?
                 (concat [(:root-node model) (:left-half-node model) (:right-half-node model)]
                         (:case-nodes model)
                         (:required-nodes model)
                         (schema/display-node-names layout)
                         (map :node (:keys layout)))))))

(defn model-node-errors [layout node-data]
  (let [legacy-set? (set? node-data)
        node-names (if legacy-set? node-data (:node-names node-data))
        counts (if legacy-set? {} (:node-name-counts node-data))
        renderable (when-not legacy-set? (:renderable-node-names node-data))
        required (required-model-node-names layout)
        key-nodes (set (map :node (:keys layout)))]
    (vec
     (concat
      (->> required
           (remove node-names)
           sort
           (map #(validate/validation-error [:model :nodes %] :missing-glb-node
                                            (str "model.glb is missing required node " % "."))))
      (->> required
           (filter #(> (get counts % 0) 1))
           sort
           (map #(validate/validation-error ["model.glb" :nodes %] :duplicate-glb-node
                                            (str "model.glb contains more than one node named " % "."))))
      (when renderable
        (->> key-nodes
             (remove renderable)
             sort
             (map #(validate/validation-error [:model :nodes %] :non-renderable-key-node
                                              (str "Mapped key node " % " has no renderable mesh.")))))))))

(defn validate-glb-runtime [array-buffer]
  (js/Promise.
   (fn [resolve _reject]
     (.parse (GLTFLoader.) array-buffer ""
             (fn [gltf]
               (try
                 (scene-model/dispose-object! (.-scene gltf))
                 (resolve [])
                 (catch :default error
                   (resolve [(validate/validation-error ["model.glb"] :invalid-glb
                                                        (str "Could not dispose parsed model.glb: "
                                                             (.-message error)))]))))
             (fn [error]
               (resolve [(validate/validation-error ["model.glb"] :invalid-glb
                                                    (str "GLTFLoader rejected model.glb: "
                                                         (or (.-message error) error)))]))))))

(defn parse-layout-edn [text]
  (cond
    (validate/unsafe-reader-form? text)
    {:errors [(validate/validation-error ["layout.edn"] :unsafe-reader-form
                                         "Tagged and discard reader forms are not allowed in layout.edn.")]}
    :else
    (try
      {:layout (reader/read-string text)}
      (catch :default error
        {:errors [(validate/validation-error ["layout.edn"] :invalid-edn
                                             (str "layout.edn is not valid EDN: " (.-message error)))]}))))

(defn- validate-package-contents [array-buffer values]
  (let [layout-text (aget values 0)
        model-buffer (aget values 1)
        preview-buffer (aget values 2)
        readme (aget values 3)
        parsed (parse-layout-edn layout-text)
        layout (:layout parsed)
        glb (inspect-glb model-buffer)
        errors (vec (concat (:errors parsed)
                            (when layout (validate/validate-layout layout))
                            (:errors glb)
                            (when (and layout (:node-names glb))
                              (model-node-errors layout glb))))]
    (if (seq errors)
      {:ok? false :errors errors}
      (-> (validate-glb-runtime model-buffer)
          (.then (fn [runtime-errors]
                   (if (seq runtime-errors)
                     {:ok? false :errors runtime-errors}
                     {:ok? true
                      :layout layout
                      :layout-text layout-text
                      :model-buffer model-buffer
                      :preview-buffer preview-buffer
                      :readme readme
                      :zip-buffer array-buffer})))))))

(defn read-package [array-buffer]
  (let [metadata-errors (archive-metadata-errors array-buffer)]
    (if (seq metadata-errors)
      (js/Promise.resolve {:ok? false :errors metadata-errors})
      (-> (.loadAsync JSZip array-buffer #js {:createFolders false})
          (.then (fn [zip]
                   (let [layout-file (.file zip "layout.edn")
                         model-file (.file zip "model.glb")
                         preview-file (.file zip "preview.webp")
                         readme-file (.file zip "README.md")]
                     (-> (js/Promise.all
                          #js [(.async layout-file "string")
                               (.async model-file "arraybuffer")
                               (if preview-file
                                 (.async preview-file "arraybuffer")
                                 (js/Promise.resolve nil))
                               (if readme-file
                                 (.async readme-file "string")
                                 (js/Promise.resolve nil))])
                         (.then #(validate-package-contents array-buffer %))))))
          (.catch (fn [error]
                    {:ok? false
                     :errors [(validate/validation-error
                               [] :invalid-zip
                               (str "Could not read package: " (.-message error)))]}))))))

(defn fetch-package [url]
  (-> (js/fetch url)
      (.then (fn [response]
               (if (.-ok response)
                 (.arrayBuffer response)
                 (throw (js/Error. (str "Package request failed with HTTP " (.-status response)))))))
      (.then read-package)))
