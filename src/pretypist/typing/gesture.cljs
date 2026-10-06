(ns pretypist.typing.gesture
  "Pure conversion of declarative layout strokes into illustrative playback.

  Timings are intentionally reviewed presentation defaults. They describe an
  expected movement sequence and must never be presented as firmware or raw
  hardware telemetry."
  (:require [pretypist.layout.schema :as schema]))

(def timing
  {:step-gap-ms 140
   :tap-down-ms 78
   :double-gap-ms 72
   :double-hold-ms 250
   :settle-ms 150})

(def supported-operations
  #{:tap :hold :release :double-tap :double-hold :one-shot :modifier
    :layer :toggle :disabled :transparent})

(defn stroke-layers
  "Resolves expected layer entries from base-key behavior."
  [layout stroke]
  (let [layer-ids (set (keys (:layers layout)))]
    (->> stroke
         (keep (fn [[operation key-id]]
                 (let [action-slot (case operation
                                     :hold :hold
                                     :one-shot :tap
                                     :double-hold :double-hold
                                     :double-tap :double-tap
                                     :tap :tap
                                     nil)
                       action (when action-slot
                                (get-in layout [:layers :base :bindings key-id
                                                :actions action-slot]))]
                   (some layer-ids action))))
         distinct
         vec)))

(defn- step-span [operation]
  (case operation
    :double-tap (* 2 (:step-gap-ms timing))
    :double-hold (+ (* 2 (:step-gap-ms timing)) (:double-hold-ms timing))
    (:step-gap-ms timing)))

(defn- step-events [at-ms operation key-id physical-key-ids]
  (let [{:keys [tap-down-ms double-gap-ms double-hold-ms]} timing
        physical? (contains? physical-key-ids key-id)
        event (fn [offset kind]
                {:at-ms (+ at-ms offset)
                 :kind kind
                 :key-id key-id
                 :source-kind operation})]
    (if-not physical?
      []
      (case operation
        (:tap :one-shot :modifier :layer :toggle)
        [(event 0 :press) (event tap-down-ms :release)]

        :hold
        [(event 0 :press)]

        :release
        [(event 0 :release)]

        :double-tap
        [(event 0 :press)
         (event tap-down-ms :release)
         (event (+ tap-down-ms double-gap-ms) :press)
         (event (+ tap-down-ms double-gap-ms tap-down-ms) :release)]

        :double-hold
        [(event 0 :press)
         (event tap-down-ms :release)
         (event (+ tap-down-ms double-gap-ms) :press)
         (event (+ tap-down-ms double-gap-ms double-hold-ms) :release)]

        []))))

(defn normalize-stroke
  "Creates semantic steps plus low-level press/release events for the scene."
  [character stroke physical-key-ids]
  (let [{:keys [steps events cursor held]}
        (reduce
         (fn [{:keys [cursor held] :as result} [index [operation key-id :as source]]]
           (let [operation (if (contains? supported-operations operation)
                             operation
                             :unsupported)
                 step {:index index
                       :at-ms cursor
                       :kind operation
                       :key-id key-id
                       :source source}
                 held (case operation
                        :hold (if (contains? physical-key-ids key-id)
                                (conj held key-id)
                                held)
                        :release (disj held key-id)
                        held)]
             (-> result
                 (update :steps conj step)
                 (update :events into (step-events cursor operation key-id physical-key-ids))
                 (assoc :held held
                        :cursor (+ cursor (step-span operation))))))
         {:steps [] :events [] :cursor 0 :held #{}}
         (map-indexed vector (or stroke [])))
        closing-events (mapv (fn [key-id]
                               {:at-ms cursor :kind :release :key-id key-id
                                :source-kind :implicit-release})
                             (sort held))
        events (->> (concat events closing-events)
                    (map-indexed #(assoc %2 :sequence %1))
                    (sort-by (juxt :at-ms :sequence))
                    vec)
        duration-ms (+ (or (:at-ms (last events)) 0)
                       (:settle-ms timing))]
    {:character character
     :source :inferred
     :timing :illustrative
     :steps steps
     :events events
     :duration-ms duration-ms}))

(defn timeline-for [layout character]
  (let [stroke (schema/stroke-for layout character)]
    (when (seq stroke)
      (assoc (normalize-stroke character stroke (set (map :id (:keys layout))))
             :layers (stroke-layers layout stroke)))))

(defn complex-timeline? [timeline]
  (or (> (count (:steps timeline)) 1)
      (some #(contains? #{:double-tap :double-hold :one-shot :hold :layer :modifier :toggle}
                        (:kind %))
            (:steps timeline))))
