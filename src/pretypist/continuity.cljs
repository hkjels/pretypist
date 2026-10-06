(ns pretypist.continuity)

(def configuration
  {:week-ms (* 7 24 60 60 1000)
   :minimum-trend-sessions 3
   :rhythm-improvement 0.04
   :accuracy-improvement 0.01})

(def earliest-sensible-epoch-ms 946684800000)

(defn- timestamp [summary]
  (let [value (or (:completed-on summary) (:completed-at-epoch summary))]
    (when (and (number? value) (>= value earliest-sensible-epoch-ms))
      value)))

(defn recent-week
  "Returns genuinely dated, completed summaries from the trailing seven days.
  Legacy performance.now timestamps are intentionally not presented as dates."
  [summaries now]
  (let [start (- now (:week-ms configuration))]
    (->> summaries
         (filter (fn [summary]
                   (when-let [completed-on (timestamp summary)]
                     (<= start completed-on now))))
         (sort-by timestamp)
         vec)))

(defn- average [values]
  (when (seq values) (/ (reduce + values) (count values))))

(defn- trend-delta [summaries metric]
  (let [values (vec (keep #(when (number? (get % metric)) (get % metric)) summaries))
        split (quot (count values) 2)]
    (when (and (pos? split) (< split (count values)))
      (- (average (subvec values split))
         (average (subvec values 0 split))))))

(defn insight
  "Builds a small, evidence-bounded continuation description for the home UI."
  [summaries prescription now]
  (let [recent (recent-week summaries now)
        count (count recent)
        rhythm-delta (trend-delta recent :rhythm-consistency)
        accuracy-delta (trend-delta recent :accuracy)
        trend (cond
                (and (>= count (:minimum-trend-sessions configuration))
                     (>= (or rhythm-delta 0) (:rhythm-improvement configuration)))
                :rhythm

                (and (>= count (:minimum-trend-sessions configuration))
                     (>= (or accuracy-delta 0) (:accuracy-improvement configuration)))
                :accuracy

                (>= count (:minimum-trend-sessions configuration)) :cadence
                :else :return)]
    (when (pos? count)
      {:sessions count
       :headline {:key (if (= count 1)
                         :continuity/session-singular
                         :continuity/session-plural)
                  :params {:count count}}
       :trend {:key (keyword "continuity" (str "trend-" (name trend)))}
       :wpm-history (vec (keep #(when (number? (:wpm %)) (:wpm %)) recent))
       :prescription prescription
       :repeat-focus? (boolean prescription)})))
