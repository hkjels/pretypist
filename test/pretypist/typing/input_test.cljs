(ns pretypist.typing.input-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.typing.input :as input]))

(def passage
  {:id :test/passage :title "Test" :chunks ["ab" "cd"]})

(deftest typing-progression-test
  (let [session (input/new-session passage :flow 1000)
        typed (-> session
                  (input/enter-character "a" 1100)
                  (input/enter-character "b" 1200))]
    (is (= 2 (:cursor typed)))
    (is (= " " (input/expected-character typed)))
    (is (= 2 (:correct-keys typed)))
    (is (= :running (:status typed)))))

(deftest whitespace-is-typing-input-test
  (let [before-space (input/enter-text (input/new-session passage :flow 0) "ab" 100)
        after-space (input/enter-text before-space " " 150)
        completed (input/enter-text after-space "cd" 200)]
    (is (= " " (input/expected-character before-space)))
    (is (= 3 (:cursor after-space)) "A literal space advances the passage like any other character")
    (is (= 3 (:correct-keys after-space)))
    (is (false? (:pending-error? after-space)))
    (is (= :complete (:status completed)))))

(deftest correction-requires-backspace-test
  (let [wrong (input/enter-character (input/new-session passage :flow 0) "x" 100)
        blocked (input/enter-character wrong "a" 200)
        corrected (input/backspace blocked 250)
        accepted (input/enter-character corrected "a" 300)]
    (is (:pending-error? wrong))
    (is (= 0 (:cursor blocked)) "Correct input does not silently bypass an uncorrected error")
    (is (false? (:pending-error? corrected)))
    (is (= 1 (:corrections corrected)))
    (is (= 1 (:cursor accepted)))))

(deftest deliberate-backspace-revises-progress-test
  (let [session (-> (input/new-session passage :flow 0)
                    (input/enter-text "ab" 100)
                    (input/backspace 200))]
    (is (= 1 (:cursor session)))
    (is (= 1 (:corrections session)))
    (is (= "b" (input/expected-character session)))))

(deftest norwegian-unicode-input-test
  (let [norwegian {:id :test/nb :title "Norsk" :chunks ["blå øy, ærlig"]}
        text "blå øy, ærlig"
        session (input/enter-text (input/new-session norwegian :flow 0) text 1000)]
    (is (= (count text) (:cursor session)))
    (is (= :complete (:status session)))
    (is (zero? (:errors session)))))

(deftest decomposed-unicode-is-normalized-before-progression-test
  (let [passage {:id :test/nfd :title "Norsk" :chunks ["å ø æ"]}
        decomposed (str "a\u030a " "o\u0338 " "æ")
        ;; Norwegian ø has no canonical decomposition, so only å should fold.
        after-aa (input/enter-text (input/new-session passage :flow 0) "a\u030a" 100)]
    (is (= "å ø æ" (:text (input/new-session passage :flow 0))))
    (is (= 1 (:cursor after-aa)))
    (is (= " " (input/expected-character after-aa)))
    (is (= "å o̸ æ" (input/normalize-text decomposed)))))

(deftest observable-physical-key-is-attached-to-only-the-next-text-event-test
  (let [session (-> (input/new-session passage :flow 0)
                    (input/observe-key :k13 "KeyA")
                    (input/enter-character "a" 100)
                    (input/enter-character "b" 200))]
    (is (= :k13 (get-in session [:events 0 :physical-key-id])))
    (is (= "KeyA" (get-in session [:events 0 :physical-code])))
    (is (nil? (get-in session [:events 1 :physical-key-id])))))
