(ns pretypist.state-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pretypist.state :as state]))

(deftest landing-does-not-inherit-explore-pointer-controls-test
  (let [application (-> (state/initial-state)
                        (assoc-in [:landing :status] :complete)
                        (assoc-in [:onboarding :status] :complete)
                        (assoc-in [:settings :mode] :layers)
                        (assoc-in [:layer-trainer :stage] :explore))]
    (is (state/layer-orbit-enabled? application))
    (is (not (state/layer-orbit-enabled?
              (assoc-in application [:landing :status] :active))))))

(deftest passage-length-never-cuts-a-sentence-mid-clause-test
  (let [passage {:id :sentences
                 :chunks ["The first phrase" "finishes here."
                          "A second phrase" "continues for a while" "and ends here."]}
        short (state/trim-passage (assoc-in (state/initial-state)
                                            [:settings :run-length] :short)
                                  passage)
        medium (state/trim-passage (assoc-in (state/initial-state)
                                             [:settings :run-length] :medium)
                                   passage)]
    (is (= ["The first phrase" "finishes here."] (:chunks short)))
    (is (= (:chunks passage) (:chunks medium)))))

(deftest state-attaches-an-explainable-selection-to-the-passage-test
  (let [application (-> (state/initial-state)
                        (assoc :corpora {:en [{:id :a :title "A" :language :en
                                              :tags #{:reviewed :coherent-prose}
                                              :chunks ["A calm road."]}]
                                           :nb []}))
        selected (state/select-passage application)]
    (is (= :a (:id selected)))
    (is (= :punctuation-density (get-in selected [:selection :active-dimension])))
    (is (= :selection/flow (get-in selected [:selection :explanation :key])))))

(deftest consolidation-selection-uses-its-captured-difficulty-test
  (let [quiet {:id :quiet :title "Quiet" :language :en
               :tags #{:reviewed :coherent-prose}
               :chunks ["A calm road runs beside the river"]}
        dense {:id :dense :title "Dense" :language :en
               :tags #{:reviewed :coherent-prose}
               :chunks ["Wait, listen: rain, wind, light; all return!"]}
        application (-> (state/initial-state)
                        (assoc :corpora {:en [quiet dense] :nb []})
                        (assoc-in [:progress :difficulty :flow :punctuation-density] 1))
        prescription {:kind :boundary :target :word :reason :stall
                      :mode :flow :difficulty {:punctuation-density 0}}
        selected (state/select-consolidation-passage application prescription)]
    (is (= :quiet (:id selected)))
    (is (= :word (get-in selected [:selection :active-dimension])))))

(deftest imported-layout-training-targets-come-from-its-own-strokes-test
  (let [application (-> (state/initial-state)
                        (assoc-in [:layout :active]
                                  {:keys [{:id :layer-key} {:id :a-key}
                                          {:id :comma-key} {:id :period-key}]
                                   :layers {:base {:bindings
                                                   {:layer-key {:actions
                                                                {:hold [:layer :momentary :sym]}}}}
                                            :sym {:bindings {}}}
                                   :strokes {"A" [[:hold :layer-key] [:tap :a-key]
                                                   [:release :layer-key]]
                                             "," [[:tap :comma-key]]
                                             "." [[:tap :period-key]]}}))
        targets (state/onboarding-targets application)]
    (is (= ["A" "," "."] (mapv :character targets)))
    (is (= [:sym :a-key] ((juxt :layer :key-id) (first targets))))
    (is (not-any? #{"æ" "ø" "å"} (map :character targets)))))
