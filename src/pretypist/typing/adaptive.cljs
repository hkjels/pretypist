(ns pretypist.typing.adaptive
  (:require [pretypist.typing.analysis :as analysis]
            [pretypist.typing.metrics :as metrics]))

(def progress-version 2)

(def thresholds
  {:minimum-accuracy 0.97
   :baseline-speed-ratio 0.75
   :maximum-stalls 3
   :calibration-runs 3
   :horizon-unlock-wpm 25
   :ema-weight 0.25
   :horizon-mask-stages 6})

(def initial-difficulty
  {:flow {:punctuation-density 0}
   :horizon {:preview-words 7 :mask-stage 0}
   :chunks {:boundary-opacity 1 :max-words 2 :step 0}
   :layers {:weak-transition-frequency 0.15}})

(def manual-presets
  {:easy initial-difficulty
   :standard {:flow {:punctuation-density 0.45}
              :horizon {:preview-words 7 :mask-stage 2}
              :chunks {:boundary-opacity 0.65 :max-words 3 :step 0}
              :layers {:weak-transition-frequency 0.3}}
   :challenge {:flow {:punctuation-density 1}
               :horizon {:preview-words 5 :mask-stage 5}
               :chunks {:boundary-opacity 0.25 :max-words 5 :step 0}
               :layers {:weak-transition-frequency 0.6}}})

(defn initial-progress []
  {:version progress-version
   :calibration []
   :baseline-wpm nil
   :recent []
   :difficulty initial-difficulty
   :profile (analysis/initial-profile)
   :next-change nil
   :next-prescription nil})

(defn migrate-progress
  "Migrates persisted progress at one explicit boundary. Unknown future data
  is rejected so it cannot quietly corrupt adaptive decisions."
  [value]
  (case (:version value)
    1 (-> (initial-progress)
          (merge value)
          (assoc :version progress-version
                 :profile (analysis/initial-profile)
                 :next-prescription nil)
          (update :difficulty #(merge-with merge initial-difficulty (or % {}))))
    2 (-> (initial-progress)
          (merge value)
          (update :difficulty #(merge-with merge initial-difficulty (or % {})))
          (update :profile #(merge (analysis/initial-profile) (or % {}))))
    nil))

(defn successful-run? [summary baseline]
  (and (:completed? summary)
       (>= (:accuracy summary) (:minimum-accuracy thresholds))
       (or (nil? baseline)
           (>= (:wpm summary) (* baseline (:baseline-speed-ratio thresholds))))
       (<= (:stall-count summary) (:maximum-stalls thresholds))))

(defn- update-baseline [baseline wpm]
  (if baseline
    (let [bounded-wpm (max (* 0.6 baseline) (min (* 1.5 baseline) wpm))]
      (+ (* (- 1 (:ema-weight thresholds)) baseline)
         (* (:ema-weight thresholds) bounded-wpm)))
    wpm))

(defn horizon-unlocked? [progress]
  (and (>= (count (:calibration progress)) (:calibration-runs thresholds))
       (>= (or (:baseline-wpm progress) 0) (:horizon-unlock-wpm thresholds))
       (every? #(>= (:accuracy %) (:minimum-accuracy thresholds))
               (take-last (:calibration-runs thresholds) (:calibration progress)))))

(defn effective-difficulty [progress mode manual-override]
  (let [automatic (get-in progress [:difficulty mode])
        selected (if (= manual-override :automatic)
                   automatic
                   (get-in manual-presets [manual-override mode] automatic))]
    ;; Even a manual challenge preset preserves the gentle calibration ramp.
    (if (and (= mode :horizon) (not (horizon-unlocked? progress)))
      (assoc selected :mask-stage 0)
      selected)))

(defn- advance-flow [progress]
  [(update-in progress [:difficulty :flow :punctuation-density] #(min 1 (+ % 0.1)))
   :adaptive/punctuation])

(defn- advance-horizon [progress]
  (if (horizon-unlocked? progress)
    [(update-in progress [:difficulty :horizon :mask-stage]
                #(min (:horizon-mask-stages thresholds) (inc %)))
     :adaptive/horizon-mask]
    [(update-in progress [:difficulty :horizon :preview-words] #(min 10 (inc %)))
     :adaptive/horizon-preview]))

(defn- advance-chunks [progress]
  (let [step (get-in progress [:difficulty :chunks :step] 0)]
    (if (even? step)
      [(-> progress
           (update-in [:difficulty :chunks :boundary-opacity] #(max 0.2 (- % 0.1)))
           (update-in [:difficulty :chunks :step] inc))
       :adaptive/chunks-boundary]
      [(-> progress
           (update-in [:difficulty :chunks :max-words] #(min 5 (inc %)))
           (update-in [:difficulty :chunks :step] inc))
       :adaptive/chunks-length])))

(defn- advance-layers [progress]
  [(update-in progress [:difficulty :layers :weak-transition-frequency] #(min 0.65 (+ % 0.05)))
   :adaptive/layers-frequency])

(defn advance-one [progress mode]
  (case mode
    :horizon (advance-horizon progress)
    :chunks (advance-chunks progress)
    :layers (advance-layers progress)
    (advance-flow progress)))

(defn- record-history [progress summary]
  (let [progress (or (migrate-progress progress)
                     (merge (initial-progress) (or progress {})))
        completed-summary (select-keys summary [:wpm :accuracy :stall-count :completed?])
        baseline-eligible? (and (:completed? summary)
                                (>= (:accuracy summary) (:minimum-accuracy thresholds)))
        previous-baseline (:baseline-wpm progress)]
    (cond-> (update progress :recent
                    #(vec (take-last 12 (conj (or % []) completed-summary))))
      baseline-eligible?
      (assoc :baseline-wpm (update-baseline previous-baseline (:wpm summary)))

      baseline-eligible?
      (update :calibration #(vec (take-last (:calibration-runs thresholds)
                                            (conj (or % []) completed-summary)))))))

(defn record-run [progress mode summary adaptive?]
  (let [success? (successful-run? summary (:baseline-wpm progress))
        progress (record-history progress summary)]
    (cond
      (not adaptive?) (assoc progress :next-change :adaptive/off)
      (not success?) (assoc progress :next-change :adaptive/consolidation)
      :else (let [[advanced explanation] (advance-one progress mode)]
              (assoc advanced :next-change explanation)))))

(defn record-consolidation-run
  "Records evidence and calibration without increasing difficulty. A focused
  run is a chance to settle a transition, never a hidden promotion step."
  [progress summary]
  (assoc (record-history progress summary)
         :next-change (if (successful-run? summary (:baseline-wpm progress))
                        :adaptive/consolidation-complete
                        :adaptive/consolidation)
         :next-prescription nil))
