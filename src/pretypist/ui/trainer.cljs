(ns pretypist.ui.trainer
  (:require [clojure.string :as str]
            [pretypist.continuity :as continuity]
            [pretypist.layout.schema :as layout-schema]
            [pretypist.state :as state]
            [pretypist.typing.adaptive :as adaptive]
            [pretypist.typing.chunks :as chunks]
            [pretypist.typing.metrics :as metrics]
            [pretypist.ui.disclosure :refer [disclosure]]
            [pretypist.ui.brand :as brand]
            [pretypist.ui.icons :as icons]))

(def mode-order [:flow :horizon :chunks :layers])

(defn mode-name-key [mode] (keyword "mode" (name mode)))
(defn mode-description-key [mode] (keyword "mode" (str (name mode) "-description")))

(defn session-difficulty [state mode]
  (or (get-in state [:session :calibration-difficulty])
      (when (= :consolidation (get-in state [:session :run-kind]))
        (get-in state [:session :focus :difficulty]))
      (adaptive/effective-difficulty
       (:progress state) mode (get-in state [:settings :manual-difficulty]))))

(defn live-metrics [session now]
  (let [summary (metrics/summarize session now)]
    [:div.live-metrics {:aria-live "polite"}
     [:span.metric [:t :metric/wpm {:value (js/Math.round (:wpm summary))}]]
     [:span.metric-separator "·"]
     [:span.metric [:t :metric/accuracy {:value (js/Math.round (* 100 (:accuracy summary)))}]]]))

(defn- mask-class [state idx]
  (let [session (:session state)
        text (:text session)
        cursor (:cursor session)
        stage (:mask-stage (session-difficulty state :horizon))
        [word-start word-end] (chunks/current-word-range text cursor)
        in-word? (<= word-start idx (dec (max (inc word-start) word-end)))
        typed-in-word (- cursor word-start)
        word-length (max 1 (- word-end word-start))
        chunk (chunks/current-chunk-range (:chunks session) cursor)
        in-chunk? (and chunk (<= (:start chunk) idx) (< idx (:end chunk)))
        trigger? (case stage
                   0 false
                   1 false
                   2 (>= (/ typed-in-word word-length) 0.7)
                   3 (>= typed-in-word 3)
                   4 (>= typed-in-word 1)
                   5 true
                   6 (and chunk (> cursor (:start chunk)))
                   false)]
    (cond
      (and (= stage 1) (< idx cursor)) "is-faded-more"
      (and trigger? (= stage 6) in-chunk?) "is-masked-hard"
      (and trigger? in-word? (>= stage 4)) "is-masked-hard"
      (and trigger? in-word?) "is-masked-soft"
      :else nil)))

(defn character [state idx character]
  (let [session (:session state)
        cursor (:cursor session)
        mode (get-in state [:settings :mode])
        [word-start word-end] (chunks/current-word-range (:text session) cursor)]
    [:span.character
     {:replicant/key idx
      :class [(cond
                (< idx cursor) "is-complete"
                (= idx cursor) "is-current"
                :else "is-upcoming")
              (when (and (>= idx cursor) (<= word-start idx) (< idx word-end))
                "is-current-word")
              (when (= idx cursor) "has-caret")
              (when (= mode :horizon) (mask-class state idx))
              (when (and (= idx cursor) (:pending-error? session)) "is-error")]}
     character]))

(defn prose-line [state]
  (let [session (:session state)
        text (:text session)]
    [:div.prose-wrap
     [:p.sr-only {:aria-live "polite"}
      [:t :a11y/position {:current (min (count text) (inc (:cursor session)))
                          :total (count text)}]]
     [:p.prose-line {:aria-label text}
      (map-indexed #(character state %1 %2) (js/Array.from text))
      (when (= (:cursor session) (count text)) [:span.end-caret])]]))

(defn chunk-line [state]
  (let [session (:session state)
        ranges (chunks/chunk-ranges (:chunks session))
        opacity (:boundary-opacity (session-difficulty state :chunks))]
    [:div.prose-wrap
     [:p.sr-only {:aria-live "polite"} (:text session)]
     [:p.prose-line.chunk-line {:aria-label (:text session)}
      (for [[chunk-index {:keys [chunk start]}] (map-indexed vector ranges)]
        [:span.phrase-chunk
         {:replicant/key chunk-index
          :style {:--boundary-opacity opacity}}
         (map-indexed (fn [local-index ch]
                        (character state (+ start local-index) ch))
                      (js/Array.from chunk))
         (when (< chunk-index (dec (count ranges)))
           (character state (+ start (count chunk)) " "))])]]))

(defn horizon-preview [state]
  (let [session (:session state)
        current (chunks/current-chunk-range (:chunks session) (:cursor session))
        ranges (chunks/chunk-ranges (:chunks session))
        idx (.indexOf ranges current)
        preview-words (:preview-words (session-difficulty state :horizon))
        visible (->> ranges
                     (drop (max 0 idx))
                     (map :chunk)
                     (str/join " · ")
                     (#(str/split % #"\s+"))
                     (take preview-words)
                     (str/join " "))
        locked? (not (adaptive/horizon-unlocked? (:progress state)))]
    [:div.horizon-preview
     [:span.eyebrow [:t :horizon/preview]]
     [:p visible]
     (when locked? [:small [:t :horizon/locked]])]))

(defn typing-input [running?]
  [:textarea.typing-input
   {:id "typing-input"
    :name "typing-input"
    :aria-label [:t :a11y/typing-input]
    :aria-describedby "typing-instructions"
    :autocomplete "off"
    :autocapitalize "off"
    :spellcheck "false"
    :inputmode "text"
    :disabled (not running?)
    :value ""
    :on {:beforeinput [[:typing/before-input [:event/input-type] [:event/composing?]
                        [:event/dom-event]]]
         :input [[:typing/dom-input [:event/input-text] [:event/input-type] [:event/composing?]
                  [:event/time] [:event/wall-time] [:event/node]]]
         :compositionstart [[:typing/composition-started]]
         :compositionend [[:typing/composition-ended]]
         :paste [[:typing/blocked-edit :paste [:event/dom-event] [:event/node]]]
         :drop [[:typing/blocked-edit :drop [:event/dom-event] [:event/node]]]
         :keydown [[:typing/key-down [:event/key] [:event/code] [:event/modifiers]
                    [:event/composing?] [:event/time] [:event/wall-time]
                    [:event/dom-event] [:event/node]]]
         :keyup [[:typing/key-up [:event/code] [:event/modifiers]]]
         :focus [[:typing/focus-gained]]
         :blur [[:typing/focus-lost [:event/time]]]}}])

(defn- continuity-points [values]
  (let [values (vec (filter number? values))
        low (when (seq values) (apply min values))
        high (when (seq values) (apply max values))
        spread (max 1 (- (or high 0) (or low 0)))
        denominator (max 1 (dec (count values)))]
    (->> values
         (map-indexed (fn [index value]
                        (let [x (+ 3 (* 94 (/ index denominator)))
                              y (- 21 (* 16 (/ (- value low) spread)))]
                          (str (.toFixed x 2) "," (.toFixed y 2)))))
         (str/join " "))))

(defn- continuity-chart [values]
  (when (> (count values) 1)
    [:svg.continuity-chart
     {:viewBox "0 0 100 24"
      :role "img"
      :aria-label [:t :continuity/chart-label {:count (count values)}]
      :preserveAspectRatio "none"}
     [:line {:x1 3 :x2 97 :y1 21 :y2 21}]
     [:polyline {:points (continuity-points values)}]]))

(defn- selection-note [state]
  (when-let [selection-copy (get-in state [:session :selection :explanation])]
    [:div.selection-note
     (disclosure state :passage-reason [:t :selection/why]
                 [:p [:t (:key selection-copy) (:params selection-copy)]])]))

(defn- continuation-view [state insight]
  (let [session (:session state)
        prescription (:prescription insight)
        focus-copy (:copy prescription)]
    [:section.session-intro.continuation-intro
     [:span.eyebrow [:t :continuity/this-week]]
     [:h1 [:t (get-in insight [:headline :key]) (get-in insight [:headline :params])]]
     [:p.continuity-trend [:t (get-in insight [:trend :key])]]
     (continuity-chart (:wpm-history insight))
     (when focus-copy
       [:div.continuity-focus
        [:span.eyebrow [:t :continuity/next-focus]]
        [:p [:t (:key focus-copy) (:params focus-copy)]]])
     [:p.continuity-passage
      [:span (:title session)]
      [:span " · "]
      [:span [:t (mode-description-key (get-in state [:settings :mode]))]]]
     [:div.continuity-actions
      [:button.primary-button
       {:type "button" :on {:click [[:session/start [:event/time]]]}}
       (icons/icon :play)
       [:span [:t :continuity/continue]]]
      (when (:repeat-focus? insight)
        [:button.secondary-button
         {:type "button" :on {:click [[:session/consolidate [:event/time]]]}}
         (icons/icon :keyboard)
         [:span [:t :continuity/repeat-focus]]])
      [:button.text-button
       {:type "button" :on {:click [[:dom/focus-id "header-mode"]]}}
       [:span [:t :continuity/choose-mode]]]]
     (selection-note state)]))

(defn idle-view [state]
  (let [mode (get-in state [:settings :mode])
        session (:session state)
        insight (continuity/insight (:recent-summaries state)
                                    (get-in state [:progress :next-prescription])
                                    (get-in state [:app :wall-now]))]
    [:main.trainer-main
     (if insight
       (continuation-view state insight)
       [:section.session-intro
        [:span.eyebrow (get-in state [:layout :active :package/name] "Modified Engram")]
        [:h1 (:title session)]
        [:p [:t (mode-description-key mode)]]
        [:button.primary-button
         {:type "button" :on {:click [[:session/start [:event/time]]]}}
         (icons/icon :play)
         [:span [:t :action/start]]]
        (selection-note state)])]))

(defn paused-view [state]
  [:div.pause-overlay {:role "status" :aria-live "polite"}
   [:span.eyebrow [:t :session/paused]]
   [:h2 [:t :app/focus-lost]]
   [:p (if (= :escape (get-in state [:session :pause-reason]))
         [:t :session/paused-escape]
         [:t :session/paused-focus])]
   [:button.primary-button
    {:type "button" :on {:click [[:session/resume [:event/time]]]}}
    (icons/icon :play)
    [:span [:t :action/resume]]]])

(defn active-prose-view [state]
  (let [session (:session state)
        mode (get-in state [:settings :mode])
        now (get-in state [:app :now])
        focus-copy (get-in session [:focus :copy])]
    [:main.trainer-main.active-session
     {:on {:click [[:trainer/background-click [:event/dom-event]]]}}
     (when focus-copy
       [:div.session-focus
        [:span.eyebrow
         [:t (if (= :transfer (:run-kind session))
               :analysis/transfer-focus
               :analysis/consolidation-focus)]]
        [:p [:t (:key focus-copy) (:params focus-copy)]]])
     (if (= :calibration (:run-kind session))
       [:div.live-metrics.calibration-indicator
        [:span.metric [:t :session/calibrating]]]
       (live-metrics session now))
     (when (= mode :horizon) (horizon-preview state))
     (if (= mode :chunks) (chunk-line state) (prose-line state))
     [:p#typing-instructions.typing-instructions
      (if (= mode :chunks) [:t :chunks/guide] [:t :app/tagline])]
     (when (:pending-error? session)
       [:p.input-error {:role "alert"} [:t :session/error]])
     (typing-input (= :running (:status session)))
     [:button.pause-button
      {:type "button" :on {:click [[:session/pause [:event/time]]]}}
      (icons/icon :pause)
      [:span [:t :action/pause]]]
     (when (= :paused (:status session)) (paused-view state))]))

(defn- step-label [state target [op key-id]]
  (let [layout (get-in state [:layout :active])
        layer (if (= op :tap) (:layer target) :base)
        legend (or (not-empty (layout-schema/legend-for layout layer key-id))
                   (not-empty (layout-schema/legend-for layout :base key-id))
                   (-> key-id name str/upper-case))]
    [:t (keyword "gesture" (name op)) {:key legend}]))

(defn gesture-steps [state target]
  (let [stroke (layout-schema/stroke-for (get-in state [:layout :active]) (:character target))]
    [:ol.gesture-steps
     (for [[idx step] (map-indexed vector stroke)]
       [:li {:replicant/key idx}
        [:span.step-number (inc idx)]
        [:span (step-label state target step)]])]))

(defn gesture-replay-control [state character]
  (let [playing? (= character (get-in state [:ui :gesture-playback :character]))]
    [:button.secondary-button.gesture-replay-button
     {:type "button"
      :disabled playing?
      :aria-label [:t (if playing? :action/replaying-gesture :action/replay-gesture)
                   {:character character}]
      :title [:t :layers/illustrative-timing]
      :on {:click [[:gesture/replay character [:event/time]]]}}
     (icons/icon :play)
     [:span [:t (if playing? :action/replaying :action/replay)]]]))

(defn layer-stage-tabs [current]
  [:div.stage-tabs {:role "tablist" :aria-label [:t :a11y/layer-stage]}
   (for [stage [:explore :guided :delayed :recall :transfer]]
     [:button.stage-tab
      {:replicant/key (name stage)
       :type "button"
       :role "tab"
       :aria-selected (= stage current)
       :class [(when (= stage current) "is-active")]
       :on {:click [[:layers/stage-selected stage]]}}
      [:t (keyword "layers" (name stage))]])])

(defn layer-selector [active]
  [:div.layer-selector {:aria-label [:t :a11y/keyboard-layer]}
   (for [layer [:base :num :sym :nav :mouse]]
     [:button.layer-pill
      {:replicant/key (name layer)
       :type "button"
       :class [(str "layer-" (name layer)) (when (= active layer) "is-active")]
       :aria-pressed (= active layer)
       :on {:click [[:layers/layer-selected layer]]}}
      (str/upper-case (name layer))])])

(defn layers-view [state]
  (let [{:keys [stage active-layer hint-visible?]} (:layer-trainer state)
        target (state/current-target state)
        transfer-target (state/transfer-target state)
        reveal? (or (= stage :guided)
                    (= stage :explore)
                    (and (= stage :delayed) hint-visible?))]
    [:main.trainer-main.layers-main
     [:div.layers-toolbar
      (when (= stage :explore) [:h1.sr-only [:t :mode/layers]])
      (layer-stage-tabs stage)
      (layer-selector active-layer)]
     [:section.layer-prompt
      (case stage
        :explore
        [:div.layer-prompt-content
         [:p#keyboard-orbit-instructions [:t :keyboard/orbit]]]

        :transfer
        [:div.layer-prompt-content
         [:span.eyebrow [:t :layers/transfer]]
         [:h1 [:t :layers/transfer-offer {:character (:character transfer-target)}]]
         [:button.primary-button
          {:type "button" :on {:click [[:layers/transfer-start [:event/time]]]}}
          (icons/icon :keyboard)
          [:span [:t :action/start]]]]

        [:div.layer-prompt-content
         [:span.eyebrow [:t :layers/inferred]]
         [:h1 [:t :layers/prompt {:character (:character target)}]]
         (when reveal?
           [:div.gesture-guide
            (gesture-steps state target)
            (gesture-replay-control state (:character target))])
         (when (and (= stage :delayed) (not hint-visible?))
           [:p.hint-waiting [:t :layers/hint-waiting]])
         [:p.telemetry-note [:t :layers/inferred-note]]
         (when (and (get-in state [:layer-trainer :transfer-offered?])
                    (not= stage :transfer))
           [:button.transfer-offer-button
            {:type "button" :on {:click [[:layers/stage-selected :transfer]]}}
            (icons/icon :keyboard)
            [:span [:t :layers/transfer-cta
                    {:character (:character transfer-target)}]]])])]
     (typing-input (not= stage :explore))
     [:button.camera-button.icon-button
      {:type "button"
       :aria-label [:t :keyboard/reset-camera]
       :title [:t :keyboard/reset-camera]
       :on {:click [[:scene/reset-camera]]}}
      (icons/icon :reset-camera)] ]))

(defn trainer [state]
  (let [mode (get-in state [:settings :mode])
        status (get-in state [:session :status])]
    (cond
      (= :loading (get-in state [:app :status]))
      [:main.loading-view [:div.loading-mark (brand/mark)] [:p [:t :app/loading]]]

      (and (= mode :layers)
           (get-in state [:layer-trainer :transfer?])
           (#{:running :paused} status)) (active-prose-view state)
      (= mode :layers) (layers-view state)
      (= status :idle) (idle-view state)
      :else (active-prose-view state))))
