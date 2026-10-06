(ns pretypist.display.status-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.display.status :as status]
            [pretypist.typing.input :as input]))

(deftest rolling-wpm-history-test
  (is (= (vec (range 2 12))
         (reduce status/append-wpm [] (range 12))))
  (is (= 255 (last (status/append-wpm [] 999))))
  (is (= 0 (last (status/append-wpm [] -12)))))

(deftest running-session-produces-a-five-character-word-sample-test
  (let [session (-> (input/new-session {:id :display :title "Display" :chunks ["abcdefghij"]}
                                       :flow 0)
                    (input/enter-text "abcde" 6000))]
    (is (= 10 (count (status/sample-session-wpm (status/empty-wpm-history)
                                                session 6000))))
    (is (= 10 (last (status/sample-session-wpm (status/empty-wpm-history)
                                               session 6000))))))

(deftest display-presentation-keeps-simulation-honest-test
  (let [defaults {:source :simulated
                  :batteries {:left {:percent 82}}
                  :wpm {:current 42 :history [42]}
                  :layer :base}
        base {:layout {:active {:layout/default-layer :base
                                :display/default-status defaults}}
              :settings {:mode :flow}
              :layer-trainer {:active-layer :sym :transfer? false}
              :display {:wpm-history []}}
        preview (status/presentation base)
        live (status/presentation (assoc-in base [:display :wpm-history]
                                             [0 0 0 0 12 18 24 31 36 39]))]
    (testing "package previews are retained before a session"
      (is (= 42 (get-in preview [:wpm :current])))
      (is (= :simulated (get-in preview [:sources :wpm])))
      (is (= 82 (get-in preview [:batteries :left :percent]))))
    (testing "application-derived WPM replaces only the simulated WPM signal"
      (is (= 39 (get-in live [:wpm :current])))
      (is (= :pretypist (get-in live [:sources :wpm])))
      (is (= :base (:layer live))))
    (testing "layer Explore reports the selected semantic layer"
      (is (= :sym
             (:layer (status/presentation
                      (-> base
                          (assoc-in [:settings :mode] :layers)
                          (assoc-in [:layer-trainer :active-layer] :sym)))))))
    (testing "landing chapters can stage a semantic layer on nice!view"
      (is (= :num
             (:layer (status/presentation
                      (assoc base :landing {:status :active :chapter :rhythm}))))))))
