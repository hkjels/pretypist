(ns pretypist.persistence-test
  (:require [cljs.test :refer-macros [deftest is]]
            [pretypist.onboarding :as onboarding]
            [pretypist.persistence :as persistence]
            [pretypist.typing.adaptive :as adaptive]))

(deftest settings-theme-preference-migration-test
  (let [migrated (persistence/migrate-settings
                  {:version 1 :language :nb :mode :chunks})]
    (is (= 2 (:version migrated)))
    (is (= :system (:appearance migrated)))
    (is (= :nb (:language migrated)))
    (is (= :chunks (:mode migrated))))
  (is (= :system (:appearance (persistence/migrate-settings
                               {:version 2 :appearance :sepia})))))

(deftest progress-migration-has-one-versioned-boundary-test
  (is (= adaptive/progress-version
         (:version (persistence/migrate-progress
                    {:version 1
                     :difficulty adaptive/initial-difficulty
                     :calibration [] :recent []}))))
  (is (nil? (persistence/migrate-progress {:version 99 :opaque true}))))

(deftest onboarding-checkpoints-have-a-versioned-migration-boundary-test
  (is (= :complete (:status (onboarding/migrate nil true))))
  (is (= :active (:status (onboarding/migrate nil false))))
  (is (= :setup (:step (onboarding/migrate {:version 99 :step :opaque} false)))))
