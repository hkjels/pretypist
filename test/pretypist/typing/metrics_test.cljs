(ns pretypist.typing.metrics-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.typing.input :as input]
            [pretypist.typing.metrics :as metrics]))

(deftest conventional-wpm-test
  (is (= 50 (metrics/wpm 250 0 60000)))
  (is (= 20 (metrics/wpm 100 0 60000))))

(deftest accuracy-test
  (is (= 0.98 (metrics/accuracy {:correct-keys 98 :total-keys 100})))
  (is (= 1 (metrics/accuracy {:correct-keys 0 :total-keys 0}))))

(deftest chunk-boundary-timing-test
  (let [passage {:id :test/chunks :title "Chunks" :chunks ["ab" "cd"]}
        session (-> (input/new-session passage :chunks 0)
                    (input/enter-character "a" 100)
                    (input/enter-character "b" 200)
                    (input/enter-character " " 300)
                    (input/enter-character "c" 900))]
    (is (= [600] (get-in session [:boundary-pauses :chunk])))
    (is (= 600 (:chunk-boundary-pause-ms (metrics/summarize session 900))))))

(deftest rhythm-and-hesitation-test
  (let [summary (metrics/summarize {:status :complete
                                    :cursor 4
                                    :started-at 0
                                    :completed-at 1000
                                    :last-input-at 1000
                                    :correct-keys 4
                                    :total-keys 5
                                    :errors 1
                                    :corrections 1
                                    :latencies [100 200 300 400]
                                    :boundary-pauses {:word [200] :chunk [400] :within [100 300]}
                                    :events []})]
    (is (= 400 (:longest-hesitation-ms summary)))
    (is (= 0.8 (:accuracy summary)))
    (is (< 0 (:rhythm-consistency summary) 1))))
