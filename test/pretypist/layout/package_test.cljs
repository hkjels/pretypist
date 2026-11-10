(ns pretypist.layout.package-test
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [clojure.string :as str]
            [pretypist.layout.package-reader :as package]
            ["jszip" :as JSZip]
            ["fs" :as fs]))

(defn node-buffer->array-buffer [buffer]
  (.slice (.-buffer buffer) (.-byteOffset buffer) (+ (.-byteOffset buffer) (.-byteLength buffer))))

(defn make-glb [node-names & [external-uri]]
  (let [json (clj->js (cond-> {:asset {:version "2.0"}
                               :scene 0
                               :scenes [{:nodes [0]}]
                               :nodes (mapv (fn [name] {:name name}) node-names)
                               :buffers [{:byteLength 0}]}
                        external-uri (assoc :buffers [{:byteLength 0 :uri external-uri}])) )
        json-text (js/JSON.stringify json)
        encoded (.encode (js/TextEncoder.) json-text)
        padded-length (* 4 (js/Math.ceil (/ (.-length encoded) 4)))
        buffer (js/ArrayBuffer. (+ 20 padded-length))
        view (js/DataView. buffer)
        bytes (js/Uint8Array. buffer)]
    (.setUint32 view 0 0x46546c67 true)
    (.setUint32 view 4 2 true)
    (.setUint32 view 8 (+ 20 padded-length) true)
    (.setUint32 view 12 padded-length true)
    (.setUint32 view 16 0x4e4f534a true)
    (.fill bytes 0x20 20 (+ 20 padded-length))
    (.set bytes encoded 20)
    buffer))

(defn zip-buffer [entries]
  (let [zip (JSZip.)]
    (doseq [[name content] entries] (.file zip name content))
    (.generateAsync zip #js {:type "arraybuffer" :compression "STORE"})))

(deftest built-in-valid-package-import-test
  (async done
    (let [buffer (node-buffer->array-buffer (.readFileSync fs "resources/public/layouts/modified-engram.pretypist"))]
      (-> (package/read-package buffer)
          (.then (fn [result]
                   (is (:ok? result) (pr-str (:errors result)))
      (is (= "modified-engram" (get-in result [:layout :package/id])))
      (is (= 14 (get-in result [:layout :package/revision])))
      (is (= 1 (get-in result [:layout :theme :version])))
      (is (= "#5b50c8" (get-in result [:layout :theme :schemes :light :accent])))
      (is (= 36 (count (get-in result [:layout :keys]))))
                   (is (= :zmk/nice-view-status-v0.3
                          (get-in result [:layout :displays :left :template])))
                   (is (= "display_screen_right"
                          (get-in result [:layout :displays :right :node])))
                   (is (= :simulated
                          (get-in result [:layout :display/default-status :source])))
                   (is (= 82
                          (get-in result [:layout :display/default-status
                                          :batteries :left :percent])))
                   (is (= 10
                          (count (get-in result [:layout :display/default-status
                                                :wpm :history]))))
                   (is (= 38 (->> (get-in result [:layout :keys])
                                  (some #(when (= :k30 (:id %)) %))
                                  :firmware-position)))
                   (is (= [:one-shot :modifier :left-shift]
                          (get-in result [:layout :layers :base :bindings :k30
                                          :actions :tap])))
                   (is (nil? (get-in result [:layout :layers :base :bindings :k30
                                             :firmware-action])))
                   (is (= [:one-shot :layer :sym]
                          (get-in result [:layout :layers :base :bindings :k32
                                          :actions :tap])))
                   (is (= [:transparent]
                          (get-in result [:layout :layers :mouse :bindings :k30
                                          :firmware-action])))
                   (done)))
          (.catch (fn [error] (is false (.-message error)) (done)))))))

(deftest missing-required-package-file-test
  (async done
    (-> (zip-buffer [["layout.edn" "{}"]])
        (.then package/read-package)
        (.then (fn [result]
                 (is (not (:ok? result)))
                 (is (some #(= :missing-file (:code %)) (:errors result)))
                 (done))))))

(deftest invalid-edn-package-test
  (async done
    (-> (zip-buffer [["layout.edn" "{:broken"]
                     ["model.glb" (make-glb ["keyboard"])]] )
        (.then package/read-package)
        (.then (fn [result]
                 (is (some #(= :invalid-edn (:code %)) (:errors result)))
                 (done))))))

(deftest unsafe-zip-path-test
  (async done
    (-> (zip-buffer [["layout.edn" "{}"]
                     ["model.glb" (make-glb ["keyboard"])]
                     ["../escape.txt" "unsafe"]])
        (.then (fn [buffer]
                 (let [errors (package/archive-metadata-errors buffer)]
                   (is (some #(= :unsafe-zip-path (:code %)) errors))
                   (done)))))))

(deftest missing-glb-node-test
  (let [errors (package/model-node-errors
                {:keys [{:node "key_00"}]}
                #{"keyboard" "half_left" "half_right" "case_left" "case_right"})]
    (is (= :missing-glb-node (:code (first errors))))
    (is (str/includes? (:message (first errors)) "key_00"))))

(deftest mapped-display-node-is-required-even-without-required-nodes-test
  (let [errors (package/model-node-errors
                {:model {:root-node "keyboard"}
                 :keys []
                 :displays {:left {:node "display_screen_left"}}}
                #{"keyboard"})
        missing (first (filter #(= :missing-glb-node (:code %)) errors))]
    (is (= [:model :nodes "display_screen_left"] (:path missing)))
    (is (str/includes? (:message missing) "display_screen_left"))))

(deftest duplicate-glb-node-test
  (let [inspection (package/inspect-glb (make-glb ["keyboard" "keyboard"]))
        errors (package/model-node-errors
                {:model {:root-node "keyboard"}
                 :keys []}
                inspection)
        duplicate (first (filter #(= :duplicate-glb-node (:code %)) errors))]
    (is (empty? (:errors inspection)))
    (is (= 2 (get-in inspection [:node-name-counts "keyboard"])))
    (is (= ["model.glb" :nodes "keyboard"] (:path duplicate)))
    (is (str/includes? (:message duplicate) "more than one"))))

(deftest external-glb-resource-rejection-test
  (let [result (package/inspect-glb (make-glb ["keyboard"] "https://example.test/model.bin"))]
    (is (= :external-resource (get-in result [:errors 0 :code])))))
