(ns pretypist.corpus.analysis
  "Deterministic, cross-platform analysis of authored prose resources.

  This namespace is intentionally usable from both the JVM build task and the
  browser test suite. Mechanical fields describe expected layout strokes, not
  observed typing or firmware timing."
  (:require [clojure.string :as str]))

(def metadata-version 1)

(def punctuation-classes
  {"," :comma
   "." :period
   ";" :semicolon
   ":" :colon
   "!" :exclamation
   "?" :question
   "\"" :quote
   "'" :apostrophe
   "-" :hyphen
   "(" :parenthesis
   ")" :parenthesis})

(def word-pattern #"[A-Za-zÆØÅæøå]+(?:['’][A-Za-zÆØÅæøå]+)?|[0-9]+(?::[0-9]+)?")
(def letter-pattern #"[A-Za-zÆØÅæøå]")

(defn- rounded [value digits]
  (let [factor (#?(:clj Math/pow :cljs js/Math.pow) 10 digits)]
    (/ (#?(:clj Math/round :cljs js/Math.round) (double (* value factor))) factor)))

(defn- average [values]
  (if (seq values) (/ (reduce + values) (count values)) 0))

(defn- rate-per-thousand [numerator denominator]
  (if (pos? denominator)
    (#?(:clj Math/round :cljs js/Math.round)
     (double (* 1000 (/ numerator denominator))))
    0))

(defn- sorted-frequencies [values]
  (into (sorted-map) (frequencies values)))

(defn- words [text]
  (vec (re-seq word-pattern text)))

(defn token-count [text]
  (count (words text)))

(defn- letters [text]
  (->> text
       (map str)
       (filter #(re-matches letter-pattern %))
       (map str/lower-case)
       vec))

(defn- ngrams [size word]
  (let [word (str/lower-case word)]
    (if (< (count word) size)
      []
      (mapv #(subs word % (+ % size))
            (range (inc (- (count word) size)))))))

(defn- sentence-word-counts [text]
  (->> (str/split text #"[.!?]+")
       (remove str/blank?)
       (mapv #(count (words %)))))

(defn- reading-analysis [word-list sentence-lengths]
  (let [word-count (count word-list)
        mean-word-length (average (map count word-list))
        mean-sentence-words (average sentence-lengths)
        long-words (count (filter #(>= (count %) 8) word-list))
        complexity-score (#?(:clj Math/round :cljs js/Math.round)
                          (double (+ (* 8 mean-word-length)
                                     mean-sentence-words
                                     (* 18 (if (pos? word-count) (/ long-words word-count) 0)))))
        band (cond
               (< complexity-score 48) :gentle
               (< complexity-score 62) :standard
               :else :dense)]
    {:band band
     :score complexity-score
     :mean-word-length (rounded mean-word-length 1)
     :mean-sentence-words (rounded mean-sentence-words 1)
     :long-word-count long-words}))

(defn- stroke-kind [stroke]
  (let [operations (set (map first stroke))]
    (cond
      (some operations [:double-tap :double-hold]) :tap-dance
      (contains? operations :one-shot) :one-shot
      (and (contains? operations :hold) (contains? operations :release)) :layer-hold
      (> (count stroke) 1) :sequence
      :else :direct)))

(defn- stroke-layers [layout stroke]
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

(defn- output-key-id [stroke]
  (->> stroke
       reverse
       (some (fn [[operation key-id]]
               (when (contains? #{:tap :double-tap :double-hold} operation)
                 key-id)))))

(defn- mechanical-analysis [layout text]
  (let [key-by-id (into {} (map (juxt :id identity) (:keys layout)))
        outputs (->> text
                     (map str)
                     (remove str/blank?)
                     (mapv (fn [character]
                             (let [stroke (get (:strokes layout) character)
                                   key (some->> stroke output-key-id (get key-by-id))]
                               {:character character
                                :stroke stroke
                                :stroke-kind (when stroke (stroke-kind stroke))
                                :layers (when stroke (stroke-layers layout stroke))
                                :hand (:hand key)
                                :finger (:finger key)}))))
        resolved (filterv :stroke outputs)
        key-resolved (filterv :finger resolved)
        pairs (partition 2 1 key-resolved)
        hand-alternations (count (filter (fn [[left right]]
                                           (not= (:hand left) (:hand right)))
                                         pairs))
        repeated-fingers (count (filter (fn [[left right]]
                                          (= (:finger left) (:finger right)))
                                        pairs))
        transitions (mapcat (fn [{:keys [layers]}]
                              (map (fn [layer] [:base layer]) layers))
                            resolved)]
    {:resolved-characters (count resolved)
     :unresolved-characters (->> outputs
                                 (remove :stroke)
                                 (map :character)
                                 distinct
                                 sort
                                 vec)
     :stroke-kinds (sorted-frequencies (keep :stroke-kind resolved))
     :required-layers (->> resolved (mapcat :layers) distinct sort vec)
     :layer-transitions (sorted-frequencies transitions)
     :hand-alternations hand-alternations
     :hand-alternation-per-thousand (rate-per-thousand hand-alternations (count pairs))
     :repeated-fingers repeated-fingers
     :repeated-finger-per-thousand (rate-per-thousand repeated-fingers (count pairs))}))

(defn analyze-passage
  "Returns reviewable mechanical metadata for one authored passage."
  [layout passage]
  (let [text (str/join " " (:chunks passage))
        word-list (words text)
        letter-list (letters text)
        chunk-word-counts (mapv #(count (words %)) (:chunks passage))
        sentence-lengths (sentence-word-counts text)
        punctuation (keep punctuation-classes (map str text))
        punctuation-count (count punctuation)]
    {:language (:language passage)
     :tags (vec (sort (:tags passage)))
     :text {:characters (count text)
            :words (count word-list)
            :sentences (count sentence-lengths)}
     :chunks {:count (count (:chunks passage))
              :word-counts chunk-word-counts
              :minimum-words (if (seq chunk-word-counts) (apply min chunk-word-counts) 0)
              :maximum-words (if (seq chunk-word-counts) (apply max chunk-word-counts) 0)
              :mean-words (rounded (average chunk-word-counts) 1)}
     :reading (reading-analysis word-list sentence-lengths)
     :syntax {:sentence-word-counts sentence-lengths
              :sentence-length-spread (if (seq sentence-lengths)
                                        (- (apply max sentence-lengths)
                                           (apply min sentence-lengths))
                                        0)
              :punctuation-variety (count (distinct punctuation))}
     :punctuation {:count punctuation-count
                   :per-thousand-characters (rate-per-thousand punctuation-count (count text))
                   :types (sorted-frequencies punctuation)}
     :letters {:frequencies (sorted-frequencies letter-list)
               :norwegian (into (sorted-map)
                                 (map (fn [character]
                                        [character (get (frequencies letter-list) character 0)]))
                                 ["æ" "ø" "å"])}
     :ngrams {:bigrams (sorted-frequencies (mapcat #(ngrams 2 %) word-list))
              :trigrams (sorted-frequencies (mapcat #(ngrams 3 %) word-list))}
     :layout (mechanical-analysis layout text)}))

(defn analyze-corpora [layout passages]
  {:corpus/metadata-version metadata-version
   :layout/id (:package/id layout)
   :layout/revision (:package/revision layout)
   :generated/by "npm run analyze:corpora"
   :passages (into (sorted-map)
                   (map (fn [passage]
                          [(:id passage) (analyze-passage layout passage)]))
                   passages)})

(defn enrich-passages [passages metadata]
  (mapv (fn [passage]
          (if-let [analysis (get-in metadata [:passages (:id passage)])]
            (assoc passage :corpus/analysis
                   (assoc analysis
                          :layout-id (:layout/id metadata)
                          :layout-revision (:layout/revision metadata)))
            passage))
        passages))
