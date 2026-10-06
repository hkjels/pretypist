(ns pretypist.corpus.selector
  "Pure, deterministic scoring of authored passages.

  The selector may use build-time metadata, but always derives focus matches
  from the actual (possibly length-trimmed) prose it will present."
  (:require [clojure.string :as str]
            [pretypist.typing.chunks :as chunks]))

(def configuration
  {:weights {:focus-occurrence 18
             :focus-occurrence-cap 5
             :missing-focus -55
             :difficulty-distance -1.2
             :reviewed-prose 14
             :coherent-prose 8
             :fresh 22
             :current-passage -18
             :prior-exposure -12
             :recent-exposure -8
             :excessive-focus -4
             :other-complex-stroke -0.45}
   :targets {:flow-punctuation-min 12
             :flow-punctuation-range 62
             :reading-score 53
             :default-chunk-words 3
             :useful-focus-occurrences 3
             :maximum-focus-occurrences 7}})

(defn- passage-text-lower [passage]
  (str/lower-case (chunks/passage-text passage)))

(defn- count-substring [source target]
  (if (str/blank? target)
    0
    (loop [offset 0 matches 0]
      (let [index (.indexOf source target offset)]
        (if (neg? index)
          matches
          (recur (+ index (max 1 (count target))) (inc matches)))))))

(defn target-occurrences [passage prescription]
  (case (:kind prescription)
    :character (count-substring (passage-text-lower passage)
                                (str/lower-case (str (:target prescription))))
    :boundary (case (:target prescription)
                :chunk (count (:chunks passage))
                :word (count (re-seq #"\S+" (chunks/passage-text passage)))
                0)
    0))

(defn- raw-punctuation-density [passage]
  (let [text (chunks/passage-text passage)
        punctuation-count (count (re-seq #"[,.!?;:\"'()\-]" text))]
    (if (pos? (count text))
      (js/Math.round (* 1000 (/ punctuation-count (count text))))
      0)))

(defn- passage-stat [passage path fallback]
  (or (get-in passage (into [:corpus/analysis] path)) fallback))

(defn- average [values]
  (if (seq values) (/ (reduce + values) (count values)) 0))

(defn- mean-chunk-words [passage]
  (average (map #(count (re-seq #"\S+" %)) (:chunks passage))))

(defn- difficulty-distance [passage mode difficulty]
  (case mode
    :flow
    (let [density (raw-punctuation-density passage)
          target (+ (get-in configuration [:targets :flow-punctuation-min])
                    (* (get-in configuration [:targets :flow-punctuation-range])
                       (get difficulty :punctuation-density 0)))]
      (/ (js/Math.abs (- density target)) 4))

    :chunks
    (* 5 (js/Math.abs (- (mean-chunk-words passage)
                          (get difficulty :max-words
                               (get-in configuration [:targets :default-chunk-words])))))

    :horizon
    (/ (js/Math.abs (- (passage-stat passage [:reading :score] 53)
                        (get-in configuration [:targets :reading-score])))
       2)

    :layers 0
    0))

(defn- exposure [recent-summaries passage-id]
  (let [matching (keep-indexed (fn [index summary]
                                 (when (= passage-id (:passage-id summary)) index))
                               (reverse recent-summaries))]
    {:count (count matching)
     :most-recent-index (first matching)}))

(defn- complex-stroke-count [passage active-layout-id]
  (let [metadata-layout-id (get-in passage [:corpus/analysis :layout-id])
        applicable? (or (nil? active-layout-id)
                        (nil? metadata-layout-id)
                        (= active-layout-id metadata-layout-id))
        kinds (if applicable?
                (passage-stat passage [:layout :stroke-kinds] {})
                {})]
    (reduce + 0 (vals (dissoc kinds :direct)))))

(defn- active-dimension [mode prescription]
  (if prescription
    (case [(:kind prescription) (:reason prescription)]
      [:character :layer-transition] :layer-transition
      [:character :errors] :target-character
      [:character :stall] :target-character
      [:character :latency] :target-character
      [:boundary :stall] (:target prescription)
      [:boundary :latency] (:target prescription)
      :focused-prose)
    (case mode
      :flow :punctuation-density
      :horizon :read-ahead
      :chunks :chunk-length
      :layers :layer-transition
      :prose-variety)))

(defn- explanation [mode prescription occurrences]
  (cond
    (and prescription (= :character (:kind prescription)))
    {:key :selection/focus-character
     :params {:character (:target prescription) :count occurrences}}

    (and prescription (= :chunk (:target prescription)))
    {:key :selection/focus-chunks :params {}}

    (and prescription (= :word (:target prescription)))
    {:key :selection/focus-words :params {}}

    (= mode :horizon) {:key :selection/horizon :params {}}
    (= mode :chunks) {:key :selection/chunks :params {}}
    (= mode :layers) {:key :selection/layers :params {}}
    :else {:key :selection/flow :params {}}))

(defn score-passage
  [passage {:keys [mode difficulty prescription recent-summaries current-passage-id
                   layout-id]}]
  (let [weights (:weights configuration)
        occurrences (if prescription (target-occurrences passage prescription) 0)
        occurrence-cap (get-in configuration [:weights :focus-occurrence-cap])
        useful-limit (get-in configuration [:targets :useful-focus-occurrences])
        maximum-focus (get-in configuration [:targets :maximum-focus-occurrences])
        exposure (exposure recent-summaries (:id passage))
        tags (set (:tags passage))
        complex-count (complex-stroke-count passage layout-id)
        focus-score (if prescription
                      (if (pos? occurrences)
                        (* (:focus-occurrence weights) (min occurrence-cap occurrences))
                        (:missing-focus weights))
                      0)
        difficulty-score (* (:difficulty-distance weights)
                            (difficulty-distance passage mode difficulty))
        editorial-score (+ (if (contains? tags :reviewed) (:reviewed-prose weights) 0)
                           (if (contains? tags :coherent-prose) (:coherent-prose weights) 0))
        novelty-score (+ (if (zero? (:count exposure)) (:fresh weights) 0)
                         (if (= current-passage-id (:id passage)) (:current-passage weights) 0)
                         (* (:prior-exposure weights) (:count exposure))
                         (if (= 0 (:most-recent-index exposure)) (:recent-exposure weights) 0))
        repetition-penalty (if (> occurrences maximum-focus)
                             (* (:excessive-focus weights) (- occurrences maximum-focus))
                             0)
        other-complex (if prescription
                        (max 0 (- complex-count (min occurrences useful-limit)))
                        0)
        simultaneous-penalty (* (:other-complex-stroke weights) other-complex)
        components {:focus-relevance focus-score
                    :difficulty-fit difficulty-score
                    :editorial-quality editorial-score
                    :novelty novelty-score
                    :repetition-penalty repetition-penalty
                    :simultaneous-difficulty-penalty simultaneous-penalty}
        score (reduce + (vals components))]
    {:passage passage
     :score score
     :components components
     :active-dimension (active-dimension mode prescription)
     :evidence {:target (:target prescription)
                :target-occurrences occurrences
                :punctuation-per-thousand (raw-punctuation-density passage)
                :mean-chunk-words (mean-chunk-words passage)
                :reading-band (passage-stat passage [:reading :band] :standard)
                :prior-exposures (:count exposure)}
     :explanation (explanation mode prescription occurrences)
     :difficulty-change? false}))

(defn select-passage
  "Returns an explainable selection prescription containing the passage."
  [passages context]
  (let [scored (map #(score-passage % context) passages)
        character-focus? (= :character (get-in context [:prescription :kind]))
        eligible (if character-focus?
                   (filter #(pos? (get-in % [:evidence :target-occurrences])) scored)
                   scored)]
    (->> eligible
         (sort-by (juxt (comp - :score)
                        #(get-in % [:evidence :prior-exposures])
                        #(str (get-in % [:passage :id]))))
         first)))
