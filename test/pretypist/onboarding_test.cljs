(ns pretypist.onboarding-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.onboarding :as onboarding]))

(deftest first-run-progression-is-small-and-explicit-test
  (let [started (onboarding/start (onboarding/initial-state) 100)
        baseline-reflection (-> started
                                onboarding/start-session
                                (onboarding/record-session
                                 {:completed? true :wpm 34 :accuracy 0.99
                                  :stall-count 0 :chart {:points []}}))
        rhythm (onboarding/continue baseline-reflection)
        layers (-> rhythm
                   onboarding/start-session
                   (onboarding/record-session
                    {:completed? true :wpm 35 :accuracy 1 :stall-count 1})
                   onboarding/continue)]
    (is (= [:baseline :reflection] [(:step baseline-reflection) (:phase baseline-reflection)]))
    (is (= [:rhythm :intro] [(:step rhythm) (:phase rhythm)]))
    (is (= [:layers :intro] [(:step layers) (:phase layers)]))
    (is (= 2 (count (:records layers))))))

(deftest three-gestures-gate-natural-transfer-test
  (let [started (-> (onboarding/initial-state)
                    (onboarding/start 10)
                    (assoc :step :layers)
                    onboarding/begin-gestures)
        one (onboarding/complete-gesture started)
        two (onboarding/complete-gesture one)
        three (onboarding/complete-gesture two)]
    (is (= :gestures (:phase one)))
    (is (= 2 (:gesture-index two)))
    (is (= onboarding/required-gestures (:gesture-index three)))
    (is (= :transfer-ready (:phase three)))))

(deftest migration-resumes-only-from-a-safe-checkpoint-test
  (testing "an interrupted prose run restarts instead of keeping partial timing"
    (is (= {:phase :intro :interrupted? true}
           (select-keys (onboarding/migrate
                         {:version 1 :status :active :step :rhythm :phase :running}
                         false)
                        [:phase :interrupted?]))))
  (testing "an interrupted layer transfer keeps completed gesture work"
    (let [migrated (onboarding/migrate
                    {:version 1 :status :active :step :layers :phase :running
                     :gesture-index 3}
                    false)]
      (is (= :transfer-ready (:phase migrated)))
      (is (= 3 (:gesture-index migrated)))))
  (testing "existing users are not forced through newly-added onboarding"
    (is (= :complete (:status (onboarding/migrate nil true)))))
  (testing "new installs begin at layout and language setup"
    (is (= [:setup :intro]
           ((juxt :step :phase) (onboarding/migrate nil false))))))

(deftest completion-is-versioned-and-terminal-test
  (let [complete (onboarding/finish (onboarding/initial-state) 500)]
    (is (= onboarding/version (:version complete)))
    (is (= [:complete :complete 500]
           ((juxt :status :step :completed-at) complete)))))

(deftest skipping-is-explicit-and-does-not-invent-calibration-test
  (let [skipped (onboarding/skip (onboarding/initial-state) 700)]
    (is (true? (:skipped? skipped)))
    (is (= [] (:records skipped)))
    (is (= :complete (:status skipped)))))
