(ns pretypist.ui
  (:require [pretypist.ui.completion :as completion]
            [pretypist.ui.brand :as brand]
            [pretypist.continuity :as continuity]
            [pretypist.layout.theme :as layout-theme]
            [pretypist.landing :as landing]
            [pretypist.ui.icons :as icons]
            [pretypist.ui.landing :as landing-ui]
            [pretypist.ui.onboarding :as onboarding-ui]
            [pretypist.ui.settings :as settings]
            [pretypist.ui.trainer :as trainer]
            [pretypist.onboarding :as onboarding]
            [pretypist.state :as state-model]))

(defn- header-select [id class-name label value action-prefix choices]
  [:label.header-control {:for id}
   [:span.sr-only label]
   [:select.header-select
    {:id id
     :class [class-name]
     :aria-label label
     :value (name value)
     :on {:change [(conj action-prefix [:event/keyword-value])]}}
    (for [[choice label-key] choices]
      [:option {:replicant/key (name choice)
                :value (name choice)
                :selected (= choice value)}
       [:t label-key]])]
   (icons/icon :chevron-down)])

(defn header [state]
  (let [language (get-in state [:settings :language])
        mode (get-in state [:settings :mode])]
    [:header.app-header
    [:button.wordmark
      {:type "button" :on {:click [[:landing/open]]} :aria-label [:t :a11y/home]}
     (brand/mark)
     [:span [:t :app/wordmark]]]
     [:div.header-status
      (header-select "header-language" "language-select" [:t :settings/language]
                     language [:settings/change :language]
                     [[:en :language/en] [:nb :language/nb]])
      [:span.header-divider]
      (header-select "header-mode" "mode-select" [:t :a11y/training-mode]
                     mode [:mode/select]
                     (mapv (fn [candidate] [candidate (trainer/mode-name-key candidate)])
                           trainer/mode-order))
      [:button.icon-button.settings-button
       {:id "settings-button"
        :type "button"
        :aria-label [:t :action/settings]
        :title [:t :action/settings]
        :on {:pointerdown [[:dom/prevent-default [:event/dom-event]]]
             :click [[:settings/open]]}}
       (icons/icon :settings)]]]))

(defn scene-canvas [state]
  (let [orbitable? (state-model/layer-orbit-enabled? state)
        continuation? (and (= :idle (get-in state [:session :status]))
                           (some? (continuity/insight
                                   (:recent-summaries state)
                                   (get-in state [:progress :next-prescription])
                                   (get-in state [:app :wall-now]))))
        onboarding? (and (not (landing/active? (:landing state)))
                         (onboarding/active? (:onboarding state)))]
    [:canvas.keyboard-canvas
     (cond-> {:replicant/key "persistent-keyboard-scene"
              :class [(str "is-" (name (state-model/scene-presentation state)))
                      (when onboarding? "is-onboarding")
                      (when (and onboarding?
                                 (= :setup (get-in state [:onboarding :step])))
                        "is-onboarding-setup")
                      (when orbitable? "is-orbitable")
                      (when continuation? "is-continuation")
                      (when (= :failed (get-in state [:app :webgl])) "is-unavailable")]
              :tabindex (if orbitable? "0" "-1")
              :replicant/on-mount [[:scene/mount [:event/node]]]
              :replicant/on-unmount [[:scene/unmount]]}
       orbitable? (assoc :role "group"
                         :aria-label [:t :keyboard/orbit-control]
                         :aria-describedby "keyboard-orbit-instructions"
                         :aria-keyshortcuts "ArrowUp ArrowDown ArrowLeft ArrowRight Home"
                         :on {:keydown [[:scene/orbit-key [:event/key]
                                        [:event/dom-event]]]})
       (not orbitable?) (assoc :aria-hidden "true"))]))

(defn notice [state]
  (when-let [message (get-in state [:ui :notice])]
    [:div.toast {:role "status" :aria-live "polite"}
     (if (keyword? message) [:t message] message)]))

(defn app [state]
  (let [landing? (landing/active? (:landing state))
        onboarding? (and (not landing?)
                         (= :ready (get-in state [:app :status]))
                         (onboarding/active? (:onboarding state)))
        resolved-theme (get-in state [:theme :resolved])
        scheme (:scheme resolved-theme)
        theme-style (into {:color-scheme (name (or scheme :dark))}
                          (map (fn [[property value]] [(keyword property) value]))
                          (layout-theme/css-properties resolved-theme))]
  [:div.app-shell
   {:class [(when (get-in state [:settings :reduced-motion?]) "is-reduced-motion")
            (when landing? "is-landing")
            (when onboarding? "is-onboarding")]
    :data-mode (name (get-in state [:settings :mode]))
    :data-theme (name (or scheme :dark))
    :style theme-style}
   [:div.scene-stage (scene-canvas state)]
   (cond
     landing? (landing-ui/view state)
     onboarding? (onboarding-ui/view state)
     :else (list (header state) (trainer/trainer state)))
   (when (= :failed (get-in state [:app :webgl]))
     [:p.webgl-fallback
      {:title (get-in state [:app :webgl-error])}
      [:t :app/webgl-fallback]])
   (when (and (not landing?) (not onboarding?) (= :complete (get-in state [:session :status])))
     (completion/completion state))
   (when (and (not landing?) (get-in state [:ui :settings-open?]))
     (settings/settings state))
   (notice state)]))
