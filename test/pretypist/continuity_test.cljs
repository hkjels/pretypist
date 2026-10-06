(ns pretypist.continuity-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.continuity :as continuity]))

(def now 2000000000000)

(deftest trailing-week-is-bounded-and-rejects-legacy-monotonic-timestamps-test
  (let [day (* 24 60 60 1000)
        summaries [{:id :legacy :completed-at 4200}
                   {:id :old :completed-on (- now (* 8 day))}
                   {:id :recent :completed-on (- now day)}
                   {:id :future :completed-on (+ now day)}]]
    (is (= [:recent] (mapv :id (continuity/recent-week summaries now))))))

(deftest continuation-claims-only-supported-trends-test
  (let [day (* 24 60 60 1000)
        sample (fn [index rhythm accuracy]
                 {:completed-on (- now (* (- 3 index) day))
                  :wpm (+ 38 index)
                  :rhythm-consistency rhythm
                  :accuracy accuracy})]
    (testing "material rhythm improvement wins when there is enough evidence"
      (is (= :continuity/trend-rhythm
             (get-in (continuity/insight
                      [(sample 0 0.61 0.97)
                       (sample 1 0.67 0.97)
                       (sample 2 0.73 0.98)] nil now)
                     [:trend :key]))))
    (testing "small changes are described as cadence, not improvement"
      (is (= :continuity/trend-cadence
             (get-in (continuity/insight
                      [(sample 0 0.70 0.98)
                       (sample 1 0.71 0.98)
                       (sample 2 0.70 0.98)] nil now)
                     [:trend :key]))))
    (testing "one run makes no trend claim and keeps a pending focus actionable"
      (let [result (continuity/insight [(sample 2 0.7 0.98)] {:target ","} now)]
        (is (= :continuity/session-singular (get-in result [:headline :key])))
        (is (= :continuity/trend-return (get-in result [:trend :key])))
        (is (true? (:repeat-focus? result)))))))

