(ns pretypist.display.status
  (:require [pretypist.landing :as landing]
            [pretypist.typing.metrics :as metrics]))

(def wpm-sample-count 10)

(defn clamp [minimum maximum value]
  (max minimum (min maximum value)))

(defn empty-wpm-history []
  (vec (repeat wpm-sample-count 0)))

(defn append-wpm
  "Adds one bounded whole-number WPM sample and retains the stock nice!view's
  ten-sample history."
  [history value]
  (let [sample (clamp 0 255 (js/Math.round (or value 0)))]
    (->> (conj (vec (or history [])) sample)
         (take-last wpm-sample-count)
         vec)))

(defn sample-session-wpm [history session timestamp]
  (if (= :running (:status session))
    (append-wpm history (:wpm (metrics/summarize session timestamp)))
    (vec (or history []))))

(defn demonstrated-layer [state]
  (let [layout (get-in state [:layout :active])]
    (cond
      (landing/active? (:landing state))
      (landing/active-layer (:landing state))

      (and (= :layers (get-in state [:settings :mode]))
           (not (get-in state [:layer-trainer :transfer?])))
      (get-in state [:layer-trainer :active-layer] (:layout/default-layer layout))

      :else (:layout/default-layer layout))))

(defn presentation
  "Projects immutable trainer state into the normalized display value map.

  WPM and the demonstrated layer are application-derived. The package defaults
  remain the explicit simulated source for battery, endpoint, Bluetooth, and
  split-link values until a permissioned keyboard telemetry provider exists."
  [state]
  (let [defaults (get-in state [:layout :active :display/default-status])
        history (vec (get-in state [:display :wpm-history]))
        live-wpm? (seq history)
        layer (demonstrated-layer state)]
    (when defaults
      (cond-> (assoc defaults
                     :layer layer
                     :sources {:batteries :simulated
                               :endpoint :simulated
                               :bluetooth-profiles :simulated
                               :split-connection :simulated
                               :peripheral-art :package
                               :layer :pretypist
                               :wpm (if live-wpm? :pretypist :simulated)})
        live-wpm?
        (assoc :wpm {:current (or (last history) 0)
                     :history history})))))
