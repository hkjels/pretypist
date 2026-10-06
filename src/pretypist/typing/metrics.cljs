(ns pretypist.typing.metrics)

(defn median [xs]
  (when (seq xs)
    (let [sorted (vec (sort xs))
          n (count sorted)
          mid (quot n 2)]
      (if (odd? n)
        (nth sorted mid)
        (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2)))))

(defn elapsed-minutes [started-at ended-at]
  (max (/ (- ended-at started-at) 60000) (/ 1 60000)))

(defn wpm [characters started-at ended-at]
  (/ (/ characters 5) (elapsed-minutes started-at ended-at)))

(defn accuracy [{:keys [correct-keys total-keys]}]
  (if (pos? (or total-keys 0))
    (/ (or correct-keys 0) total-keys)
    1))

(defn standard-deviation [xs]
  (if (< (count xs) 2)
    0
    (let [avg (/ (reduce + xs) (count xs))
          variance (/ (reduce + (map #(let [d (- % avg)] (* d d)) xs)) (count xs))]
      (js/Math.sqrt variance))))

(defn rhythm-consistency [latencies]
  (if (< (count latencies) 2)
    1
    (let [avg (/ (reduce + latencies) (count latencies))]
      (if (zero? avg)
        1
        (max 0 (min 1 (- 1 (/ (standard-deviation latencies) avg))))))))

(defn sustained-wpm [events ended-at]
  (let [window-start (- ended-at 10000)
        accepted (filter #(and (:accepted? %) (>= (:at %) window-start)) events)
        first-at (or (:at (first accepted)) window-start)
        duration (max 1000 (- ended-at first-at))]
    (if (seq accepted)
      (wpm (count accepted) 0 duration)
      0)))

(defn summarize
  ([session] (summarize session (or (:completed-at session) (:last-input-at session) (:started-at session))))
  ([session now]
   (let [latencies (or (:latencies session) [])
         started (or (:started-at session) now)
         ended (or (:completed-at session) now)
         boundary (:boundary-pauses session)]
     {:completed? (= :complete (:status session))
      :characters (:cursor session)
      :elapsed-ms (max 0 (- ended started))
      :wpm (wpm (:cursor session) started ended)
      :sustained-wpm (sustained-wpm (:events session) ended)
      :accuracy (accuracy session)
      :errors (:errors session)
      :corrections (:corrections session)
      :longest-hesitation-ms (reduce max 0 latencies)
      :rhythm-consistency (rhythm-consistency latencies)
      :word-boundary-pause-ms (or (median (:word boundary)) 0)
      :chunk-boundary-pause-ms (or (median (:chunk boundary)) 0)
      :within-chunk-pause-ms (or (median (:within boundary)) 0)
      :stall-count (count (filter #(>= % 1500) latencies))})))
