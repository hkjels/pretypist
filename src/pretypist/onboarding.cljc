(ns pretypist.onboarding)

(def version 1)
(def calibration-steps [:baseline :rhythm :layers])
(def required-gestures 3)

(defn initial-state []
  {:version version
   :status :active
   :step :setup
   :phase :intro
   :gesture-index 0
   :records []
   :started-at nil
   :completed-at nil
   :interrupted? false})

(defn completed-state []
  (assoc (initial-state)
         :status :complete
         :step :complete
         :phase :complete))

(defn active? [onboarding]
  (= :active (:status onboarding)))

(defn progress-index [onboarding]
  (case (:step onboarding)
    :baseline 1
    :rhythm 2
    :layers 3
    :result 3
    0))

(defn start [onboarding timestamp]
  (-> (merge (initial-state) onboarding)
      (assoc :status :active
             :step :baseline
             :phase :intro
             :started-at (or (:started-at onboarding) timestamp)
             :interrupted? false)))

(defn start-session [onboarding]
  (assoc onboarding :phase :running :interrupted? false))

(defn record-session [onboarding summary]
  (let [step (:step onboarding)
        record (merge {:step step}
                      (select-keys summary
                                   [:wpm :accuracy :stall-count :rhythm-consistency
                                    :completed? :completed-at :chart]))]
    (-> onboarding
        (assoc :phase :reflection)
        (update :records #(vec (take-last 3 (conj (or % []) record)))))))

(defn continue [onboarding]
  (case (:step onboarding)
    :baseline (assoc onboarding :step :rhythm :phase :intro :interrupted? false)
    :rhythm (assoc onboarding :step :layers :phase :intro :gesture-index 0
                   :interrupted? false)
    :layers (assoc onboarding :step :result :phase :reflection :interrupted? false)
    onboarding))

(defn complete-gesture [onboarding]
  (if (and (= :layers (:step onboarding))
           (= :gestures (:phase onboarding)))
    (let [next-index (min required-gestures (inc (:gesture-index onboarding 0)))]
      (cond-> (assoc onboarding :gesture-index next-index)
        (= required-gestures next-index) (assoc :phase :transfer-ready)))
    onboarding))

(defn begin-gestures [onboarding]
  (assoc onboarding :phase :gestures :gesture-index 0 :interrupted? false))

(defn finish [onboarding timestamp]
  (assoc onboarding
         :status :complete
         :step :complete
         :phase :complete
         :completed-at timestamp
         :interrupted? false))

(defn skip [onboarding timestamp]
  (assoc (finish onboarding timestamp) :skipped? true))

(defn- normalize-v1 [value]
  (let [known-steps #{:setup :baseline :rhythm :layers :result :complete}
        known-phases #{:intro :running :gestures :transfer-ready :reflection :complete}
        normalized (-> (initial-state)
                       (merge value)
                       (update :records #(vec (take-last 3 (or % []))))
                       (update :gesture-index #(max 0 (min required-gestures (or % 0)))))
        normalized (cond-> normalized
                     (not (known-steps (:step normalized))) (assoc :step :setup)
                     (not (known-phases (:phase normalized))) (assoc :phase :intro))]
    ;; Keystroke-level session state is deliberately not persisted. A reload
    ;; returns to the nearest safe start point and clearly says why.
    (if (= :running (:phase normalized))
      (cond-> (assoc normalized :phase :intro :interrupted? true)
        (= :layers (:step normalized)) (assoc :phase :transfer-ready))
      normalized)))

(defn migrate
  "Migrates the small local onboarding checkpoint. Existing PRETYPIST users
  without a checkpoint are placed beyond onboarding instead of being forced
  through a new first-run journey."
  [value returning-user?]
  (cond
    (= version (:version value)) (normalize-v1 value)
    returning-user? (completed-state)
    :else (initial-state)))
