(ns pretypist.corpus.selector-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pretypist.corpus.selector :as selector]))

(def base-context
  {:mode :flow
   :difficulty {:punctuation-density 0.2}
   :recent-summaries []})

(defn passage [id text]
  {:id id
   :title (name id)
   :language :nb
   :tags #{:reviewed :coherent-prose}
   :chunks [text]
   :corpus/analysis {:reading {:band :standard :score 53}
                     :layout {:stroke-kinds {:direct (count text)}}}})

(deftest focused-selection-prefers-relevant-fresh-prose-test
  (let [source (passage :source "På en ås.")
        fresh (passage :fresh "Blå åser lå nær våte båter.")
        irrelevant (passage :irrelevant "Veien gikk mot byen.")
        selection (selector/select-passage
                   [source fresh irrelevant]
                   (assoc base-context
                          :prescription {:kind :character :target "å" :reason :stall}
                          :current-passage-id :source
                          :recent-summaries [{:passage-id :source}]))]
    (is (= :fresh (get-in selection [:passage :id])))
    (is (= 5 (get-in selection [:evidence :target-occurrences])))
    (is (= :target-character (:active-dimension selection)))
    (is (= :selection/focus-character (get-in selection [:explanation :key])))
    (is (false? (:difficulty-change? selection)))))

(deftest normal-selection-fits-the-one-active-mode-dimension-test
  (let [quiet (passage :quiet "A calm road runs beside the river")
        dense (passage :dense "Wait, listen: rain, wind, light; all return!")
        easy (selector/select-passage [quiet dense]
                                      (assoc base-context :difficulty {:punctuation-density 0}))
        challenge (selector/select-passage [quiet dense]
                                           (assoc base-context :difficulty {:punctuation-density 1}))]
    (is (= :quiet (get-in easy [:passage :id])))
    (is (= :dense (get-in challenge [:passage :id])))
    (is (= :punctuation-density (:active-dimension easy)))
    (is (= :selection/flow (get-in easy [:explanation :key])))))

(deftest exposure-and-deterministic-id-break-ties-test
  (let [a (passage :a "A still lake.")
        b (passage :b "A still lake.")]
    (is (= :a (get-in (selector/select-passage [b a] base-context) [:passage :id])))
    (is (= :b (get-in (selector/select-passage
                       [a b]
                       (assoc base-context :recent-summaries [{:passage-id :a}]))
                      [:passage :id])))))

(deftest excessive-focus-and-unrelated-complexity-are-visible-penalties-test
  (let [candidate (-> (passage :repeat "ååååååååå")
                      (assoc-in [:corpus/analysis :layout :stroke-kinds]
                                {:direct 2 :layer-hold 9 :one-shot 4}))
        score (selector/score-passage
               candidate
               (assoc base-context
                      :prescription {:kind :character :target "å"
                                     :reason :layer-transition}))]
    (is (neg? (get-in score [:components :repetition-penalty])))
    (is (neg? (get-in score [:components :simultaneous-difficulty-penalty])))
    (is (= :layer-transition (:active-dimension score)))))

(deftest mechanical-penalty-is-not-borrowed-by-an-imported-layout-test
  (let [candidate (-> (passage :mechanics "åsen ligger stille")
                      (assoc-in [:corpus/analysis :layout-id] "modified-engram")
                      (assoc-in [:corpus/analysis :layout :stroke-kinds]
                                {:direct 2 :layer-hold 8}))
        prescription {:kind :character :target "å" :reason :layer-transition}
        built-in (selector/score-passage
                  candidate (assoc base-context :layout-id "modified-engram"
                                   :prescription prescription))
        imported (selector/score-passage
                  candidate (assoc base-context :layout-id "another-layout"
                                   :prescription prescription))]
    (is (neg? (get-in built-in [:components :simultaneous-difficulty-penalty])))
    (is (zero? (get-in imported [:components :simultaneous-difficulty-penalty])))))

(deftest character-focus-never-selects-prose-that-omits-the-character-test
  (is (nil? (selector/select-passage
             [(passage :irrelevant "The road stays quiet.")]
             (assoc base-context
                    :prescription {:kind :character :target "æ" :reason :stall})))))
