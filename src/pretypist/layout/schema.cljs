(ns pretypist.layout.schema)

(def supported-format-version 1)
(def maximum-key-count 256)

;; Version 1 keeps displays optional so packages authored before nice!view
;; support remain valid.  The allowlists deliberately name renderers that
;; PRETYPIST knows how to reproduce; package data cannot nominate code.
(def display-kinds #{:nice-view})
(def display-roles #{:central :peripheral})
(def display-templates
  #{:zmk/nice-view-status-v0.3
    :zmk/nice-view-peripheral-v0.3})
(def display-widgets
  #{:battery :endpoint :bluetooth-profiles :wpm :layer
    :split-connection :peripheral-art})
(def display-orientations
  #{:landscape :portrait :landscape-inverted :portrait-inverted})
(def battery-kinds #{:li-po})
(def telemetry-sources #{:simulated})
(def display-transports #{:usb :ble})
(def split-connection-states #{:connected :disconnected})
(def peripheral-art-options #{:mountain :balloon})

(def maximum-display-resolution 4096)
(def maximum-display-surface-mm 1000)
(def maximum-bluetooth-profiles 16)
(def maximum-display-wpm 255)
(def nice-view-wpm-history-length 10)

(def display-template-contracts
  {:zmk/nice-view-status-v0.3
   {:role :central
    :resolution [160 68]
    :orientations #{:portrait}
    :widgets #{:battery :endpoint :bluetooth-profiles :wpm :layer}}

   :zmk/nice-view-peripheral-v0.3
   {:role :peripheral
    :resolution [160 68]
    :orientations #{:portrait}
    :widgets #{:battery :split-connection :peripheral-art}}})

(def action-kinds
  #{:text :key :tap :hold :double-tap :double-hold :one-shot :layer
    :toggle :modifier :disabled :transparent :macro :mouse :media :bluetooth
    :dead-key})

(defn layer-binding [layout layer-id key-id]
  (get-in layout [:layers layer-id :bindings key-id]))

(defn legend-for [layout layer-id key-id]
  (or (:legend (layer-binding layout layer-id key-id)) ""))

(defn legends-for [layout layer-id]
  (into {} (map (fn [{:keys [id]}] [id (legend-for layout layer-id id)]) (:keys layout))))

(defn stroke-for [layout character]
  (get-in layout [:strokes character]))

(defn reverse-stroke-index [layout]
  (:strokes layout))

(defn key-by-code [layout code]
  (some #(when (some #{code} (:observed-codes %)) %) (:keys layout)))

(defn key-by-id [layout key-id]
  (some #(when (= key-id (:id %)) %) (:keys layout)))

(defn display-by-id [layout display-id]
  (get-in layout [:displays display-id]))

(defn display-node-names [layout]
  (set (keep :node (vals (:displays layout)))))

(defn default-display-status [layout]
  (:display/default-status layout))
