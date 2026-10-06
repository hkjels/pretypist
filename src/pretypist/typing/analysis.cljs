(ns pretypist.typing.analysis
  "Pure, bounded analysis of a completed typing session.

  The browser event stream is reduced to a compact long-lived profile and a
  short-lived completion analysis. No DOM, persistence, or scene objects enter
  this namespace."
  (:require [clojure.string :as str]
            [pretypist.layout.schema :as layout-schema]
            [pretypist.typing.gesture :as gesture]
            [pretypist.typing.metrics :as metrics]))

(def profile-version 1)

(def configuration
  {:ewma-weight 0.28
   :stall-ms 1500
   :minimum-weakness-score 0.35
   :maximum-character-records 128
   :maximum-stroke-records 96
   :maximum-physical-key-records 96
   :maximum-transition-records 32
   :chart-minimum-scale-ms 450
   :chart-maximum-notable-events 3
   :weakness-weights {:error 3
                      :stall 1.5
                      :latency-maximum 2
                      :complex-stroke 0.18
                      :boundary-stall 1.25}
   :confidence-floor 0.25
   :boundary-latency-ratio 1.25})

(defn initial-profile []
  {:version profile-version
   :sessions 0
   :characters {}
   :boundaries {}
   :strokes {}
   :transitions {}
   :physical-keys {}
   :last-session-at nil})

(defn- finite-number? [value]
  (and (number? value) (js/Number.isFinite value)))

(defn- clamp [minimum maximum value]
  (max minimum (min maximum value)))

(defn stroke-kind
  "Classifies an expected layout stroke without claiming hardware telemetry."
  [stroke]
  (let [operations (set (map first stroke))]
    (cond
      (some operations [:double-tap :double-hold]) :tap-dance
      (contains? operations :one-shot) :one-shot
      (and (contains? operations :hold) (contains? operations :release)) :layer-hold
      (> (count stroke) 1) :sequence
      :else :direct)))

(defn session-observations
  "Returns intended-character observations from text-input events.

  Layer strokes come from the layout package and are explicitly marked as
  inferred. Correction/revision events are not new character attempts."
  [session layout]
  (->> (:events session)
       (keep (fn [event]
               (when (and (contains? event :accepted?)
                          (string? (:expected event))
                          (not-empty (:expected event)))
                 (let [stroke (layout-schema/stroke-for layout (:expected event))
                       layers (gesture/stroke-layers layout stroke)]
                   {:character (:expected event)
                    :typed (:character event)
                    :accepted? (:accepted? event)
                    :error? (not (:accepted? event))
                    :cursor (:cursor event)
                    :at (:at event)
                    :latency-ms (when (and (:accepted? event)
                                           (finite-number? (:latency-ms event)))
                                  (:latency-ms event))
                    :boundary-kind (:boundary-kind event)
                    :physical-key-id (:physical-key-id event)
                    :physical-code (:physical-code event)
                    :stroke stroke
                    :layers layers
                    :transition (when-let [layer (first layers)] [:base layer])
                    :stroke-kind (stroke-kind stroke)
                    :gesture-source (when stroke :inferred)}))))
       vec))

(defn- blend [previous value]
  (if (finite-number? previous)
    (+ (* (- 1 (:ewma-weight configuration)) previous)
       (* (:ewma-weight configuration) value))
    value))

(defn- skill-observation [observation]
  ;; The first accepted character includes the time spent reading and finding
  ;; focus after a run starts. Keep it in the rhythm chart, but do not teach the
  ;; long-lived profile that this character itself is slow.
  (if (and (:accepted? observation) (zero? (:cursor observation)))
    (assoc observation :latency-ms nil)
    observation))

(defn update-evidence
  "Adds one observation to a bounded aggregate.

  Counts retain exact rates while latency uses an EWMA so recent practice
  matters without allowing one run to replace the longer history."
  [evidence observation practised-at]
  (let [evidence (or evidence {:attempts 0 :accepted 0 :errors 0 :stalls 0})
        accepted? (:accepted? observation)
        latency (:latency-ms observation)
        stalled? (and accepted? (finite-number? latency)
                      (>= latency (:stall-ms configuration)))
        next (cond-> (-> evidence
                         (update :attempts (fnil inc 0))
                         (assoc :last-practised-at practised-at))
               accepted? (update :accepted (fnil inc 0))
               (not accepted?) (update :errors (fnil inc 0))
               stalled? (update :stalls (fnil inc 0))
               (and accepted? (finite-number? latency))
               (assoc :latency-ms (blend (:latency-ms evidence) latency)))
        attempts (:attempts next)
        accepted (:accepted next)]
    (assoc next
           :error-rate (/ (:errors next) attempts)
           :stall-rate (if (pos? accepted) (/ (:stalls next) accepted) 0)
           :confidence (min 1 (/ attempts 8)))))

(defn- trim-recent [records limit]
  (if (<= (count records) limit)
    records
    (->> records
         (sort-by (comp :last-practised-at val) >)
         (take limit)
         (into {}))))

(defn update-profile
  "Compacts one completed session into character, boundary, and inferred-stroke
  evidence. Maps are capped by recency before persistence."
  [profile observations completed-at]
  (let [profile (merge (initial-profile) (or profile {}))
        updated
        (reduce
         (fn [result observation]
           (let [observation (skill-observation observation)
                 character (:character observation)
                 boundary (:boundary-kind observation)
                 physical-key-id (:physical-key-id observation)
                 transition (:transition observation)
                 complex-stroke? (not= :direct (:stroke-kind observation))]
             (cond-> (update-in result [:characters character]
                                update-evidence observation completed-at)
               (keyword? physical-key-id)
               (update-in [:physical-keys physical-key-id]
                          (fn [evidence]
                            (assoc (update-evidence evidence observation completed-at)
                                   :code (:physical-code observation)
                                   :source :observed)))

               (and (:accepted? observation) (keyword? boundary))
               (update-in [:boundaries boundary]
                          update-evidence observation completed-at)

               complex-stroke?
               (update-in [:strokes character]
                          (fn [evidence]
                            (assoc (update-evidence evidence observation completed-at)
                                   :stroke (:stroke observation)
                                   :stroke-kind (:stroke-kind observation)
                                   :source :inferred)))
               transition
               (update-in [:transitions transition]
                          (fn [evidence]
                            (assoc (update-evidence evidence observation completed-at)
                                   :from (first transition)
                                   :to (second transition)
                                   :source :inferred))))))
         profile
         observations)]
    (-> updated
        (update :sessions (fnil inc 0))
        (assoc :last-session-at completed-at)
        (update :characters trim-recent (:maximum-character-records configuration))
        (update :strokes trim-recent (:maximum-stroke-records configuration))
        (update :transitions trim-recent (:maximum-transition-records configuration))
        (update :physical-keys trim-recent (:maximum-physical-key-records configuration)))))

(defn- percentile [values proportion]
  (when (seq values)
    (let [sorted (vec (sort values))
          index (js/Math.floor (* (dec (count sorted)) proportion))]
      (nth sorted index))))

(defn- average [values]
  (if (seq values) (/ (reduce + values) (count values)) 0))

(defn- rolling-average [values index]
  (let [start (max 0 (dec index))
        end (min (count values) (+ index 2))]
    (average (subvec values start end))))

(defn rhythm-chart
  "Produces normalized chart geometry and notable events for Replicant SVG."
  [session observations]
  (let [accepted (vec (filter #(and (:accepted? %) (finite-number? (:latency-ms %)))
                              observations))
        raw-latencies (mapv #(max 0 (:latency-ms %)) accepted)
        typical (or (metrics/median raw-latencies) 0)
        scale (max (:chart-minimum-scale-ms configuration)
                   (* 1.7 typical)
                   (or (percentile raw-latencies 0.9) 0))
        smoothed (mapv #(rolling-average raw-latencies %) (range (count raw-latencies)))
        text-length (max 1 (dec (count (:text session))))
        point-for (fn [observation latency]
                    {:x (clamp 0 1 (/ (:cursor observation) text-length))
                     ;; Higher on the chart means a quicker, steadier moment.
                     :y (- 1 (clamp 0.06 0.94 (/ latency scale)))
                     :cursor (:cursor observation)
                     :character (:character observation)
                     :latency-ms (:latency-ms observation)
                     :boundary-kind (:boundary-kind observation)})
        points (mapv point-for accepted smoothed)
        position-x (fn [cursor] (clamp 0 1 (/ (or cursor 0) text-length)))
        errors (->> observations
                    (filter :error?)
                    (mapv #(select-keys (assoc % :kind :error
                                              :x (position-x (:cursor %)))
                                        [:kind :x :cursor :character :typed])))
        stalls (->> accepted
                    (filter #(>= (:latency-ms %) (:stall-ms configuration)))
                    (mapv #(select-keys (assoc % :kind :stall
                                              :x (position-x (:cursor %)))
                                        [:kind :x :cursor :character :latency-ms])))
        boundaries (->> accepted
                        (filter #(= :chunk (:boundary-kind %)))
                        (mapv #(select-keys (assoc % :kind :chunk
                                                  :x (position-x (:cursor %)))
                                            [:kind :x :cursor])))
        gestures (->> accepted
                      (filter #(and (= :inferred (:gesture-source %))
                                    (not= :direct (:stroke-kind %))))
                      (mapv #(select-keys (assoc % :kind :gesture
                                                :x (position-x (:cursor %)))
                                          [:kind :x :cursor :character :stroke-kind])))
        notable (->> (concat errors (sort-by :latency-ms > stalls) gestures)
                     (take (:chart-maximum-notable-events configuration))
                     vec)]
    {:points points
     :baseline-y (when (pos? scale)
                   (- 1 (clamp 0.06 0.94 (/ typical scale))))
     :markers (vec (concat boundaries gestures stalls errors))
     :notable notable
     :error-count (count errors)
     :stall-count (count stalls)
     :typical-latency-ms (js/Math.round typical)}))

(defn- evidence-score [evidence baseline-latency complex-stroke?]
  (let [weights (:weakness-weights configuration)
        latency (or (:latency-ms evidence) baseline-latency)
        ratio (/ latency (max 80 baseline-latency))
        base (+ (* (:error weights) (:error-rate evidence))
                (* (:stall weights) (:stall-rate evidence))
                (min (:latency-maximum weights) (max 0 (- ratio 1))))
        confidence-floor (:confidence-floor configuration)
        confidence-factor (+ confidence-floor
                             (* (- 1 confidence-floor) (or (:confidence evidence) 0)))]
    (* confidence-factor
       (+ base (if (and complex-stroke? (> base 0.1))
                 (:complex-stroke weights)
                 0)))))

(defn- combine-evidence [historical current]
  (if-not historical
    current
    (let [attempts (+ (:attempts historical 0) (:attempts current 0))
          accepted (+ (:accepted historical 0) (:accepted current 0))
          errors (+ (:errors historical 0) (:errors current 0))
          stalls (+ (:stalls historical 0) (:stalls current 0))
          historical-latency (:latency-ms historical)
          current-latency (:latency-ms current)
          latency (cond
                    (and historical-latency current-latency)
                    (blend historical-latency current-latency)
                    :else (or current-latency historical-latency))]
      (cond-> {:attempts attempts
               :accepted accepted
               :errors errors
               :stalls stalls
               :error-rate (if (pos? attempts) (/ errors attempts) 0)
               :stall-rate (if (pos? accepted) (/ stalls accepted) 0)
               :confidence (min 1 (/ attempts 8))
               :last-practised-at (:last-practised-at current)}
        latency (assoc :latency-ms latency)))))

(defn- current-evidence [observations]
  (reduce (fn [result observation]
            (let [observation (skill-observation observation)]
              (update result (:character observation)
                      update-evidence observation (:at observation))))
          {}
          observations))

(defn- boundary-evidence [observations]
  (reduce (fn [result observation]
            (if (and (:accepted? observation) (keyword? (:boundary-kind observation)))
              (update result (:boundary-kind observation)
                      update-evidence observation (:at observation))
              result))
          {}
          observations))

(defn- weakness-reason [evidence complex-stroke?]
  (cond
    (pos? (:errors evidence)) :errors
    (pos? (:stalls evidence)) :stall
    complex-stroke? :layer-transition
    :else :latency))

(defn- character-candidates [observations profile baseline-latency]
  (let [by-character (current-evidence observations)
        stroke-by-character (into {} (keep (juxt :character :stroke) observations))]
    (for [[character current] by-character
          :when (not (str/blank? character))
          :let [evidence (combine-evidence (get-in profile [:characters character]) current)
                stroke (or (get stroke-by-character character)
                           (get-in profile [:strokes character :stroke]))
                kind (stroke-kind stroke)
                complex? (not= :direct kind)
                score (evidence-score evidence baseline-latency complex?)]]
      {:kind :character
       :target character
       :score score
       :reason (weakness-reason evidence complex?)
       :evidence evidence
       :stroke stroke
       :stroke-kind kind
       :source (when stroke :inferred)})))

(defn- boundary-candidates [observations profile baseline-latency]
  (for [[boundary current] (boundary-evidence observations)
        :when (contains? #{:chunk :word} boundary)
        :let [evidence (combine-evidence (get-in profile [:boundaries boundary]) current)
              ratio (/ (or (:latency-ms evidence) 0) (max 80 baseline-latency))
              score (+ (max 0 (- ratio (:boundary-latency-ratio configuration)))
                       (* (get-in configuration [:weakness-weights :boundary-stall])
                          (:stall-rate evidence)))]
        :when (> score 0)]
    {:kind :boundary
     :target boundary
     :score score
     :reason (if (pos? (:stalls evidence)) :stall :latency)
     :evidence evidence}))

(defn select-weakness
  "Chooses one evidence-backed focus. Ties are deterministic."
  ([observations] (select-weakness observations nil))
  ([observations profile]
   (let [accepted-latencies (->> observations
                                 (filter #(and (:accepted? %)
                                               (pos? (:cursor %))
                                               (finite-number? (:latency-ms %))))
                                 (map :latency-ms))
         baseline-latency (max 80 (or (metrics/median accepted-latencies) 250))
         candidate (->> (concat (character-candidates observations profile baseline-latency)
                                (boundary-candidates observations profile baseline-latency))
                        (sort-by (juxt (comp - :score)
                                       #(str (:kind %) "/" (:target %))))
                        first)]
     (when (and candidate
                (>= (:score candidate) (:minimum-weakness-score configuration)))
       candidate))))

(defn- rounded-ms [value]
  (* 10 (js/Math.round (/ (or value 0) 10))))

(defn weakness-copy [weakness]
  (when weakness
    (let [evidence (:evidence weakness)]
      (case [(:kind weakness) (:reason weakness)]
        [:character :errors]
        {:key :analysis/focus-character-errors
         :params {:character (:target weakness) :count (:errors evidence)}}

        [:character :stall]
        {:key :analysis/focus-character-stall
         :params {:character (:target weakness)
                  :latency (rounded-ms (:latency-ms evidence))}}

        [:character :layer-transition]
        {:key :analysis/focus-layer-character
         :params {:character (:target weakness)}}

        [:character :latency]
        {:key :analysis/focus-character-latency
         :params {:character (:target weakness)
                  :latency (rounded-ms (:latency-ms evidence))}}

        [:boundary :stall]
        {:key (if (= :chunk (:target weakness))
                :analysis/focus-chunk-stall
                :analysis/focus-word-stall)
         :params {:latency (rounded-ms (:latency-ms evidence))}}

        [:boundary :latency]
        {:key (if (= :chunk (:target weakness))
                :analysis/focus-chunk-latency
                :analysis/focus-word-latency)
         :params {:latency (rounded-ms (:latency-ms evidence))}}

        nil))))

(defn improvement-copy [summary previous-summary]
  (if-not previous-summary
    {:key :analysis/improvement-baseline :params {}}
    (let [accuracy-delta (- (:accuracy summary) (or (:accuracy previous-summary) 0))
          rhythm-delta (- (:rhythm-consistency summary)
                          (or (:rhythm-consistency previous-summary) 0))
          wpm-delta (- (:wpm summary) (or (:wpm previous-summary) 0))]
      (cond
        (>= accuracy-delta 0.01)
        {:key :analysis/improvement-accuracy
         :params {:value (js/Math.round (* 100 accuracy-delta))}}

        (>= rhythm-delta 0.04)
        {:key :analysis/improvement-rhythm
         :params {:value (js/Math.round (* 100 rhythm-delta))}}

        (and (>= wpm-delta 3) (<= wpm-delta 25))
        {:key :analysis/improvement-pace
         :params {:value (js/Math.round wpm-delta)}}

        :else
        {:key :analysis/improvement-steady
         :params {:count (:stall-count summary)}}))))

(defn prescription-for [session weakness language difficulty]
  (when weakness
    {:id (str (:id session) "-consolidation")
     :kind (:kind weakness)
     :target (:target weakness)
     :reason (:reason weakness)
     :mode (:mode session)
     :language language
     :difficulty difficulty
     :source-passage-id (:passage-id session)
     :stroke (:stroke weakness)
     :stroke-kind (:stroke-kind weakness)
     :gesture-source (:source weakness)
     :difficulty-change? false
     :copy (if (= :character (:kind weakness))
             {:key :analysis/prescription-character
              :params {:character (:target weakness)}}
             {:key (if (= :chunk (:target weakness))
                     :analysis/prescription-chunk
                     :analysis/prescription-word)
              :params {}})}))

(defn analyze-session
  "Builds the short-lived completion model and the next persisted profile."
  [{:keys [session layout profile previous-summary language summary difficulty]}]
  (let [summary (or summary (metrics/summarize session))
        observations (session-observations session layout)
        weakness (select-weakness observations profile)
        completed-at (or (:completed-at session) (:last-input-at session) 0)]
    {:profile (update-profile profile observations completed-at)
     :chart (rhythm-chart session observations)
     :improvement (improvement-copy summary previous-summary)
     :weakness (when weakness (assoc weakness :copy (weakness-copy weakness)))
     :prescription (prescription-for session weakness language difficulty)}))
