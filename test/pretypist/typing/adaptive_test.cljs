(ns pretypist.typing.adaptive-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.typing.adaptive :as adaptive]))

(def success-summary
  {:completed? true :wpm 40 :accuracy 0.99 :stall-count 1})

(deftest calibration-and-baseline-test
  (let [p1 (adaptive/record-run (adaptive/initial-progress) :flow success-summary true)
        p2 (adaptive/record-run p1 :flow (assoc success-summary :wpm 44) true)]
    (is (= 1 (count (:calibration p1))))
    (is (= 40 (:baseline-wpm p1)))
    (is (= 41 (:baseline-wpm p2)))
    (is (= 2 (count (:calibration p2))))))

(deftest successful-run-detection-test
  (is (adaptive/successful-run? success-summary 45))
  (is (not (adaptive/successful-run? (assoc success-summary :accuracy 0.95) 40)))
  (is (not (adaptive/successful-run? (assoc success-summary :stall-count 8) 40)))
  (is (not (adaptive/successful-run? (assoc success-summary :wpm 20) 40))))

(deftest exactly-one-small-step-test
  (let [before (adaptive/initial-progress)
        after (adaptive/record-run before :chunks success-summary true)
        before-difficulty (:difficulty before)
        after-difficulty (:difficulty after)]
    (is (= 0.9 (get-in after-difficulty [:chunks :boundary-opacity])))
    (is (= 2 (get-in after-difficulty [:chunks :max-words])))
    (is (= (:flow before-difficulty) (:flow after-difficulty)))
    (is (= :adaptive/chunks-boundary (:next-change after)))))

(deftest no-progression-after-failure-test
  (let [before (assoc (adaptive/initial-progress) :baseline-wpm 40)
        after (adaptive/record-run before :horizon
                                   (assoc success-summary :accuracy 0.91) true)]
    (is (= (:difficulty before) (:difficulty after)))
    (is (= :adaptive/consolidation (:next-change after)))))

(deftest horizon-unlock-needs-calibration-and-speed-test
  (let [progress {:calibration (vec (repeat 3 success-summary))
                  :baseline-wpm 40}]
    (is (adaptive/horizon-unlocked? progress))
    (is (not (adaptive/horizon-unlocked? (assoc progress :baseline-wpm 20))))))

(deftest effective-difficulty-respects-manual-override-test
  (let [progress (-> (adaptive/initial-progress)
                     (assoc-in [:difficulty :flow :punctuation-density] 0.3))
        calibrated (assoc progress
                          :baseline-wpm 40
                          :calibration (vec (repeat 3 success-summary)))]
    (is (= {:punctuation-density 0.3}
           (adaptive/effective-difficulty progress :flow :automatic)))
    (is (= {:boundary-opacity 0.65 :max-words 3 :step 0}
           (adaptive/effective-difficulty progress :chunks :standard)))
    (is (= 0
           (:mask-stage (adaptive/effective-difficulty progress :horizon :challenge)))
        "A manual challenge cannot skip Horizon calibration")
    (is (= 5
           (:mask-stage (adaptive/effective-difficulty calibrated :horizon :challenge)))
        "The requested manual preset applies once Horizon is unlocked")))

(deftest calibration-keeps-a-rolling-eligible-window-test
  (let [summaries (mapv #(assoc success-summary :wpm %) [32 36 40 44])
        progress (reduce #(adaptive/record-run %1 :flow %2 false)
                         (adaptive/initial-progress)
                         summaries)
        rejected (adaptive/record-run progress :flow
                                      (assoc success-summary :wpm 80 :accuracy 0.90)
                                      false)]
    (is (= [36 40 44] (mapv :wpm (:calibration progress))))
    (is (= 3 (count (:calibration progress))))
    (is (< (js/Math.abs (- 37.0625 (:baseline-wpm progress))) 0.0001)
        "The baseline is an EMA, so a single fast run cannot replace it")
    (is (= (:calibration progress) (:calibration rejected))
        "Low-accuracy runs do not enter calibration")
    (is (= (:baseline-wpm progress) (:baseline-wpm rejected)))
    (is (= 5 (count (:recent rejected))))
    (is (= 0.90 (:accuracy (peek (:recent rejected))))
        "Rejected calibration runs remain available in recent session history")))

(deftest version-one-progress-migrates-with-an-empty-performance-profile-test
  (let [old {:version 1
             :calibration [] :baseline-wpm 38 :recent []
             :difficulty adaptive/initial-difficulty :next-change nil}
        migrated (adaptive/migrate-progress old)]
    (is (= adaptive/progress-version (:version migrated)))
    (is (= 38 (:baseline-wpm migrated)))
    (is (= 1 (get-in migrated [:profile :version])))
    (is (nil? (:next-prescription migrated)))))

(deftest consolidation-records-evidence-without-changing-difficulty-test
  (let [before (assoc (adaptive/initial-progress) :baseline-wpm 40)
        after (adaptive/record-consolidation-run before success-summary)]
    (is (= (:difficulty before) (:difficulty after)))
    (is (= :adaptive/consolidation-complete (:next-change after)))
    (is (= 1 (count (:recent after))))
    (is (nil? (:next-prescription after)))))

(deftest one-extreme-run-cannot-replace-the-recent-baseline-test
  (let [before (assoc (adaptive/initial-progress) :baseline-wpm 40)
        after (adaptive/record-run before :flow (assoc success-summary :wpm 400) false)]
    (is (= 45 (:baseline-wpm after))
        "The eligible WPM sample is clamped before entering the EMA")))
