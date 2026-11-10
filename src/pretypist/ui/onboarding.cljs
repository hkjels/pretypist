(ns pretypist.ui.onboarding
  (:require [pretypist.onboarding :as onboarding]
            [pretypist.state :as state]
            [pretypist.typing.adaptive :as adaptive]
            [pretypist.ui.completion :as completion]
            [pretypist.ui.brand :as brand]
            [pretypist.ui.disclosure :refer [disclosure]]
            [pretypist.ui.icons :as icons]
            [pretypist.ui.settings :as settings]
            [pretypist.ui.trainer :as trainer]))

(defn- progress [state]
  (let [total (if (state/gesture-practice-available? state) 3 2)
        current (min total (onboarding/progress-index (:onboarding state)))]
    [:div.onboarding-progress
     [:span [:t :onboarding/calibration-progress {:current current :total total}]]
     [:div.onboarding-progress-dots {:aria-hidden "true"}
      (for [index (range 1 (inc total))]
        [:i {:replicant/key index
             :class [(when (< index current) "is-complete")
                     (when (= index current) "is-current")]}])]]))

(defn header [state]
  [:header.app-header.onboarding-header
   [:span.wordmark.onboarding-wordmark (brand/mark) [:span [:t :app/wordmark]]]
   (progress state)])

(defn- language-select [state]
  [:label.onboarding-control {:for "onboarding-language"}
   [:span.eyebrow [:t :settings/language]]
   [:select#onboarding-language
    {:value (name (get-in state [:settings :language]))
     :on {:change [[:settings/change :language [:event/keyword-value]]]}}
    [:option {:value "en"} [:t :language/en]]
    [:option {:value "nb"} [:t :language/nb]]]])

(defn- layout-picker [state]
  [:div.onboarding-layout-picker
   [:span.eyebrow [:t :onboarding/your-layout]]
   [:div.layout-list
    (map #(settings/layout-row state %) (get-in state [:layout :installed]))]
   [:input.sr-only
    {:id "layout-package-input"
     :type "file"
     :accept ".pretypist,application/vnd.pretypist+zip,application/zip"
     :aria-label [:t :layout/input-label]
     :on {:change [[:layout/file-selected [:event/file] [:event/node]]]}}]
   [:button.secondary-button
    {:type "button" :on {:click [[:layout/import-requested]]}}
    (icons/icon :upload)
    [:span [:t :action/import]]]
   (when (= :importing (get-in state [:layout :status]))
     [:p.setting-note [:t :layout/importing]])
   (settings/layout-import-preview (get-in state [:ui :layout-import-preview])
                                   (get-in state [:layout :status]))
   (when (seq (get-in state [:ui :validation-errors]))
     [:div.validation-errors {:role "alert"}
      (icons/icon :warning)
      [:div
       [:strong [:t :layout/invalid]]
       [:ul
        (for [[index error] (map-indexed vector (get-in state [:ui :validation-errors]))]
          [:li {:replicant/key index}
           [:code (if (seq (:path error)) (pr-str (:path error)) "package")]
           [:span (:message error)]])]]])])

(defn setup [state]
  [:main.onboarding-main.onboarding-setup
   [:section.onboarding-copy
    [:span.eyebrow [:t :onboarding/welcome]]
    [:h1 [:t :onboarding/setup-title]]
    [:p.lede [:t :onboarding/setup-copy]]]
   [:div.onboarding-setup-grid
    (language-select state)
    (layout-picker state)]
   [:section.telemetry-explainer
    [:p.telemetry-note [:t :onboarding/telemetry-short]]
    (disclosure state :telemetry [:t :onboarding/telemetry-details]
                [:dl
                 [:div
                  [:dt [:t :layers/observed]]
                  [:dd [:t :onboarding/observed-copy]]]
                 [:div
                  [:dt [:t :layers/inferred]]
                  [:dd [:t :onboarding/inferred-copy]]]
                 [:div [:dd [:t :layers/inferred-note]]]])]
   [:div.onboarding-setup-actions
    [:button.primary-button.onboarding-next
     {:type "button"
      :disabled (not= :ready (get-in state [:layout :status]))
      :on {:click [[:onboarding/start [:event/time]]]}}
     [:span [:t :onboarding/begin]]
     (icons/icon :arrow-right)]
    [:button.text-button
     {:type "button" :on {:click [[:onboarding/skip [:event/time]]]}}
     [:t :onboarding/skip]]]] )

(def intro-copy
  {:baseline {:eyebrow :onboarding/session-one
              :title :onboarding/baseline-title
              :copy :onboarding/baseline-copy
              :action :onboarding/start-baseline}
   :rhythm {:eyebrow :onboarding/session-two
            :title :onboarding/rhythm-title
            :copy :onboarding/rhythm-copy
            :action :onboarding/start-rhythm}
   :layers {:eyebrow :onboarding/session-three
            :title :onboarding/layers-title
            :copy :onboarding/layers-copy
            :action :onboarding/show-gestures}})

(defn session-intro [state]
  (let [step (get-in state [:onboarding :step])
        copy (get intro-copy step)]
    [:main.onboarding-main.onboarding-centered
     [:section.onboarding-copy
      [:span.eyebrow [:t (:eyebrow copy)]]
      [:h1 [:t (:title copy)]]
      [:p.lede [:t (:copy copy)]]
      (when (get-in state [:onboarding :interrupted?])
        [:p.onboarding-resume-note {:role "status"}
         (icons/icon :pause)
         [:span [:t :onboarding/interrupted]]])]
     [:button.primary-button
      {:type "button"
       :on {:click [[(if (= step :layers)
                       :onboarding/gestures-start
                       :onboarding/session-start)
                     [:event/time]]]}}
      (icons/icon (if (= step :layers) :keyboard :play))
      [:span [:t (:action copy)]]]]))

(defn layer-gestures [state]
  (let [index (get-in state [:onboarding :gesture-index] 0)
        target (state/current-target state)]
    [:main.trainer-main.layers-main.onboarding-layer-main
     [:section.layer-prompt
      [:div.layer-prompt-content
       [:span.eyebrow
        [:t :onboarding/gesture-progress
         {:current (inc index) :total onboarding/required-gestures}]]
       [:h1 [:t :layers/prompt {:character (:character target)}]]
       [:div.gesture-guide
        (trainer/gesture-steps state target)
        (trainer/gesture-replay-control state (:character target))]
       [:p.telemetry-note [:t :onboarding/gesture-note]]]]
     (trainer/typing-input true)]))

(defn transfer-ready [state]
  (let [[first-character second-character third-character]
        (mapv :character (state/onboarding-targets state))]
    [:main.onboarding-main.onboarding-centered
     [:section.onboarding-copy
      [:span.eyebrow [:t :onboarding/transfer-eyebrow]]
      [:h1 [:t :onboarding/transfer-title
            {:first first-character
             :second second-character
             :third third-character}]]
      [:p.lede [:t :onboarding/transfer-copy]]]
     [:button.primary-button
      {:type "button" :on {:click [[:onboarding/session-start [:event/time]]]}}
      (icons/icon :keyboard)
      [:span [:t :onboarding/start-transfer]]]]))

(defn- last-record [state]
  (last (get-in state [:onboarding :records])))

(defn reflection [state]
  (let [step (get-in state [:onboarding :step])
        record (last-record state)
        rhythm? (= step :rhythm)]
    [:main.onboarding-main.onboarding-centered.onboarding-reflection
     [:section.onboarding-copy
      [:span.eyebrow [:t (case step
                           :baseline :onboarding/baseline-captured
                           :rhythm :onboarding/rhythm-captured
                           :onboarding/transfer-complete)]]
      [:h1 [:t (case step
                 :baseline :onboarding/baseline-reflection-title
                 :rhythm :onboarding/rhythm-reflection-title
                 :onboarding/transfer-reflection-title)]]
      [:p.lede [:t (case step
                     :baseline :onboarding/baseline-reflection-copy
                     :rhythm :onboarding/rhythm-reflection-copy
                     :onboarding/transfer-reflection-copy)]]]
     (when (and rhythm? (:chart record))
       [:div.onboarding-chart
        (completion/rhythm-chart (:chart record))])
     [:button.primary-button
      {:type "button" :on {:click [[:onboarding/continue]]}}
      [:span [:t :action/continue]]
      (icons/icon :arrow-right)]]))

(defn result [state]
  (let [unlocked? (adaptive/horizon-unlocked? (:progress state))]
    [:main.onboarding-main.onboarding-result
     [:section.onboarding-copy
      [:span.eyebrow [:t :onboarding/calibration-complete]]
      [:h1 [:t (if unlocked?
                 :onboarding/result-unlocked-title
                 :onboarding/result-steady-title)]]
      [:p.lede [:t (if unlocked?
                     :onboarding/result-unlocked-copy
                     :onboarding/result-steady-copy)]]]
     [:div.horizon-demo {:class [(when unlocked? "is-unlocked")]}
      [:span.eyebrow [:t :onboarding/horizon-preview]]
      [:p
       [:span.is-read [:t :onboarding/preview-read]] " "
       [:span.is-current [:t :onboarding/preview-current]] " "
       [:span [:t :onboarding/preview-next]]]]
     [:button.primary-button
      {:type "button" :on {:click [[:onboarding/finish [:event/time]]]}}
      [:span [:t :onboarding/enter-pretypist]]
      (icons/icon :arrow-right)]]))

(defn view [state]
  (let [{:keys [step phase]} (:onboarding state)]
    [:div.onboarding-surface
     (header state)
     (cond
       (= step :setup) (setup state)
       (= step :result) (result state)
       (= phase :running) (trainer/active-prose-view state)
       (= phase :reflection) (reflection state)
       (= phase :gestures) (layer-gestures state)
       (= phase :transfer-ready) (transfer-ready state)
       :else (session-intro state))]))
