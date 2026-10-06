(ns pretypist.landing-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.landing :as landing]))

(deftest new-and-existing-user-migration-test
  (testing "new installs see the story before onboarding"
    (is (= {:status :active :return-to :onboarding :seen? false}
           (select-keys (landing/migrate nil false)
                        [:status :return-to :seen?]))))
  (testing "existing users keep their fast path"
    (is (= {:status :complete :return-to :trainer :seen? true}
           (select-keys (landing/migrate nil true)
                        [:status :return-to :seen?])))))

(deftest landing-can-be-revisited-and-finished-test
  (let [revisited (landing/open (landing/completed-state) :trainer)
        moved (landing/enter-chapter revisited :layout)
        finished (landing/finish moved)]
    (is (= [:active :read-ahead :trainer]
           ((juxt :status :chapter :return-to) revisited)))
    (is (= 3 (landing/chapter-index (:chapter moved))))
    (is (= :sym (landing/active-layer moved)))
    (is (= [:complete true]
           ((juxt :status :seen?) finished)))))

(deftest unknown-chapters-never-enter-state-test
  (let [state (landing/initial-state)]
    (is (= state (landing/enter-chapter state :unknown)))))

(deftest scroll-progress-uses-actual-chapter-heights
  (is (= 0 (landing/scroll-progress [] 0)))
  (is (= 0 (landing/scroll-progress [0 720 1600] -40)))
  (is (= 0.5 (landing/scroll-progress [0 720 1600] 360)))
  (is (= 1.5 (landing/scroll-progress [0 720 1600] 1160)))
  (is (= 2 (landing/scroll-progress [0 720 1600] 2200))))
