(ns pretypist.ui.disclosure
  (:require [pretypist.ui.icons :as icons]))

(defn disclosure [state id label content]
  (let [open? (true? (get-in state [:ui :disclosures id]))
        panel-id (str "disclosure-" (name id))]
    [:div.disclosure {:class [(when open? "is-open")]}
     [:button.disclosure-toggle
      {:type "button"
       :aria-expanded open?
       :aria-controls panel-id
       :on {:click [[:ui/toggle-disclosure id]]}}
      [:span label]
      (icons/icon :chevron-down)]
     [:div.disclosure-content {:id panel-id :hidden (not open?)} content]]))
