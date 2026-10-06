(ns pretypist.typing.chunks
  (:require [clojure.string :as str]))

(defn passage-text [passage]
  (str/join " " (:chunks passage)))

(defn split-for-training [phrase-chunks maximum-words]
  (->> phrase-chunks
       (mapcat (fn [phrase]
                 (->> (str/split phrase #"\s+")
                      (partition-all (max 1 maximum-words))
                      (map #(str/join " " %)))))
       vec))

(defn chunk-ranges
  "Returns inclusive/exclusive character ranges for each phrase chunk."
  [chunks]
  (loop [remaining chunks
         start 0
         result []]
    (if-let [chunk (first remaining)]
      (let [end (+ start (count chunk))]
        (recur (next remaining)
               (inc end)
               (conj result {:chunk chunk :start start :end end})))
      result)))

(defn chunk-boundaries [chunks]
  (->> (chunk-ranges chunks)
       rest
       (map :start)
       set))

(defn word-boundary? [text cursor]
  (and (pos? cursor)
       (< cursor (count text))
       (= " " (subs text (dec cursor) cursor))
       (not= " " (subs text cursor (inc cursor)))))

(defn current-word-range [text cursor]
  (let [cursor (min cursor (count text))
        before (subs text 0 cursor)
        after (subs text cursor)
        start (let [i (.lastIndexOf before " ")] (if (neg? i) 0 (inc i)))
        rel-end (let [i (.indexOf after " ")] (if (neg? i) (count after) i))]
    [start (+ cursor rel-end)]))

(defn current-chunk-range [chunks cursor]
  (or (some #(when (and (<= (:start %) cursor) (< cursor (:end %))) %) (chunk-ranges chunks))
      (last (chunk-ranges chunks))))

(defn boundary-kind [text chunk-starts cursor]
  (cond
    (contains? chunk-starts cursor) :chunk
    (word-boundary? text cursor) :word
    :else :within))
