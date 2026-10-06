(ns pretypist.typing.analysis-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.typing.analysis :as analysis]
            [pretypist.typing.input :as input]
            [pretypist.typing.metrics :as metrics]))

(def layout
  {:layers {:base {:bindings {:num-thumb {:actions {:hold [:layer :momentary :num]}}}}
            :num {:bindings {}}}
   :strokes {"a" [[:tap :ka]]
             "å" [[:hold :num-thumb] [:tap :kaa] [:release :num-thumb]]
             " " [[:tap :space]]
             "b" [[:tap :kb]]}})

(defn observed-session []
  (-> (input/new-session {:id :analysis :title "Analyse" :chunks ["aå" "b"]} :flow 0)
      (input/observe-key :ka "KeyA")
      (input/enter-character "a" 900)
      (input/observe-key :kaa "KeyQ")
      (input/enter-character "x" 1050)
      (input/backspace 1100)
      (input/observe-key :kaa "KeyQ")
      (input/enter-character "å" 1650)
      (input/observe-key :space "Space")
      (input/enter-character " " 1750)
      (input/observe-key :kb "KeyB")
      (input/enter-character "b" 3500)))

(deftest observations-preserve-unicode-source-and-gesture-provenance-test
  (let [observations (analysis/session-observations (observed-session) layout)
        aa (filterv #(= "å" (:character %)) observations)
        accepted-aa (some #(when (:accepted? %) %) aa)]
    (is (= 2 (count aa)))
    (is (= :layer-hold (:stroke-kind accepted-aa)))
    (is (= :inferred (:gesture-source accepted-aa)))
    (is (= [:base :num] (:transition accepted-aa)))
    (is (= :kaa (:physical-key-id accepted-aa)))
    (is (= "KeyQ" (:physical-code accepted-aa)))
    (is (= 550 (:latency-ms accepted-aa)))))

(deftest profile-is-bounded-and-separates-observed-from-inferred-test
  (let [observations (analysis/session-observations (observed-session) layout)
        profile (analysis/update-profile (analysis/initial-profile) observations 3500)]
    (is (= 1 (:sessions profile)))
    (is (= :observed (get-in profile [:physical-keys :kaa :source])))
    (is (= :inferred (get-in profile [:strokes "å" :source])))
    (is (= :inferred (get-in profile [:transitions [:base :num] :source])))
    (is (= 2 (get-in profile [:characters "å" :attempts])))
    (is (= 0.5 (get-in profile [:characters "å" :error-rate])))
    (is (nil? (get-in profile [:characters "a" :latency-ms]))
        "Reading time before the first character is not learned as key latency")
    (let [many (mapv (fn [idx]
                       {:character (str "character-" idx)
                        :accepted? true :cursor (inc idx) :at idx :latency-ms 100
                        :physical-key-id (keyword (str "key-" idx))})
                     (range 160))
          bounded (analysis/update-profile profile many 5000)]
      (is (= 128 (count (:characters bounded))))
      (is (= 96 (count (:physical-keys bounded)))))))

(deftest one-startup-delay-does-not-become-a-weak-character-test
  (let [session (-> (input/new-session {:id :startup :title "Startup" :chunks ["ab"]} :flow 0)
                    (input/enter-character "a" 5000)
                    (input/enter-character "b" 5200))
        observations (analysis/session-observations session layout)]
    (is (nil? (analysis/select-weakness observations)))))

(deftest corrected-error-produces-one-concrete-prescription-test
  (let [session (-> (input/new-session {:id :error :title "Error" :chunks ["ab"]} :flow 0)
                    (input/enter-character "x" 100)
                    (input/backspace 120)
                    (input/enter-character "a" 220)
                    (input/enter-character "b" 320))
        summary (metrics/summarize session)
        result (analysis/analyze-session
                {:session session :layout layout :summary summary :language :en
                 :profile (analysis/initial-profile)
                 :difficulty {:punctuation-density 0.2}})]
    (is (= "a" (get-in result [:weakness :target])))
    (is (= :errors (get-in result [:weakness :reason])))
    (is (= :character (get-in result [:prescription :kind])))
    (is (= {:punctuation-density 0.2}
           (get-in result [:prescription :difficulty])))
    (is (false? (get-in result [:prescription :difficulty-change?])))))

(deftest rhythm-chart-is-normalized-and-marks-evidence-test
  (let [session (observed-session)
        observations (analysis/session-observations session layout)
        chart (analysis/rhythm-chart session observations)
        kinds (set (map :kind (:markers chart)))]
    (is (every? #(<= 0 (:x %) 1) (:points chart)))
    (is (every? #(<= 0 (:y %) 1) (:points chart)))
    (is (contains? kinds :error))
    (is (contains? kinds :stall))
    (is (contains? kinds :chunk))
    (is (contains? kinds :gesture))))

(deftest profile-latency-uses-an-ewma-test
  (let [first (analysis/update-evidence nil {:accepted? true :latency-ms 100} 1)
        second (analysis/update-evidence first {:accepted? true :latency-ms 200} 2)]
    (is (< (js/Math.abs (- 128 (:latency-ms second))) 0.0001))))
