(ns pretypist.ui.settings
  (:require [pretypist.ui.icons :as icons]
            [pretypist.ui.pwa :as pwa]
            [pretypist.ui.disclosure :refer [disclosure]]))

(defn option [value selected-value label]
  ;; A settings drawer is unmounted when it closes. On a fresh mount browsers
  ;; may resolve the select's `value` before Replicant has inserted its option
  ;; children, leaving the first option visibly selected even though immutable
  ;; application state contains another value. Marking the matching option as
  ;; selected makes initial mount deterministic; keeping the select value below
  ;; retains controlled updates while the drawer remains open.
  [:option {:replicant/key (name value)
            :value (name value)
            :selected (= value selected-value)}
   label])

(defn select-setting [id label-key value choices]
  [:label.setting-row {:for id}
   [:span.setting-label [:t label-key]]
   [:select {:id id
             :value (name value)
             :on {:change [[:settings/change (keyword id) [:event/keyword-value]]]}}
    (for [[choice label] choices]
      (option choice value [:t label]))]])

(defn toggle-setting [id label-key checked?]
  [:label.setting-row.toggle-row {:for id}
   [:span.setting-label [:t label-key]]
   [:input.switch {:id id
                   :type "checkbox"
                   :checked checked?
                   :on {:change [[:settings/change (keyword id) [:event/checked]]]}}]])

(defn range-setting [id label-key value]
  [:label.setting-row.range-row {:for id}
   [:span.setting-label [:t label-key]]
   [:input {:id id
            :type "range"
            :min "0"
            :max "1"
            :step "0.05"
            :value value
            :on {:input [[:settings/change (keyword id) [:event/number-value]]]}}]])

(defn layout-row [state layout]
  (let [active? (= (:id layout) (get-in state [:settings :active-layout]))]
    [:div.layout-row {:replicant/key (:id layout)}
     [:button.layout-choice
      {:type "button"
       :class [(when active? "is-active")]
       :aria-pressed active?
       :on {:click [[:layout/select (:id layout)]]}}
      [:span (:name layout)]
      [:small (when (:builtin? layout) [:t :layout/builtin])]]
     (when-not (:builtin? layout)
       [:button.icon-button.subtle
        {:type "button"
         :aria-label [:t :a11y/remove-layout {:name (:name layout)}]
         :title [:t :action/remove]
         :disabled active?
         :on {:click [[:layout/remove (:id layout)]]}}
        (icons/icon :trash)])]))

(defn- theme-preview-card [scheme preview]
  (let [tokens (:tokens preview)
        lowest (apply min (keep :ratio (:contrast preview)))]
    [:div.theme-preview-card
     {:replicant/key (name scheme)
      :style {:background (:background tokens)
              :color (:foreground tokens)
              :--preview-accent (:accent tokens)
              :--preview-muted (:muted-foreground tokens)}}
     [:div.theme-preview-heading
      [:strong [:t (if (= scheme :dark) :settings/dark :settings/light)]]
      [:small [:t (if (:provided? preview)
                    :layout/theme-package
                    :layout/theme-fallback)]]]
     [:p [:span.theme-preview-mark "Aa"]]
     [:div.theme-preview-colors {:aria-hidden "true"}
      [:span {:style {:background (:accent tokens)}}]
      [:span {:style {:background (:focus tokens)}}]
      [:span {:style {:background (:error tokens)}}]]
     [:small.theme-preview-contrast
      [:t :layout/theme-contrast {:ratio (.toFixed lowest 1)}]]]))

(defn layout-import-preview [preview status]
  (when preview
    [:section.layout-import-preview
     {:aria-labelledby "layout-preview-title"}
     [:span.eyebrow [:t :layout/preview-eyebrow]]
     [:h4#layout-preview-title (:name preview)]
     [:p.setting-note [:t :layout/preview-copy]]
     [:div.theme-preview-grid
      (for [scheme [:dark :light]]
        (theme-preview-card scheme (get-in preview [:schemes scheme])))]
     [:div.button-row
      [:button.text-button
       {:type "button"
        :disabled (= status :installing)
        :on {:click [[:layout/import-cancelled]]}}
       [:t :action/cancel]]
      [:button.secondary-button
       {:type "button"
        :disabled (= status :installing)
        :on {:click [[:layout/import-confirmed]]}}
       (icons/icon :upload)
       [:span [:t (if (= status :installing) :layout/installing :layout/install)]]]]]))

(defn settings [state]
  (let [settings (:settings state)
        reset? (get-in state [:ui :reset-confirmation?])]
    [:div.drawer-scrim {:on {:click [[:settings/scrim-click [:event/dom-event]]]}}
     [:aside.settings-drawer
      {:role "dialog"
       :aria-modal "true"
       :aria-labelledby "settings-title"
       :on {:keydown [[:settings/key-down [:event/key] [:event/dom-event]]]}
       :replicant/on-mount [[:settings/mounted [:event/node]]]}
      [:header.drawer-header
       [:div
        [:span.eyebrow "PRETYPIST"]
        [:h2#settings-title [:t :settings/title]]]
       [:button.icon-button
        {:id "settings-close"
         :type "button"
         :aria-label [:t :action/close]
         :title [:t :action/close]
         :on {:click [[:settings/close]]}}
        (icons/icon :close)]]
      [:div.drawer-body
       [:section.settings-section
        [:h3 [:t :settings/practice]]
        (select-setting "run-length" :settings/run-length (:run-length settings)
                        [[:short :settings/short] [:medium :settings/medium] [:long :settings/long]])
        [:p.setting-note
         [:t (if (and (:adaptive? settings) (= :automatic (:manual-difficulty settings)))
               :settings/adapting :settings/custom-difficulty)]]
        (disclosure state :difficulty [:t :settings/difficulty-controls]
                    [:div
                     (toggle-setting "adaptive?" :settings/adaptive (:adaptive? settings))
                     (select-setting "manual-difficulty" :settings/difficulty (:manual-difficulty settings)
                                     [[:automatic :settings/automatic] [:easy :settings/easy]
                                      [:standard :settings/standard] [:challenge :settings/challenge]])])]
       [:section.settings-section
        [:h3 [:t :settings/appearance]]
        (select-setting "appearance" :settings/color-scheme (:appearance settings)
                        [[:system :settings/system] [:dark :settings/dark] [:light :settings/light]])
        (toggle-setting "reduced-motion?" :settings/reduced-motion (:reduced-motion? settings))
        (disclosure state :scene [:t :settings/keyboard-details]
                    [:div
                     (range-setting "ambient-intensity" :settings/ambient (:ambient-intensity settings))
                     (range-setting "glow-intensity" :settings/glow (:glow-intensity settings))
                     (toggle-setting "layer-legends?" :settings/legends (:layer-legends? settings))])]

       [:section.settings-section
        [:h3 [:t :settings/keyboard]]
        [:div.layout-list
         (map #(layout-row state %) (get-in state [:layout :installed]))]
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
        (layout-import-preview (get-in state [:ui :layout-import-preview])
                               (get-in state [:layout :status]))
        (when (seq (get-in state [:ui :validation-errors]))
          [:div.validation-errors {:role "alert"}
           (icons/icon :warning)
           [:div
            [:strong [:t :layout/invalid]]
            [:ul (for [[idx error] (map-indexed vector (get-in state [:ui :validation-errors]))]
                   [:li {:replicant/key idx}
                    [:code (if (seq (:path error)) (pr-str (:path error)) "package")]
                    [:span (:message error)]])]]])]

       [:section.settings-section
        [:h3 [:t :pwa/title]]
        [:p.setting-note [:t (if (get-in state [:pwa :offline-ready?])
                              :pwa/offline-ready :pwa/offline-preparing)]]
        (when (get-in state [:pwa :installed?]) [:p.setting-note [:t :pwa/installed]])
        (when (and (not (get-in state [:pwa :installed?]))
                   (not (get-in state [:pwa :installable?])))
          [:p.setting-note [:t :pwa/install-browser]])
        (pwa/actions state)]
       [:section.settings-section.danger-zone
        [:h3 [:t :settings/progress]]
        (if reset?
          [:div.reset-confirmation
           [:strong [:t :settings/reset-title]]
           [:p [:t :settings/reset-copy]]
           [:div.button-row
            [:button.secondary-button {:type "button" :on {:click [[:settings/reset-cancelled]]}} [:t :action/cancel]]
            [:button.danger-button {:type "button" :on {:click [[:progress/reset-confirmed]]}} [:t :action/confirm-reset]]]]
          [:button.text-button {:type "button" :on {:click [[:settings/reset-requested]]}}
           [:t :action/reset]])]]]]))
