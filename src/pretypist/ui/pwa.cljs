(ns pretypist.ui.pwa
  (:require [pretypist.pwa :as pwa]))

(defn actions [state]
  (let [{:keys [installable? update-ready?]} (:pwa state)
        safe? (pwa/update-safe? state)]
    (when (or installable? update-ready?)
      [:div.pwa-actions
       (when installable?
         [:button.text-button {:type "button" :on {:click [[:pwa/install]]}}
          [:t :pwa/install]])
       (when update-ready?
         (if safe?
           [:button.text-button {:type "button" :on {:click [[:pwa/update]]}}
            [:t :pwa/update]]
           [:p.setting-note [:t :pwa/update-after-session]]))])))
