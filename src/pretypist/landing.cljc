(ns pretypist.landing)

(def version 1)

(def chapters
  [:read-ahead :movement :rhythm :layout :begin])

(def chapter-layers
  {:read-ahead :base
   :movement :sym
   :rhythm :num
   :layout :sym
   :begin :base})

(defn initial-state []
  {:version version
   :status :active
   :chapter :read-ahead
   :seen? false
   :return-to :onboarding})

(defn completed-state []
  {:version version
   :status :complete
   :chapter :begin
   :seen? true
   :return-to :trainer})

(defn active? [landing]
  (= :active (:status landing)))

(defn open [landing return-to]
  (-> (merge (initial-state) landing)
      (assoc :status :active
             :chapter :read-ahead
             :return-to return-to)))

(defn enter-chapter [landing chapter]
  (if (contains? (set chapters) chapter)
    (assoc landing :chapter chapter)
    landing))

(defn finish [landing]
  (assoc landing :status :complete :chapter :begin :seen? true))

(defn migrate [value existing-user?]
  (cond
    (= version (:version value))
    (let [normalized (merge (initial-state) value)]
      (cond-> normalized
        (not (contains? (set chapters) (:chapter normalized)))
        (assoc :chapter :read-ahead)

        (not (#{:active :complete} (:status normalized)))
        (assoc :status :active)

        (not (#{:onboarding :trainer} (:return-to normalized)))
        (assoc :return-to (if (:seen? normalized) :trainer :onboarding))))

    existing-user? (completed-state)
    :else (initial-state)))

(defn chapter-index [chapter]
  (or (first (keep-indexed (fn [index candidate]
                             (when (= chapter candidate) index))
                           chapters))
      0))

(defn active-layer [landing]
  (get chapter-layers (:chapter landing) :base))

(defn scroll-progress
  "Interpolate actual chapter offsets, including chapters taller than a phone
  viewport. Layout measurements stay in the browser effect, not app state."
  [offsets scroll-y]
  (let [offsets (vec offsets)
        last-index (max 0 (dec (count offsets)))
        index (or (last (keep-indexed #(when (<= %2 scroll-y) %1) offsets)) 0)
        start (get offsets index 0)
        end (get offsets (inc index) start)]
    (if (or (= index last-index) (<= end start))
      index
      (+ index (max 0 (min 1 (/ (- scroll-y start) (- end start))))))))
