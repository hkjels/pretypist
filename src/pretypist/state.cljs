(ns pretypist.state
  (:require [pretypist.corpus.selector :as selector]
            [pretypist.landing :as landing]
            [pretypist.layout.schema :as layout-schema]
            [pretypist.layout.theme :as layout-theme]
            [pretypist.onboarding :as onboarding]
            [pretypist.typing.adaptive :as adaptive]
            [pretypist.typing.chunks :as chunks]
            [pretypist.typing.gesture :as gesture]))

(def persistence-version 2)
(def appearance-options #{:system :dark :light})

(def default-settings
  {:version persistence-version
   :language :en
   :mode :flow
   :appearance :system
   :adaptive? true
   :manual-difficulty :automatic
   :run-length :medium
   :ambient-intensity 0.42
   :glow-intensity 0.72
   :layer-legends? true
   :reduced-motion? false
   :active-layout "modified-engram"})

(def layer-targets
  [{:character "æ" :layer :num :key-id :k12 :label "NUM + Æ"}
   {:character "ø" :layer :num :key-id :k02 :label "NUM + Ø"}
   {:character "å" :layer :num :key-id :k22 :label "NUM + Å"}
   {:character "A" :layer :base :key-id :k13 :label "one-shot Shift + A"}
   {:character "," :layer :base :key-id :k14 :label "double-tap period key"}
   {:character "." :layer :base :key-id :k14 :label "tap period key"}
   {:character "'" :layer :base :key-id :k04 :label "tap quote key"}
   {:character "\"" :layer :sym :key-id :k05 :label "one-shot SYM + quote"}
   {:character "?" :layer :sym :key-id :k07 :label "one-shot SYM + ?"}
   {:character "@" :layer :sym :key-id :k04 :label "one-shot SYM + @"}])

(defn initial-state []
  {:app {:status :loading
         :webgl :unknown
         :focused? false
         :now 0
         ;; Wall-clock time is deliberately separate from the monotonic clock
         ;; used for typing latency. Weekly continuity cannot be reconstructed
         ;; from performance.now timestamps after a browser restart.
         :wall-now 0}
   :settings default-settings
   :pwa {:installable? false :installed? false :offline-ready? false :update-ready? false}
   :progress (adaptive/initial-progress)
   :corpora {:en [] :nb []}
   :layout {:status :loading
            :active nil
            :installed [{:id "modified-engram" :name "Modified Engram" :builtin? true}]
            :error nil}
   :theme {:system-dark? true
           :resolved (layout-theme/resolve-theme {} :system true)}
   :display {:wpm-history []}
   :recent-summaries []
   :landing (landing/initial-state)
   :onboarding (onboarding/initial-state)
   :session nil
   :layer-trainer {:stage :explore
                   :active-layer :base
                   :target-index 0
                   :last-trained-target nil
                   :transfer-offered? false
                   :hint-visible? false
                   :transfer? false}
   :ui {:settings-open? false
        :composing? false
        :reset-confirmation? false
        :layout-import-preview nil
        :gesture-playback nil
        :notice nil
        :validation-errors []}})

(defn resolve-active-theme [state]
  (assoc-in state [:theme :resolved]
            (layout-theme/resolve-theme
             (get-in state [:layout :active])
             (get-in state [:settings :appearance] :system)
             (get-in state [:theme :system-dark?] true))))

(defn- target-from-stroke [layout character]
  (let [stroke (layout-schema/stroke-for layout character)
        layers (gesture/stroke-layers layout stroke)
        key-id (->> stroke
                    reverse
                    (some (fn [[operation candidate]]
                            (when (and (keyword? candidate)
                                       (#{:tap :double-tap :double-hold :hold} operation))
                              candidate))))]
    {:character character
     :layer (or (first layers) :base)
     :key-id key-id
     :label character}))

(defn training-targets
  "Resolves familiar course targets against the active package, then fills
  the course from that package's own character-to-stroke index. This keeps an
  imported layout from being asked for gestures it never declared."
  [state]
  (let [layout (get-in state [:layout :active])
        preferred (->> layer-targets
                       (filter #(seq (layout-schema/stroke-for layout (:character %))))
                       (mapv (fn [target]
                               (merge target
                                      (target-from-stroke layout (:character target))))))
        preferred-characters (set (map :character preferred))
        extras (->> (:strokes layout)
                    (remove (fn [[character stroke]]
                              (or (contains? preferred-characters character)
                                  (not (string? character))
                                  (not= 1 (count character))
                                  (empty? stroke))))
                    (sort-by (fn [[character stroke]]
                               [(if (> (count stroke) 1) 0 1) character]))
                    (mapv (fn [[character _]]
                            (target-from-stroke layout character))))
        targets (vec (concat preferred extras))]
    (if (seq targets) targets layer-targets)))

(defn onboarding-targets [state]
  (vec (take onboarding/required-gestures (cycle (training-targets state)))))

(defn current-target [state]
  (let [targets (training-targets state)]
    (nth targets (mod (get-in state [:layer-trainer :target-index]) (count targets)))))

(defn transfer-target [state]
  (or (get-in state [:layer-trainer :last-trained-target])
      (current-target state)))

(defn scene-presentation [state]
  (let [mode (get-in state [:settings :mode])
        status (get-in state [:session :status])
        settings-open? (get-in state [:ui :settings-open?])
        layer-transfer? (get-in state [:layer-trainer :transfer?])]
    (cond
      (landing/active? (:landing state)) :landing
      settings-open? :menu
      (and (onboarding/active? (:onboarding state))
           (= :layers (get-in state [:onboarding :step]))
           (#{:intro :gestures :transfer-ready} (get-in state [:onboarding :phase]))) :layers
      (and (= mode :layers) (not layer-transfer?)) :layers
      (= status :running) :practice
      :else :menu)))

(defn layer-orbit-enabled? [state]
  (and (not (landing/active? (:landing state)))
       (not (onboarding/active? (:onboarding state)))
       (= :layers (get-in state [:settings :mode]))
       (= :explore (get-in state [:layer-trainer :stage]))
       (not (get-in state [:layer-trainer :transfer?]))
       (not (get-in state [:ui :settings-open?]))))

(defn ready? [state]
  (and (= :ready (get-in state [:layout :status]))
       (seq (get-in state [:corpora :en]))
       (seq (get-in state [:corpora :nb]))))

(defn passages [state]
  (get-in state [:corpora (get-in state [:settings :language])]))

(defn trim-passage [state passage]
  (when passage
    (let [length-key (get-in state [:settings :run-length])
          chunk-limit ({:short 3 :medium 5 :long 99} length-key 5)
          chunks (:chunks passage)
          sentence-end? #(boolean (re-find #"[.!?][\"”']?$" %))
          before-limit (keep-indexed (fn [index chunk]
                                       (when (and (< index chunk-limit)
                                                  (sentence-end? chunk))
                                         index))
                                     chunks)
          after-limit (keep-indexed (fn [index chunk]
                                      (when (and (>= index chunk-limit)
                                                 (sentence-end? chunk))
                                        index))
                                    chunks)
          last-before (last before-limit)
          end-index (cond
                      (<= (count chunks) chunk-limit) (count chunks)
                      (and last-before (>= last-before 1)) (inc last-before)
                      (seq after-limit) (inc (first after-limit))
                      :else chunk-limit)]
      (assoc passage :chunks (vec (take end-index chunks))))))

(defn- selection-context [state prescription]
  (let [mode (or (:mode prescription) (get-in state [:settings :mode]))]
    {:mode mode
     :difficulty (or (:difficulty prescription)
                     (adaptive/effective-difficulty
                      (:progress state) mode
                      (get-in state [:settings :manual-difficulty])))
     :prescription prescription
     :recent-summaries (:recent-summaries state)
     :layout-id (get-in state [:layout :active :package/id])
     :current-passage-id (get-in state [:session :passage-id])}))

(defn select-passage-prescription
  ([state] (select-passage-prescription state (get-in state [:progress :next-prescription])))
  ([state prescription]
   (selector/select-passage (keep #(trim-passage state %) (passages state))
                             (selection-context state prescription))))

(defn select-passage [state]
  (when-let [selection (select-passage-prescription state)]
    (let [mode (get-in state [:settings :mode])
          difficulty (adaptive/effective-difficulty
                      (:progress state) mode
                      (get-in state [:settings :manual-difficulty]))
          passage (cond-> (:passage selection)
                    (= mode :chunks)
                    (update :chunks chunks/split-for-training (:max-words difficulty)))]
      (assoc passage :selection (dissoc selection :passage)))))

(defn select-consolidation-passage [state prescription]
  (when-let [selection (select-passage-prescription state prescription)]
    (let [passage (cond-> (:passage selection)
                    (= :chunks (:mode prescription))
                    (update :chunks chunks/split-for-training
                            (get-in prescription [:difficulty :max-words] 2)))]
      (assoc passage :selection (dissoc selection :passage)))))
