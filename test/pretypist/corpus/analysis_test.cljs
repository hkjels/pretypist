(ns pretypist.corpus.analysis-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pretypist.corpus.analysis :as corpus]))

(def layout
  {:package/id "test-layout"
   :package/revision 3
   :keys [{:id :shift :hand :left :finger :left-thumb}
          {:id :num :hand :right :finger :right-thumb}
          {:id :aa :hand :left :finger :left-index}
          {:id :s :hand :right :finger :right-index}
          {:id :e :hand :left :finger :left-middle}
          {:id :comma :hand :right :finger :right-middle}
          {:id :period :hand :right :finger :right-middle}]
   :layers {:base {:bindings
                   {:shift {:actions {:tap [:one-shot :modifier :shift]}}
                    :num {:actions {:hold [:layer :momentary :num]}}}}
            :num {:bindings {}}}
   :strokes {"Å" [[:one-shot :shift] [:hold :num] [:tap :aa] [:release :num]]
             "s" [[:tap :s]]
             "e" [[:tap :e]]
             "," [[:double-tap :comma]]
             "." [[:tap :period]]}})

(def passage
  {:id :nb/test
   :title "Test"
   :language :nb
   :tags #{:reviewed :coherent-prose :norske-bokstaver}
   :chunks ["Åse," "Åse."]})

(deftest deterministic-analysis-covers-language-prose-and-layout-mechanics-test
  (let [analysis (corpus/analyze-passage layout passage)]
    (is (= analysis (corpus/analyze-passage layout passage)))
    (is (= [:coherent-prose :norske-bokstaver :reviewed] (:tags analysis)))
    (is (= {:characters 9 :words 2 :sentences 1} (:text analysis)))
    (is (= [1 1] (get-in analysis [:chunks :word-counts])))
    (is (= 2 (get-in analysis [:letters :norwegian "å"])))
    (is (= {:comma 1 :period 1} (get-in analysis [:punctuation :types])))
    (is (= 2 (get-in analysis [:layout :stroke-kinds :one-shot])))
    (is (= 1 (get-in analysis [:layout :stroke-kinds :tap-dance])))
    (is (= [:num] (get-in analysis [:layout :required-layers])))
    (is (= 2 (get-in analysis [:layout :layer-transitions [:base :num]])))
    (is (pos? (get-in analysis [:layout :hand-alternations])))
    (is (empty? (get-in analysis [:layout :unresolved-characters])))))

(deftest corpus-envelope-and-enrichment-are-versioned-test
  (let [metadata (corpus/analyze-corpora layout [passage])
        enriched (first (corpus/enrich-passages [passage] metadata))]
    (is (= corpus/metadata-version (:corpus/metadata-version metadata)))
    (is (= "test-layout" (:layout/id metadata)))
    (is (= 3 (:layout/revision metadata)))
    (is (= "test-layout" (get-in enriched [:corpus/analysis :layout-id])))
    (is (= :gentle (get-in enriched [:corpus/analysis :reading :band])))))
