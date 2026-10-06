(ns pretypist.nexus
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [nexus.core :as nexus]
            [pretypist.app :as app]
            [pretypist.corpus.analysis :as corpus-analysis]
            [pretypist.display.status :as display-status]
            [pretypist.i18n :as i18n]
            [pretypist.landing :as landing]
            [pretypist.layout.package-reader :as package]
            [pretypist.layout.schema :as layout-schema]
            [pretypist.layout.storage :as layout-storage]
            [pretypist.layout.theme :as layout-theme]
            [pretypist.onboarding :as onboarding]
            [pretypist.persistence :as persistence]
            [pretypist.pwa :as pwa]
            [pretypist.scene.controller :as scene]
            [pretypist.state :as state]
            [pretypist.typing.adaptive :as adaptive]
            [pretypist.typing.analysis :as analysis]
            [pretypist.typing.chunks :as chunks]
            [pretypist.typing.gesture :as gesture]
            [pretypist.typing.input :as input]
            [pretypist.typing.metrics :as metrics]
            [pretypist.ui :as ui]
            [replicant.dom :as replicant]))

(defn- now [] (js/performance.now))

(defn- idle-session [state now]
  (when-let [passage (state/select-passage state)]
    (assoc (input/new-session passage (get-in state [:settings :mode]) now)
           :run-kind :practice
           :status :idle
           :started-at nil
           :last-input-at nil)))

(defn render! [system]
  (when-let [root @(:root system)]
    (let [snapshot (app/snapshot system)
          language (get-in snapshot [:settings :language])]
      (replicant/render root (i18n/localize language (ui/app snapshot)))
      (when-let [meta (js/document.querySelector "meta[name='theme-color']")]
        (let [color (get-in snapshot [:theme :resolved :tokens :background] "#090a0f")]
          (when (not= color (.getAttribute meta "content"))
            (.setAttribute meta "content" color)))))))

(defn- schedule-render! [system]
  ;; Replicant lifecycle hooks dispatch while a render is still unwinding. A
  ;; single microtask keeps those actions data-driven without nesting renders,
  ;; and coalesces all state changes made by one Nexus dispatch.
  (when (compare-and-set! (:render-scheduled? system) false true)
    (js/queueMicrotask
     (fn []
       (reset! (:render-scheduled? system) false)
       (render! system)))))

(defn- set-state! [_ctx system new-state]
  (reset! (:state system) new-state))

(defn- assoc-state! [_ctx system path value]
  (swap! (:state system) assoc-in path value))

(defn boot-start [_state]
  [[:persistence/load]
   [:corpora/load]
   [:appearance/watch-system]
   [:pwa/start]
   [:clock/start]])

(defn persistence-loaded [state local db prefers-reduced? prefers-dark? wall-now]
  (let [loaded-settings (cond-> (:settings local)
                          (and (not (:stored-settings? local)) prefers-reduced?)
                          (assoc :reduced-motion? true))
        installed (into [{:id "modified-engram" :name "Modified Engram" :builtin? true}]
                        (map #(assoc % :builtin? false) (:layouts local)))
        next-state (-> state
                       (assoc :settings loaded-settings)
                       (assoc-in [:app :wall-now] wall-now)
                       (assoc-in [:theme :system-dark?] prefers-dark?)
                       (assoc :progress (or (:progress local) (adaptive/initial-progress)))
                       (assoc :recent-summaries (:summaries local))
                       (assoc :landing (:landing local))
                       (assoc :onboarding (:onboarding local))
                       (assoc-in [:layout :installed] installed)
                       state/resolve-active-theme)]
    [[:state/set next-state]
     [:database/set db]
     [:dom/set-language (:language loaded-settings)]
     [:layout/load-selected (get-in next-state [:settings :active-layout])]]))

(defn corpora-loaded [state corpora]
  [[:state/set (assoc state :corpora corpora)]
   [:boot/maybe-ready]])

(defn layout-loaded [state package-result builtin?]
  (let [layout (:layout package-result)
        id (:package/id layout)
        metadata {:id id :name (:package/name layout) :revision (:package/revision layout) :builtin? builtin?}
        installed (if (some #(= id (:id %)) (get-in state [:layout :installed]))
                    (mapv #(if (= id (:id %)) (merge % metadata) %) (get-in state [:layout :installed]))
                    (conj (get-in state [:layout :installed]) metadata))
        next-state (cond-> (-> state
                               (assoc-in [:layout :status] :ready)
                               (assoc-in [:layout :error] nil)
                               (assoc-in [:layout :active] layout)
                               (assoc-in [:layout :installed] installed)
                               (assoc-in [:settings :active-layout] id)
                               (assoc-in [:ui :validation-errors] [])
                               (assoc-in [:ui :layout-import-preview] nil)
                               state/resolve-active-theme)
                     (not builtin?)
                     (assoc-in [:ui :notice] [:t :layout/imported {:name (:package/name layout)}]))]
    [[:state/set next-state]
     [:layout/cache-active package-result]
     [:layout/clear-pending]
     [:scene/load-active]
     [:persistence/save-settings]
     [:persistence/save-layouts]
     [:boot/maybe-ready]]))

(defn layout-failed [state errors]
  [[:state/set
    (-> state
        (assoc-in [:layout :status] :error)
        (assoc-in [:layout :error] :invalid-package)
        (assoc-in [:ui :layout-import-preview] nil)
        (assoc-in [:ui :validation-errors] (vec errors)))]
   [:layout/clear-pending]])

(defn layout-preview [layout]
  {:id (:package/id layout)
   :name (:package/name layout)
   :revision (:package/revision layout)
   :schemes (layout-theme/preview layout)})

(defn layout-import-preview-ready [state preview]
  [[:state/set (-> state
                   (assoc-in [:layout :status] :preview)
                   (assoc-in [:layout :error] nil)
                   (assoc-in [:ui :layout-import-preview] preview)
                   (assoc-in [:ui :validation-errors] []))]])

(defn layout-import-confirmed [state]
  (if (get-in state [:ui :layout-import-preview])
    [[:state/set (assoc-in state [:layout :status] :installing)]
     [:layout/install-pending]]
    []))

(defn layout-import-cancelled [state]
  [[:state/set (-> state
                   (assoc-in [:layout :status] :ready)
                   (assoc-in [:ui :layout-import-preview] nil)
                   (assoc-in [:ui :validation-errors] []))]
   [:layout/clear-pending]])

(defn system-scheme-changed [state dark?]
  (let [next-state (-> state
                       (assoc-in [:theme :system-dark?] dark?)
                       state/resolve-active-theme)]
    [[:state/set next-state]
     [:scene/sync]]))

(defn boot-maybe-ready [state]
  (if (and (state/ready? state) (= :loading (get-in state [:app :status])))
    [[:state/set (-> state
                     (assoc-in [:app :status] :ready)
                     (assoc :session (when-not (onboarding/active? (:onboarding state))
                                       (idle-session state (get-in state [:app :now])))))]
     [:scene/sync]]
    []))

(defn onboarding-start [state timestamp]
  [[:state/set (-> state
                   (assoc :session nil)
                   (assoc-in [:settings :mode] :flow)
                   (assoc :onboarding (onboarding/start (:onboarding state) timestamp)))]
   [:persistence/save-onboarding]
   [:persistence/save-settings]
   [:scene/sync]])

(defn onboarding-target-score [passage target-characters]
  (let [text (chunks/passage-text passage)]
    (reduce (fn [score character]
              (if (str/includes? text character) (inc score) score))
            0
            target-characters)))

(defn- onboarding-passage [state step]
  (let [mode (case step :rhythm :chunks :layers :layers :flow)
        selection-state (-> state
                            (assoc-in [:settings :mode] mode)
                            (assoc-in [:settings :run-length] :short)
                            (assoc-in [:progress :next-prescription] nil))
        target-characters (mapv :character (state/onboarding-targets state))
        target-score #(onboarding-target-score % target-characters)
        passage (if (= step :layers)
                  (->> (state/passages selection-state)
                       (keep #(state/trim-passage selection-state %))
                       (reduce (fn [best candidate]
                                 (if (or (nil? best)
                                         (> (target-score candidate)
                                            (target-score best)))
                                   candidate
                                   best))
                               nil))
                  (state/select-passage selection-state))]
    (cond-> passage
      (= step :rhythm) (update :chunks chunks/split-for-training 4)
      passage (assoc :selection nil))))

(defn onboarding-session-start [state timestamp]
  (let [step (get-in state [:onboarding :step])
        mode (case step :rhythm :chunks :layers :layers :flow)
        difficulty (case step
                     :rhythm {:boundary-opacity 1 :max-words 4 :step 0}
                     :layers {:weak-transition-frequency 0.15}
                     {:punctuation-density 0})]
    (if-let [passage (onboarding-passage state step)]
      (let [session (assoc (input/new-session passage mode timestamp)
                           :run-kind :calibration
                           :calibration-step step
                           :calibration-difficulty difficulty)
            next-state (-> state
                           (assoc :session session)
                           (assoc-in [:settings :mode] mode)
                           (assoc :onboarding (onboarding/start-session (:onboarding state)))
                           (assoc-in [:display :wpm-history] (display-status/empty-wpm-history))
                           (assoc-in [:app :focused?] true)
                           (assoc-in [:ui :gesture-playback] nil)
                           (assoc-in [:layer-trainer :transfer?] (= step :layers)))]
        [[:state/set next-state]
         [:persistence/save-onboarding]
         [:persistence/save-settings]
         [:scene/cancel-gesture]
         [:scene/sync]
         [:dom/focus-input]])
      [])))

(defn onboarding-gestures-start [state _timestamp]
  (let [target (first state/layer-targets)]
    [[:state/set (-> state
                     (assoc :session nil)
                     (assoc-in [:settings :mode] :layers)
                     (assoc :onboarding (onboarding/begin-gestures (:onboarding state)))
                     (assoc-in [:layer-trainer :stage] :guided)
                     (assoc-in [:layer-trainer :target-index] 0)
                     (assoc-in [:layer-trainer :active-layer] (:layer target))
                     (assoc-in [:layer-trainer :transfer?] false)
                     (assoc-in [:layer-trainer :hint-visible?] false))]
     [:persistence/save-onboarding]
     [:persistence/save-settings]
     [:scene/sync]
     [:dom/focus-input]]))

(defn onboarding-continue [state]
  [[:state/set (-> state
                   (assoc :session nil)
                   (assoc :onboarding (onboarding/continue (:onboarding state))))]
   [:persistence/save-onboarding]
   [:scene/sync]])

(defn onboarding-finish [state timestamp]
  (let [next-state (-> state
                       (assoc-in [:settings :mode] :flow)
                       (assoc-in [:layer-trainer :transfer?] false)
                       (assoc :onboarding (onboarding/finish (:onboarding state) timestamp)))
        next-state (assoc next-state :session (idle-session next-state timestamp))]
    [[:state/set next-state]
     [:persistence/save-onboarding]
     [:persistence/save-settings]
     [:scene/sync]]))

(defn onboarding-skip [state timestamp]
  (let [next-state (-> state
                       (assoc-in [:settings :mode] :flow)
                       (assoc-in [:layer-trainer :transfer?] false)
                       (assoc :onboarding (onboarding/skip (:onboarding state) timestamp)))
        next-state (assoc next-state :session (idle-session next-state timestamp))]
    [[:state/set next-state]
     [:persistence/save-onboarding]
     [:persistence/save-settings]
     [:scene/sync]]))

(defn mode-select [state mode]
  (let [next-state (-> state
                       (assoc-in [:settings :mode] mode)
                       (assoc-in [:layer-trainer :transfer?] false)
                       (assoc-in [:display :wpm-history] [])
                       (assoc-in [:ui :gesture-playback] nil)
                       (assoc :session nil))
        next-state (if (= mode :layers)
                     next-state
                     (assoc next-state :session (idle-session next-state (get-in state [:app :now]))))]
    [[:state/set next-state]
     [:scene/cancel-gesture]
     [:persistence/save-settings]
     [:scene/sync]
     [:dom/focus-input]]))

(defn session-start [state timestamp]
  (if-let [passage (or (when (= :idle (get-in state [:session :status]))
                         (select-keys (:session state) [:passage-id :title :chunks :selection]))
                       (state/select-passage state))]
    [[:state/set (-> state
                     (assoc :session (assoc (input/new-session (assoc passage :id (:passage-id passage))
                                                               (get-in state [:settings :mode]) timestamp)
                                            :run-kind :practice))
                     (assoc-in [:display :wpm-history] (display-status/empty-wpm-history))
                     (assoc-in [:app :focused?] true))]
     [:scene/sync]
     [:dom/focus-input]]
    []))

(defn session-new [state]
  (let [mode (get-in state [:settings :mode])
        next-state (-> state
                       (assoc-in [:layer-trainer :transfer?] false)
                       (assoc-in [:ui :gesture-playback] nil)
                       (assoc-in [:display :wpm-history] [])
                       (assoc :session (when-not (= mode :layers)
                                         (idle-session state (get-in state [:app :now])))))]
    [[:state/set next-state]
     [:scene/cancel-gesture]
     [:scene/sync]]))

(defn session-pause [state timestamp]
  (if (= :running (get-in state [:session :status]))
    [[:state/set (-> state
                     (update :session input/pause :escape timestamp)
                     (assoc-in [:app :focused?] false))]
     [:scene/sync]]
    []))

(defn session-resume [state timestamp]
  [[:state/set (-> state
                   (update :session input/resume timestamp)
                   (assoc-in [:app :focused?] true))]
   [:scene/sync]
   [:dom/focus-input]])

(defn- previous-summary [state mode]
  (some #(when (= mode (:mode %)) %)
        (reverse (:recent-summaries state))))

(defn gesture-replay [state character timestamp]
  (if-let [timeline (gesture/timeline-for (get-in state [:layout :active]) character)]
    (let [playback-id (str "gesture-" (or (:id (:session state)) "layout") "-" timestamp)
          playback {:id playback-id
                    :character character
                    :source :inferred
                    :timing :illustrative
                    :duration-ms (:duration-ms timeline)}]
      [[:state/set (assoc-in state [:ui :gesture-playback] playback)]
       [:scene/play-gesture timeline]
       [:timer/gesture-finished playback-id (:duration-ms timeline)]])
    []))

(defn gesture-finished [state playback-id]
  (if (= playback-id (get-in state [:ui :gesture-playback :id]))
    [[:state/set (assoc-in state [:ui :gesture-playback] nil)]]
    []))

(defn- complete-session-state [state session completed-on]
  (let [summary (metrics/summarize session)
        consolidation? (= :consolidation (:run-kind session))
        analysis-result (analysis/analyze-session
                         {:session session
                          :summary summary
                          :layout (get-in state [:layout :active])
                          :profile (get-in state [:progress :profile])
                          :previous-summary (previous-summary state (:mode session))
                          :language (get-in state [:settings :language])
                          :difficulty (adaptive/effective-difficulty
                                       (:progress state)
                                       (:mode session)
                                       (get-in state [:settings :manual-difficulty]))})
        analysis-result (cond-> analysis-result
                          consolidation? (assoc :prescription nil))
        progress (if consolidation?
                   (adaptive/record-consolidation-run (:progress state) summary)
                   (adaptive/record-run (:progress state)
                                        (:mode session)
                                        summary
                                        (get-in state [:settings :adaptive?])))
        progress (assoc progress
                        :profile (:profile analysis-result)
                        :next-prescription (:prescription analysis-result))
        summary-record (assoc summary
                              :id (:id session)
                              :mode (:mode session)
                              :passage-id (:passage-id session)
                              :run-kind (or (:run-kind session) :practice)
                              :focus (select-keys (:focus session) [:kind :target :reason])
                              :selection (select-keys (:selection session)
                                                      [:active-dimension :score])
                              :completed-at (:completed-at session)
                              :completed-on completed-on)]
    (cond-> (-> state
                (assoc-in [:app :wall-now] (or completed-on (get-in state [:app :wall-now])))
                (assoc :progress progress)
                (assoc :session (assoc session :summary summary :analysis analysis-result))
                (update :recent-summaries #(vec (take-last 20 (conj (or % []) summary-record)))))
      (and (= :calibration (:run-kind session))
           (onboarding/active? (:onboarding state)))
      (assoc :onboarding
             (onboarding/record-session
              (:onboarding state)
              (assoc summary :completed-at (:completed-at session)
                             :chart (:chart analysis-result)))))))

(defn session-consolidate [state timestamp]
  (let [prescription (get-in state [:progress :next-prescription])]
    (if-let [passage (and prescription
                          (state/select-consolidation-passage state prescription))]
      (let [mode (:mode prescription)
            session (assoc (input/new-session passage mode timestamp)
                           :run-kind :consolidation
                           :focus prescription)
            next-state (-> state
                           (assoc-in [:settings :mode] mode)
                           (assoc :session session)
                           (assoc-in [:display :wpm-history] (display-status/empty-wpm-history))
                           (assoc-in [:app :focused?] true)
                           (assoc-in [:ui :gesture-playback] nil)
                           (assoc-in [:layer-trainer :transfer?] (= mode :layers)))]
        [[:state/set next-state]
         [:scene/cancel-gesture]
         [:scene/sync]
         [:dom/focus-input]])
      [])))

(defn typing-input
  ([state text timestamp node]
   (typing-input state text timestamp nil node))
  ([state text timestamp wall-time node]
  (let [text (input/normalize-text text)
        layer-practice? (and (= :layers (get-in state [:settings :mode]))
                             (not (get-in state [:layer-trainer :transfer?])))
        onboarding-gesture? (and layer-practice?
                                 (onboarding/active? (:onboarding state))
                                 (= :layers (get-in state [:onboarding :step]))
                                 (= :gestures (get-in state [:onboarding :phase])))
        character (first (js/Array.from (or text "")))]
    (cond
      (empty? (or text "")) [[:input/clear node]]

      layer-practice?
      (let [target (state/current-target state)
            correct? (= character (:character target))
            next-index (if correct?
                         (inc (get-in state [:layer-trainer :target-index]))
                         (get-in state [:layer-trainer :target-index]))
            next-target (nth state/layer-targets (mod next-index (count state/layer-targets)))
            next-state (cond-> state
                         correct? (-> (assoc-in [:layer-trainer :target-index] next-index)
                                      (assoc-in [:layer-trainer :last-trained-target] target)
                                      (assoc-in [:layer-trainer :transfer-offered?] true)
                                      (assoc-in [:layer-trainer :active-layer] (:layer next-target))
                                      (assoc-in [:layer-trainer :hint-visible?] false)
                                      (assoc-in [:ui :notice] nil))
                         (and correct? onboarding-gesture?)
                         (assoc :onboarding (onboarding/complete-gesture (:onboarding state)))
                         (not correct?) (assoc-in [:ui :notice] :layers/try-again))]
        (cond-> [[:state/set next-state]
                 [:input/clear node]
                 [:layers/sync-hint]
                 [:scene/sync]
                 [:dom/focus-input]]
          (and correct? onboarding-gesture?) (conj [:persistence/save-onboarding])
          correct? (conj [:scene/pulse-stroke character])
          (and (not correct?)
               (not= :recall (get-in state [:layer-trainer :stage])))
          (conj [:gesture/replay (:character target) timestamp])))

      (= :running (get-in state [:session :status]))
      (let [previous-session (:session state)
            expected (input/expected-character previous-session)
            session (input/enter-text previous-session text timestamp)
            completed? (= :complete (:status session))
            accepted? (> (:cursor session) (:cursor previous-session))
            new-error? (> (:errors session) (:errors previous-session))
            error-timeline (when new-error?
                             (gesture/timeline-for (get-in state [:layout :active]) expected))
            next-state (if completed?
                         (complete-session-state state session wall-time)
                         (assoc state :session session))]
        (cond-> [[:state/set next-state]
                 [:input/clear node]]
          accepted? (conj [:scene/pulse-stroke character])
          (and new-error? (gesture/complex-timeline? error-timeline))
          (conj [:gesture/replay expected timestamp])
          completed? (conj [:scene/sync]
                           [:persistence/save-progress]
                           [:persistence/save-summaries])
          (and completed? (= :calibration (:run-kind session)))
          (conj [:persistence/save-onboarding])
          (not completed?) (conj [:dom/focus-input])))

      :else [[:input/clear node]]))))

(def accepted-input-types
  ;; nil supports programmatic/older browser input events whose inserted delta
  ;; is still available. Every richer edit intent fails closed.
  #{nil "" "insertText" "insertCompositionText" "insertFromComposition"})

(defn accepted-input-intent? [input-type]
  (or (contains? accepted-input-types input-type)
      (= input-type "deleteContentBackward")))

(defn typing-before-input [state input-type composing? event]
  (when (and (not composing?) (not (accepted-input-intent? input-type)))
    [[:dom/prevent-default event]
     [:state/set (assoc-in state [:ui :notice] :notice/blocked-edit)]]))

(defn typing-blocked-edit [state _kind event node]
  [[:dom/prevent-default event]
   [:state/set (assoc-in state [:ui :notice] :notice/blocked-edit)]
   [:input/clear node]
   [:dom/focus-input]])

(defn typing-dom-input [state text input-type composing? timestamp wall-time node]
  (cond
    composing? []

    (= input-type "deleteContentBackward")
    (if (= :running (get-in state [:session :status]))
      [[:state/set (-> state
                       (update :session input/backspace timestamp)
                       (assoc-in [:ui :notice] nil))]
       [:input/clear node]
       [:dom/focus-input]]
      [[:input/clear node]])

    (contains? accepted-input-types input-type)
    (typing-input (assoc-in state [:ui :notice] nil)
                  text timestamp wall-time node)

    :else
    (typing-blocked-edit state input-type nil node)))

(defn typing-key-down
  ([state key code modifiers timestamp event node]
   (typing-key-down state key code modifiers false timestamp nil event node))
  ([state key code modifiers composing? timestamp wall-time event node]
  (let [{:keys [meta? control? alt?]} modifiers
        shortcut? (or meta? control? alt?)
        key-record (layout-schema/key-by-code (get-in state [:layout :active]) code)
        accepts-input? (or (= :running (get-in state [:session :status]))
                           (and (= :layers (get-in state [:settings :mode]))
                                (not (get-in state [:layer-trainer :transfer?]))))
        observable-text-key? (and key-record
                                  (= :running (get-in state [:session :status]))
                                  (or (= code "Space") (= 1 (count key))))
        observed-state (if observable-text-key?
                         (update state :session input/observe-key (:id key-record) code)
                         state)]
    (cond
      composing? []
      shortcut? []
      (= key "Escape")
      [[:dom/prevent-default event]
       [:session/pause timestamp]]
      (and (= key "Backspace") (= :running (get-in state [:session :status])))
      [[:dom/prevent-default event]
       [:state/set (update state :session input/backspace timestamp)]
       [:scene/press (:id key-record)]]
      ;; Some keyboard/IME combinations defer or coalesce the textarea input
      ;; event at a word boundary. Space is unambiguous, so consume its
      ;; physical keydown directly and suppress the duplicate native input.
      (and (= code "Space") accepts-input?)
      (into [[:dom/prevent-default event]
             [:scene/press (:id key-record)]]
            (typing-input observed-state " " timestamp wall-time node))
      key-record (cond-> [[:scene/press (:id key-record)]]
                   observable-text-key? (conj [:state/set observed-state]))
      :else []))))

(defn typing-key-up [state code _modifiers]
  (if-let [key-record (layout-schema/key-by-code (get-in state [:layout :active]) code)]
    [[:scene/release (:id key-record)]]
    []))

(defn focus-lost [state timestamp]
  (if (and (= :running (get-in state [:session :status]))
           (not (get-in state [:ui :settings-open?])))
    [[:state/set (-> state
                     (update :session input/pause :focus timestamp)
                     (assoc-in [:app :focused?] false))]
     [:scene/sync]]
    []))

(defn landing-open [state]
  (let [timestamp (get-in state [:app :now])
        next-state (cond-> (-> state
                               (assoc :landing (landing/open (:landing state) :trainer))
                               (assoc-in [:ui :settings-open?] false)
                               (assoc-in [:ui :layout-import-preview] nil)
                               (assoc-in [:ui :validation-errors] []))
                     (= :running (get-in state [:session :status]))
                     (update :session input/pause :landing timestamp))]
    [[:state/set next-state]
     [:layout/clear-pending]
     [:dom/scroll-top]
     [:scene/sync]]))

(defn landing-continue [state]
  (let [return-to (get-in state [:landing :return-to])
        next-state (-> state
                       (update :landing landing/finish)
                       (assoc-in [:ui :layout-import-preview] nil)
                       (assoc-in [:ui :validation-errors] []))
        next-state (if (and (= return-to :onboarding)
                            (not (onboarding/active? (:onboarding next-state))))
                     (assoc next-state :onboarding (onboarding/initial-state))
                     next-state)]
    [[:state/set next-state]
     [:layout/clear-pending]
     [:persistence/save-landing]
     [:dom/scroll-top]
     [:scene/sync]]))

(defn landing-chapter-entered [state chapter]
  (let [next-landing (landing/enter-chapter (:landing state) chapter)]
    (if (= next-landing (:landing state))
      []
      [[:state/set (assoc state :landing next-landing)]
       [:scene/sync]])))

(defn settings-open [state]
  (let [status (get-in state [:session :status])
        next-state (cond-> (assoc-in state [:ui :settings-open?] true)
                     (= status :running)
                     (update :session input/pause :settings (get-in state [:app :now]))

                     (= status :paused)
                     (assoc-in [:session :pause-reason] :settings))]
    [[:state/set next-state]
     [:scene/sync]]))

(defn settings-close [state]
  (let [resume? (= :settings (get-in state [:session :pause-reason]))
        next-state (cond-> (-> state
                              (assoc-in [:ui :settings-open?] false)
                              (assoc-in [:ui :reset-confirmation?] false))
                     resume? (update :session input/resume (get-in state [:app :now])))]
    (cond-> [[:state/set next-state]
             [:dom/focus-settings-button]
             [:scene/sync]]
      resume? (conj [:dom/focus-input]))))

(defn settings-change [state setting value]
  (let [value (if (and (= setting :appearance)
                       (not (contains? state/appearance-options value)))
                :system
                value)
        next-state (assoc-in state [:settings setting] value)
        restart? (contains? #{:language :mode :run-length :manual-difficulty} setting)
        mode (get-in next-state [:settings :mode])
        next-state (cond-> next-state
                     restart? (assoc :session (when-not (= mode :layers)
                                               (idle-session next-state (get-in state [:app :now]))))
                     restart? (assoc-in [:display :wpm-history] [])
                     (= setting :mode) (assoc-in [:layer-trainer :transfer?] false)
                     (= setting :appearance) state/resolve-active-theme)]
    (cond-> [[:state/set next-state]
             [:persistence/save-settings]]
      (= setting :language) (conj [:dom/set-language value])
      true (conj [:scene/sync]))))

(defn layer-selected [state layer]
  [[:state/set (assoc-in state [:layer-trainer :active-layer] layer)]
   [:scene/sync]])

(defn layer-stage-selected [state stage]
  (let [target (state/current-target state)]
    (cond-> [[:state/set (cond-> (-> state
                                     (assoc-in [:layer-trainer :stage] stage)
                                     (assoc-in [:layer-trainer :hint-visible?] false)
                                     (assoc-in [:ui :gesture-playback] nil)
                                     (assoc-in [:layer-trainer :transfer?] false))
                              (not (#{:explore :transfer} stage))
                              (assoc-in [:layer-trainer :active-layer] (:layer target)))]
           [:scene/cancel-gesture]
           [:layers/sync-hint]
           [:scene/sync]]
      (not= stage :explore) (conj [:dom/focus-input]))))

(defn layer-hint-reveal [state target-index]
  (if (and (= :delayed (get-in state [:layer-trainer :stage]))
           (= target-index (get-in state [:layer-trainer :target-index])))
    [[:state/set (assoc-in state [:layer-trainer :hint-visible?] true)]
     [:scene/sync]]
    []))

(defn layer-transfer-start [state timestamp]
  (let [target (state/transfer-target state)
        character (:character target)
        prescription {:id (str "layer-transfer-" timestamp)
                      :kind :character
                      :target character
                      :reason :layer-transition
                      :mode :layers
                      :language (get-in state [:settings :language])
                      :difficulty (adaptive/effective-difficulty
                                   (:progress state) :layers
                                   (get-in state [:settings :manual-difficulty]))
                      :stroke (layout-schema/stroke-for (get-in state [:layout :active]) character)
                      :gesture-source :inferred
                      :copy {:key :analysis/prescription-character
                             :params {:character character}}}]
    (if-let [passage (state/select-consolidation-passage state prescription)]
      [[:state/set (-> state
                       (assoc :session (assoc (input/new-session passage :layers timestamp)
                                              :run-kind :transfer
                                              :focus prescription))
                       (assoc-in [:display :wpm-history] (display-status/empty-wpm-history))
                       (assoc-in [:ui :gesture-playback] nil)
                       (assoc-in [:layer-trainer :transfer-offered?] false)
                       (assoc-in [:layer-trainer :transfer?] true))]
       [:scene/cancel-gesture]
       [:scene/sync]
       [:dom/focus-input]]
      [])))

(def orbit-key-deltas
  {"ArrowLeft" [-0.09 0]
   "ArrowRight" [0.09 0]
   "ArrowUp" [0 -0.09]
   "ArrowDown" [0 0.09]})

(defn scene-orbit-key [state key event]
  (when (state/layer-orbit-enabled? state)
    (cond
      (contains? orbit-key-deltas key)
      (let [[delta-yaw delta-pitch] (get orbit-key-deltas key)]
        [[:dom/prevent-default event]
         [:scene/command :orbit-by! delta-yaw delta-pitch]])

      (= key "Home")
      [[:dom/prevent-default event]
       [:scene/reset-camera]]

      :else [])))

(defn clock-tick [state timestamp]
  (if (= :running (get-in state [:session :status]))
    [[:state/set
      (-> state
          (assoc-in [:app :now] timestamp)
          (update-in [:display :wpm-history]
                     display-status/sample-session-wpm
                     (:session state)
                     timestamp))]
     [:scene/sync]]
    []))

(defn toggle-disclosure [state id]
  (when (contains? #{:telemetry :difficulty :scene :passage-reason} id)
    [[:state/set (update-in state [:ui :disclosures id] not)]]))

(def expansions
  {:boot/start boot-start
   :pwa/changed (fn [state values] [[:state/set (update state :pwa merge values)]])
   :pwa/install (fn [_state] [[:pwa/prompt-install]])
   :pwa/update (fn [state] (when (pwa/update-safe? state) [[:pwa/apply-update]]))
   :persistence/loaded persistence-loaded
   :corpora/loaded corpora-loaded
   :layout/loaded layout-loaded
   :layout/failed layout-failed
   :layout/import-preview-ready layout-import-preview-ready
   :layout/import-confirmed layout-import-confirmed
   :layout/import-cancelled layout-import-cancelled
   :appearance/system-scheme-changed system-scheme-changed
   :boot/maybe-ready boot-maybe-ready
   :landing/open landing-open
   :landing/continue landing-continue
   :landing/chapter-entered landing-chapter-entered
   :landing/mount (fn [_state node] [[:landing/start-scroll node]])
   :landing/unmount (fn [_state] [[:landing/stop-scroll]])
   :onboarding/start onboarding-start
   :onboarding/session-start onboarding-session-start
   :onboarding/gestures-start onboarding-gestures-start
   :onboarding/continue onboarding-continue
   :onboarding/finish onboarding-finish
   :onboarding/skip onboarding-skip
   :mode/select mode-select
   :session/start session-start
   :session/new session-new
   :session/consolidate session-consolidate
   :session/pause session-pause
   :session/resume session-resume
   :gesture/replay gesture-replay
   :gesture/finished gesture-finished
   :typing/input typing-input
   :typing/before-input typing-before-input
   :typing/dom-input typing-dom-input
   :typing/blocked-edit typing-blocked-edit
   :typing/composition-started (fn [state]
                                 [[:state/set (assoc-in state [:ui :composing?] true)]])
   :typing/composition-ended (fn [state]
                               [[:state/set (assoc-in state [:ui :composing?] false)]])
   :typing/key-down typing-key-down
   :typing/key-up typing-key-up
   :typing/focus-gained (fn [state] [[:state/set (assoc-in state [:app :focused?] true)]])
   :typing/focus-lost focus-lost
   :settings/open settings-open
   :ui/toggle-disclosure toggle-disclosure
   :settings/close settings-close
   :settings/key-down (fn [_state key event]
                        (case key
                          "Escape" [[:dom/prevent-default event] [:settings/close]]
                          "Tab" [[:dom/trap-focus event]]
                          []))
   :settings/scrim-click (fn [_state event]
                           (when (= (.-target event) (.-currentTarget event)) [[:settings/close]]))
   :settings/change settings-change
   :completion/key-down (fn [_state key event]
                          (case key
                            "Escape" [[:dom/prevent-default event] [:session/new]]
                            "Tab" [[:dom/trap-focus event]]
                            []))
   :settings/reset-requested (fn [state] [[:state/set (assoc-in state [:ui :reset-confirmation?] true)]])
   :settings/reset-cancelled (fn [state] [[:state/set (assoc-in state [:ui :reset-confirmation?] false)]])
   :progress/reset-confirmed (fn [state]
                               [[:state/set (-> state
                                                (assoc :progress (adaptive/initial-progress))
                                                (assoc :recent-summaries [])
                                                (assoc-in [:ui :reset-confirmation?] false)
                                                (assoc-in [:ui :notice] :notice/progress-reset))]
                                [:persistence/reset-progress]])
   :layout/import-requested (fn [_state] [[:dom/click-id "layout-package-input"]])
   :layout/file-selected (fn [state file input-node]
                           (when file
                             [[:state/set (-> state
                                              (assoc-in [:layout :status] :importing)
                                              (assoc-in [:ui :layout-import-preview] nil)
                                              (assoc-in [:ui :validation-errors] []))]
                              [:input/clear input-node]
                              [:layout/read-file file]]))
   :layout/select (fn [state id]
                    [[:state/set (-> state
                                     (assoc-in [:layout :status] :loading)
                                     (assoc-in [:settings :active-layout] id))]
                     [:layout/load-selected id]])
   :layout/remove (fn [state id]
                    (if (= id (get-in state [:settings :active-layout]))
                      [[:state/set (assoc-in state [:ui :notice] :layout/remove-active)]]
                      (let [installed (filterv (fn [item] (not= id (:id item)))
                                               (get-in state [:layout :installed]))]
                        [[:state/set (assoc-in state [:layout :installed] installed)]
                         [:layout/remove-stored id]
                         [:persistence/save-layouts]])))
   :layers/layer-selected layer-selected
   :layers/stage-selected layer-stage-selected
   :layers/hint-reveal layer-hint-reveal
   :layers/transfer-start layer-transfer-start
   :layers/sync-hint (fn [_state] [[:timer/sync-layer-hint]])
   :scene/reset-camera (fn [_state] [[:scene/command :reset-camera!]])
   :scene/orbit-key scene-orbit-key
   :trainer/background-click (fn [_state event] [[:dom/focus-if-background event]])
   :clock/tick clock-tick
   :scene/ready (fn [state] [[:state/set (assoc-in state [:app :webgl] :ready)]])
   :scene/failed (fn [state message]
                   [[:state/set (-> state
                                    (assoc-in [:app :webgl] :failed)
                                    (assoc-in [:app :webgl-error] message))]])
   :scene/mount (fn [_state node] [[:scene/mount-effect node]])
   :scene/unmount (fn [_state] [[:scene/unmount-effect]])})

(defn- persistence-load! [ctx system]
  (let [local (persistence/load-local)
        prefers-reduced? (boolean (some-> (js/matchMedia "(prefers-reduced-motion: reduce)") .-matches))
        prefers-dark? (boolean (some-> (js/matchMedia "(prefers-color-scheme: dark)") .-matches))]
    (-> (layout-storage/open-database)
        (.then (fn [db]
                 ((:dispatch ctx)
                  [[:persistence/loaded local db prefers-reduced? prefers-dark? (js/Date.now)]])))
        (.catch (fn [_]
                  ;; The built-in package and trainer remain usable in private modes
                  ;; where IndexedDB is unavailable.
                  ((:dispatch ctx)
                   [[:persistence/loaded local nil prefers-reduced? prefers-dark? (js/Date.now)]]))))))

(defn- watch-system-theme! [ctx system]
  (when-let [cleanup @(:system-theme-cleanup system)] (cleanup))
  (let [media (js/matchMedia "(prefers-color-scheme: dark)")
        handler (fn [event]
                  ((:dispatch ctx)
                   [[:appearance/system-scheme-changed (boolean (.-matches event))]]))
        modern? (fn? (.-addEventListener media))]
    (if modern?
      (.addEventListener media "change" handler)
      (.addListener media handler))
    (reset! (:system-theme-cleanup system)
            #(if modern?
               (.removeEventListener media "change" handler)
               (.removeListener media handler)))))

(defn- load-corpora! [ctx _system]
  (-> (js/Promise.all
       #js [(-> (js/fetch "/corpora/en.edn") (.then #(.text %)))
            (-> (js/fetch "/corpora/nb.edn") (.then #(.text %)))
            (-> (js/fetch "/corpora/metadata.edn") (.then #(.text %)))])
      (.then (fn [values]
               (let [en (reader/read-string (aget values 0))
                     nb (reader/read-string (aget values 1))
                     metadata (reader/read-string (aget values 2))]
                 ((:dispatch ctx)
                  [[:corpora/loaded
                    {:en (corpus-analysis/enrich-passages en metadata)
                     :nb (corpus-analysis/enrich-passages nb metadata)
                     :metadata metadata}]]))))
      (.catch (fn [error]
                ((:dispatch ctx) [[:scene/failed (str "Could not load passages: " (.-message error))]])))))

(defn- database-set! [_ctx system db]
  (reset! (:database system) db))

(defn- load-selected! [ctx system id]
  (if (or (nil? id) (= id "modified-engram"))
    (-> (package/fetch-package "/layouts/modified-engram.pretypist")
        (.then (fn [result]
                 ((:dispatch ctx) [(if (:ok? result)
                                     [:layout/loaded result true]
                                     [:layout/failed (:errors result)])])))
        (.catch (fn [error]
                  ((:dispatch ctx)
                   [[:layout/failed
                     [{:path []
                       :code :package-request-failed
                       :message (str "Could not load the selected layout package: "
                                     (.-message error))}]]]))))
    (if-let [db @(:database system)]
      (-> (layout-storage/get-one db id)
          (.then (fn [record]
                   (if record
                     (-> (package/read-package (:zipBuffer record))
                         (.then (fn [result]
                                  ((:dispatch ctx) [(if (:ok? result)
                                                      [:layout/loaded result false]
                                                      [:layout/failed (:errors result)])]))))
                     ((:dispatch ctx) [[:layout/load-selected "modified-engram"]]))))
          (.catch (fn [_] ((:dispatch ctx) [[:layout/load-selected "modified-engram"]]))))
      ((:dispatch ctx) [[:layout/load-selected "modified-engram"]]))))

(defn- read-layout-file! [ctx system file]
  (reset! (:pending-package system) nil)
  (-> (.arrayBuffer file)
      (.then package/read-package)
      (.then (fn [result]
               (if (:ok? result)
                 (do
                   (reset! (:pending-package system) result)
                   ((:dispatch ctx) [[:layout/import-preview-ready
                                      (layout-preview (:layout result))]]))
                 (do
                   (reset! (:pending-package system) nil)
                   ((:dispatch ctx) [[:layout/failed (:errors result)]])))))
      (.catch (fn [error]
                (reset! (:pending-package system) nil)
                ((:dispatch ctx) [[:layout/failed [{:path [] :code :read-failed
                                                    :message (str "Could not read the package: " (.-message error))}]]])))))

(defn- install-pending! [ctx system]
  (if-let [result @(:pending-package system)]
    (if-let [db @(:database system)]
      (-> (layout-storage/install! db result)
          (.then (fn [_] ((:dispatch ctx) [[:layout/loaded result false]])))
          (.catch (fn [error]
                    ((:dispatch ctx)
                     [[:layout/failed [{:path [] :code :storage-failed
                                        :message (str "Could not install the layout: "
                                                      (.-message error))}]]]))))
      ((:dispatch ctx) [[:layout/failed [{:path [] :code :storage-unavailable
                                          :message "IndexedDB is unavailable; imported layouts cannot be installed."}]]]))
    ((:dispatch ctx) [[:layout/failed [{:path [] :code :missing-import-preview
                                        :message "The layout preview expired. Choose the package again."}]]])))

(defn- cache-active! [_ctx system result]
  (reset! (:active-package system) result))

(defn- invoke-scene! [system command & args]
  (when-let [controller @(:scene system)]
    (when-let [f (get controller command)]
      (apply f args))))

(defn- stop-landing-scroll! [_ctx system]
  (when-let [cleanup @(:landing-scroll-cleanup system)]
    (cleanup)
    (reset! (:landing-scroll-cleanup system) nil)))

(defn- start-landing-scroll! [ctx system node]
  (stop-landing-scroll! ctx system)
  (let [sections (vec (array-seq (.querySelectorAll node "[data-landing-chapter]")))
        maximum (max 0 (dec (count sections)))
        target* (atom (double @(:landing-progress system)))
        frame* (atom nil)
        observer* (atom nil)
        last-chapter* (atom nil)]
    (letfn [(scene-progress! [value]
              (reset! (:landing-progress system) value)
              (invoke-scene! system :set-landing-progress! value))
            (frame! []
              (reset! frame* nil)
              (let [current @(:landing-progress system)
                    target @target*
                    reduced? (get-in (app/snapshot system) [:settings :reduced-motion?])
                    next (if reduced?
                           target
                           (+ current (* (- target current) 0.16)))]
                (scene-progress! (if (< (js/Math.abs (- target next)) 0.001)
                                   target
                                   next))
                (when (> (js/Math.abs (- target next)) 0.001)
                  (reset! frame* (js/requestAnimationFrame frame!)))))
            (schedule! []
              (when-not @frame*
                (reset! frame* (js/requestAnimationFrame frame!))))
            (fallback-chapter! [progress]
              (let [index (max 0 (min maximum (js/Math.round progress)))
                    chapter (nth landing/chapters index :read-ahead)]
                (when (not= chapter @last-chapter*)
                  (reset! last-chapter* chapter)
                  ((:dispatch ctx) [[:landing/chapter-entered chapter]]))))
            (scroll! []
              (let [scroll-y (.-scrollY js/window)
                    offsets (mapv #(+ scroll-y (.-top (.getBoundingClientRect %))) sections)
                    continuous (landing/scroll-progress offsets scroll-y)
                    reduced? (get-in (app/snapshot system) [:settings :reduced-motion?])
                    ;; Reduced motion keeps the authored composition, but
                    ;; advances it as five stable tableaux instead of tying
                    ;; rotation and parallax to every scroll pixel.
                    progress (if reduced? (js/Math.round continuous) continuous)]
                (reset! target* progress)
                (when-not (exists? js/IntersectionObserver)
                  (fallback-chapter! continuous))
                (schedule!)))]
      (when (exists? js/IntersectionObserver)
        (let [observer
              (js/IntersectionObserver.
               (fn [entries _]
                 (when-let [entry (->> (array-seq entries)
                                       (filter #(.-isIntersecting %))
                                       (sort-by #(.-intersectionRatio %) >)
                                       first)]
                   (let [chapter (some-> (.-target entry)
                                         (.getAttribute "data-landing-chapter")
                                         keyword)]
                     (when (and chapter (not= chapter @last-chapter*))
                       (reset! last-chapter* chapter)
                       ((:dispatch ctx) [[:landing/chapter-entered chapter]])))))
               #js {:threshold #js [0.38 0.6 0.82]
                    :rootMargin "-12% 0px -12% 0px"})]
          (reset! observer* observer)
          (doseq [section sections] (.observe observer section))))
      (.addEventListener js/window "scroll" scroll! #js {:passive true})
      (.addEventListener js/window "resize" scroll! #js {:passive true})
      (scroll!)
      (reset! (:landing-scroll-cleanup system)
              #(do
                 (.removeEventListener js/window "scroll" scroll!)
                 (.removeEventListener js/window "resize" scroll!)
                 (when-let [observer @observer*] (.disconnect observer))
                 (when-let [frame @frame*] (js/cancelAnimationFrame frame))
                 (reset! frame* nil))))))

(defn- mount-scene! [ctx system node]
  (when-let [old @(:scene system)] ((:dispose! old)))
  (let [controller (scene/create-controller)]
    (reset! (:scene system) controller)
    ((:mount! controller) node
     {:ready #(do
                ((:dispatch ctx) [[:scene/ready]])
                (when @(:active-package system)
                  ((:dispatch ctx) [[:scene/load-active]])))
      :failed #((:dispatch ctx) [[:scene/failed %]])})))

(defn- unload-scene! [_ctx system]
  (when-let [controller @(:scene system)] ((:dispose! controller)))
  (reset! (:scene system) nil))

(defn- load-active-scene! [ctx system]
  (when-let [result @(:active-package system)]
    (when-let [controller @(:scene system)]
      ((:load! controller) (:model-buffer result) (:layout result)
       #(do ((:dispatch ctx) [[:scene/ready]])
            ((:dispatch ctx) [[:scene/sync]]))
       #((:dispatch ctx) [[:scene/failed (.-message %)]])))))

(defn- sync-scene! [_ctx system]
  (let [snapshot (app/snapshot system)
        settings (:settings snapshot)
        mode (:mode settings)
        layer-state (:layer-trainer snapshot)
        stage (:stage layer-state)
        target (state/current-target snapshot)
        landing? (landing/active? (:landing snapshot))
        scene-layer (if landing?
                      (landing/active-layer (:landing snapshot))
                      (:active-layer layer-state))
        onboarding-layers? (and (onboarding/active? (:onboarding snapshot))
                                (= :layers (get-in snapshot [:onboarding :step])))
        reveal? (if onboarding-layers?
                  (= :gestures (get-in snapshot [:onboarding :phase]))
                  (or (= stage :guided)
                      (= stage :explore)
                      (and (= stage :delayed) (:hint-visible? layer-state))))
        expected (when (and (= mode :layers) (not= stage :explore) reveal?)
                   (->> (layout-schema/stroke-for (get-in snapshot [:layout :active]) (:character target))
                        (keep second)
                        (filter keyword?)
                        vec))
        legend-scope (cond
                       (or (not= mode :layers) (not (:layer-legends? settings))) #{}
                       (= stage :explore) :all
                       reveal? expected
                       :else #{})]
    (invoke-scene! system :set-mode! (state/scene-presentation snapshot))
    (invoke-scene! system :set-theme! (get-in snapshot [:theme :resolved]))
    (when landing?
      (invoke-scene! system :set-landing-progress! @(:landing-progress system)))
    (invoke-scene! system :set-orbit-enabled! (state/layer-orbit-enabled? snapshot))
    (invoke-scene! system :set-expected! expected)
    (invoke-scene! system :set-layer! scene-layer legend-scope)
    (invoke-scene! system :set-display-status! (display-status/presentation snapshot))
    (invoke-scene! system :set-ambient! (:ambient-intensity settings))
    (invoke-scene! system :set-glow! (:glow-intensity settings))
    (invoke-scene! system :set-reduced-motion! (:reduced-motion? settings))))

(defn- pulse-stroke! [_ctx system character]
  (let [layout (get-in (app/snapshot system) [:layout :active])
        key-id (->> (layout-schema/stroke-for layout character)
                    reverse
                    (some (fn [[op key-id]] (when (#{:tap :double-tap :double-hold :hold} op) key-id))))]
    (when key-id (invoke-scene! system :pulse! key-id))))

(defn- sync-layer-hint! [ctx system]
  (when-let [timer (get @(:timers system) :layer-hint)] (js/clearTimeout timer))
  (let [snapshot (app/snapshot system)]
    (when (= :delayed (get-in snapshot [:layer-trainer :stage]))
      (let [target-index (get-in snapshot [:layer-trainer :target-index])
            timer (js/setTimeout #((:dispatch ctx) [[:layers/hint-reveal target-index]]) 1050)]
        (swap! (:timers system) assoc :layer-hint timer)))))

(defn- schedule-gesture-finished! [ctx system playback-id duration-ms]
  (when-let [timer (get @(:timers system) :gesture-playback)]
    (js/clearTimeout timer))
  (let [timer (js/setTimeout
               #(do
                  (swap! (:timers system) dissoc :gesture-playback)
                  ((:dispatch ctx) [[:gesture/finished playback-id]]))
               (+ duration-ms 24))]
    (swap! (:timers system) assoc :gesture-playback timer)))

(def effects
  {:state/set set-state!
   :state/assoc assoc-state!
   :persistence/load persistence-load!
   :persistence/save-settings (fn [_ctx system] (persistence/save-settings! (:settings (app/snapshot system))))
   :persistence/save-progress (fn [_ctx system] (persistence/save-progress! (:progress (app/snapshot system))))
   :persistence/save-summaries (fn [_ctx system] (persistence/save-summaries! (:recent-summaries (app/snapshot system))))
   :persistence/save-layouts (fn [_ctx system] (persistence/save-layouts! (get-in (app/snapshot system) [:layout :installed])))
   :persistence/save-onboarding (fn [_ctx system] (persistence/save-onboarding! (:onboarding (app/snapshot system))))
   :persistence/save-landing (fn [_ctx system] (persistence/save-landing! (:landing (app/snapshot system))))
   :persistence/reset-progress (fn [_ctx _system] (persistence/reset-progress!))
   :appearance/watch-system watch-system-theme!
   :pwa/start (fn [ctx system]
                ;; Caching the unoptimized REPL shell would obstruct hot reload.
                (when (and (not goog.DEBUG) (nil? @(:pwa system)))
                  (reset! (:pwa system)
                          (pwa/start! #((:dispatch ctx) [[:pwa/changed %]])))))
   :pwa/prompt-install (fn [_ctx system] (when-let [service @(:pwa system)] ((:install! service))))
   :pwa/apply-update (fn [_ctx system] (when-let [service @(:pwa system)] ((:update! service))))
   :corpora/load load-corpora!
   :database/set database-set!
   :layout/load-selected load-selected!
   :layout/read-file read-layout-file!
   :layout/install-pending install-pending!
   :layout/cache-active cache-active!
   :layout/clear-pending (fn [_ctx system] (reset! (:pending-package system) nil))
   :layout/remove-stored (fn [_ctx system id]
                           (when-let [db @(:database system)] (layout-storage/remove! db id)))
   :landing/start-scroll start-landing-scroll!
   :landing/stop-scroll stop-landing-scroll!
   :scene/mount-effect mount-scene!
   :scene/unmount-effect unload-scene!
   :scene/load-active load-active-scene!
   :scene/sync sync-scene!
   :scene/press (fn [_ctx system key-id] (when key-id (invoke-scene! system :press! key-id)))
   :scene/release (fn [_ctx system key-id] (when key-id (invoke-scene! system :release! key-id)))
   :scene/pulse-stroke pulse-stroke!
   :scene/play-gesture (fn [_ctx system timeline]
                         (invoke-scene! system :play-gesture! timeline))
   :scene/cancel-gesture (fn [_ctx system]
                          (invoke-scene! system :cancel-gesture!))
   :scene/command (fn [_ctx system command & args]
                    (apply invoke-scene! system command args))
   :timer/sync-layer-hint sync-layer-hint!
   :timer/gesture-finished schedule-gesture-finished!
   :clock/start (fn [ctx system]
                  (when-not (get @(:timers system) :clock)
                    (swap! (:timers system) assoc :clock
                           (js/setInterval #((:dispatch ctx) [[:clock/tick (now)]]) 1000))))
   :input/clear (fn [_ctx _system node] (when node (set! (.-value node) "")))
   :dom/prevent-default (fn [_ctx _system event] (when event (.preventDefault event)))
   :dom/click-id (fn [_ctx _system id] (some-> (js/document.getElementById id) .click))
   :dom/scroll-top (fn [_ctx _system]
                     (let [location (.-location js/window)]
                       (when (seq (.-hash location))
                         (js-invoke (.-history js/window) "replaceState" nil ""
                                    (str (.-pathname location) (.-search location))))
                       (.scrollTo js/window 0 0)))
   :dom/focus-input (fn [_ctx _system]
                      (js/setTimeout #(some-> (js/document.getElementById "typing-input") .focus) 0))
   :dom/focus-settings-button (fn [_ctx _system]
                                (js/setTimeout #(some-> (js/document.getElementById "settings-button") .focus) 0))
   :dom/focus-id (fn [_ctx _system id]
                   (js/setTimeout #(some-> (js/document.getElementById id) .focus) 0))
   :dom/set-language (fn [_ctx _system language]
                       (set! (.-lang js/document.documentElement) (if (= language :nb) "nb" "en")))
   :dom/focus-if-background (fn [_ctx _system event]
                              (let [target (.-target event)]
                                (when-not (.closest target "button, input, select, textarea, a")
                                  (some-> (js/document.getElementById "typing-input") .focus))))
   :settings/mounted (fn [_ctx _system node]
                       (js/setTimeout #(some-> (.querySelector node "#settings-close") .focus) 0))
   :completion/mounted (fn [_ctx _system node]
                         (js/setTimeout
                          #(do
                             (set! (.-scrollTop node) 0)
                             (.focus node #js {:preventScroll true}))
                          0))
   :dom/trap-focus (fn [_ctx _system event]
                     (let [container (.-currentTarget event)
                           selector (str "button:not([disabled]),input:not([disabled]),select:not([disabled]),"
                                         "textarea:not([disabled]),a[href],[tabindex]:not([tabindex='-1'])")
                           focusable (->> (array-seq (.querySelectorAll container selector))
                                          (filter #(pos? (.-length (.getClientRects %))))
                                          vec)
                           first-node (first focusable)
                           last-node (last focusable)
                           active (.-activeElement js/document)
                           backwards? (.-shiftKey event)]
                       (cond
                         (empty? focusable) (.preventDefault event)
                         (and backwards? (or (= active first-node) (not (some #{active} focusable))))
                         (do (.preventDefault event) (.focus last-node))
                         (and (not backwards?) (or (= active last-node) (not (some #{active} focusable))))
                         (do (.preventDefault event) (.focus first-node)))))})

(def placeholders
  {;; InputEvent.data is the inserted delta. Reading the control's whole value
   ;; can replay an uncleared prefix when events arrive faster than a DOM
   ;; update. Programmatic input and older browsers fall back to target.value.
   :event/input-text (fn [dispatch-data]
                       (let [event (:replicant/dom-event dispatch-data)
                             data (some-> event .-data)]
                         (if (string? data)
                           data
                           (some-> event .-target .-value))))
   :event/input-type (fn [dispatch-data]
                       (some-> (:replicant/dom-event dispatch-data) .-inputType))
   :event/composing? (fn [dispatch-data]
                       (boolean (some-> (:replicant/dom-event dispatch-data) .-isComposing)))
   :event/target-value (fn [dispatch-data] (some-> (:replicant/dom-event dispatch-data) .-target .-value))
   :event/keyword-value (fn [dispatch-data] (some-> (:replicant/dom-event dispatch-data) .-target .-value keyword))
   :event/number-value (fn [dispatch-data] (some-> (:replicant/dom-event dispatch-data) .-target .-value js/parseFloat))
   :event/checked (fn [dispatch-data] (boolean (some-> (:replicant/dom-event dispatch-data) .-target .-checked)))
   :event/key (fn [dispatch-data] (some-> (:replicant/dom-event dispatch-data) .-key))
   :event/code (fn [dispatch-data] (some-> (:replicant/dom-event dispatch-data) .-code))
   :event/modifiers (fn [dispatch-data]
                      (let [event (:replicant/dom-event dispatch-data)]
                        {:meta? (boolean (.-metaKey event))
                         :control? (boolean (.-ctrlKey event))
                         :alt? (boolean (.-altKey event))
                         :shift? (boolean (.-shiftKey event))}))
   :event/dom-event (fn [dispatch-data] (:replicant/dom-event dispatch-data))
   :event/node (fn [dispatch-data] (:replicant/node dispatch-data))
   :event/file (fn [dispatch-data] (some-> (:replicant/dom-event dispatch-data) .-target .-files (aget 0)))
   :event/time (fn [dispatch-data] (:clock/now dispatch-data))
   :event/wall-time (fn [dispatch-data] (:clock/wall-now dispatch-data))})

(defn config []
  {:nexus/system->state app/snapshot
   :nexus/expansions expansions
   :nexus/effects effects
   :nexus/placeholders placeholders
   :nexus/interceptors
   [{:id ::render-once
     :after-dispatch (fn [{:keys [system] :as context}]
                       (schedule-render! system)
                       context)}]
   :nexus/on-error
   (fn [_context {:keys [err]}]
     (.error js/console "PRETYPIST action failed" err))})

(defn dispatch! [system dispatch-data actions]
  (let [actions (cond
                  (and (vector? actions) (keyword? (first actions))) [actions]
                  (sequential? actions) (vec (remove nil? actions))
                  :else [])]
    (when (seq actions)
      (nexus/dispatch (config) system
                      (assoc dispatch-data
                             :clock/now (now)
                             :clock/wall-now (js/Date.now))
                      actions))))
