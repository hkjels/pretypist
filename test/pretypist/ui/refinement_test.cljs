(ns pretypist.ui.refinement-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pretypist.nexus :as nexus]
            [pretypist.state :as state]
            [pretypist.ui :as ui]
            [pretypist.ui.disclosure :as disclosure]
            [pretypist.ui.trainer :as trainer]))

(defn classes [state index]
  (set (:class (second (trainer/character state index "å")))))

(deftest current-word-emphasis-preserves-correctness-and-horizon-masking
  (let [s (assoc (state/initial-state) :session {:text "Blå fjell." :cursor 1 :chunks ["Blå fjell."]})]
    (is (contains? (classes s 2) "is-current-word"))
    (is (not (contains? (classes s 0) "is-current-word")))
    (is (contains? (classes s 0) "is-complete"))
    (is (not (contains? (classes s 4) "is-current-word")))
    (is (contains? (classes (assoc-in s [:session :pending-error?] true) 1) "is-error"))
    (is (contains? (classes (-> s
                               (assoc-in [:settings :mode] :horizon)
                               (assoc-in [:session :calibration-difficulty] {:mask-stage 5})) 2)
                   "is-masked-hard"))))

(deftest disclosures-are-pure-nexus-state-transitions
  (let [s (state/initial-state)
        opened (second (first (nexus/toggle-disclosure s :difficulty)))
        closed (second (first (nexus/toggle-disclosure opened :difficulty)))
        [_ _ button panel] (disclosure/disclosure s :difficulty [:t :settings/difficulty-controls] [:p "content"])]
    (is (= (:settings s) (:settings opened)))
    (is (= (:progress s) (:progress opened)))
    (is (true? (get-in opened [:ui :disclosures :difficulty])))
    (is (false? (get-in closed [:ui :disclosures :difficulty])))
    (is (= false (:aria-expanded (second button))))
    (is (= "disclosure-difficulty" (:aria-controls (second button))))
    (is (true? (:hidden (second panel))))
    (is (nil? (nexus/toggle-disclosure s :unknown)))))

(deftest mode-navigation-has-one-stable-location
  (let [s (assoc (state/initial-state) :session {:title "The workshop"})
        maps-in (fn [view] (filter map? (tree-seq coll? seq view)))
        header-ids (keep :id (maps-in (ui/header s)))
        idle-ids (keep :id (maps-in (trainer/idle-view s)))]
    (is (= 1 (count (filter #{"header-mode"} header-ids))))
    (is (not-any? #{"mode-flow" "mode-horizon" "mode-chunks" "mode-layers"} idle-ids))))
