(ns pretypist.nexus-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as str]
            [pretypist.nexus :as nexus]
            [pretypist.onboarding :as onboarding]
            [pretypist.pwa :as pwa]
            [pretypist.state :as state]
            [pretypist.typing.chunks :as chunks]
            [pretypist.typing.input :as input]))

(deftest app-updates-preserve-active-and-paused-practice-test
  (let [base (-> (state/initial-state)
                 (assoc :landing {:status :complete})
                 (assoc :onboarding {:status :complete})
                 (assoc :session {:status :idle}))
        update-action (get nexus/expansions :pwa/update)]
    (is (pwa/update-safe? base))
    (is (= [[:pwa/apply-update]] (update-action base)))
    (doseq [status [:running :paused]]
      (is (not (pwa/update-safe? (assoc-in base [:session :status] status))))
      (is (nil? (update-action (assoc-in base [:session :status] status)))))
    (is (pwa/update-safe? (assoc-in base [:session :status] :complete)))
    (is (not (pwa/update-safe? (assoc base :onboarding (onboarding/initial-state)))))))

(deftest scene-presentation-follows-training-state-test
  (let [base {:settings {:mode :flow}
              :session {:status :idle}
              :layer-trainer {:transfer? false}
              :ui {:settings-open? false}}]
    (is (= :menu (state/scene-presentation base)))
    (is (= :practice (state/scene-presentation (assoc-in base [:session :status] :running))))
    (is (= :menu (state/scene-presentation (assoc-in base [:session :status] :paused))))
    (is (= :menu (state/scene-presentation
                  (-> base
                      (assoc-in [:session :status] :running)
                      (assoc-in [:ui :settings-open?] true)))))
    (is (= :layers (state/scene-presentation
                    (assoc-in base [:settings :mode] :layers))))
    (is (= :practice (state/scene-presentation
                      (-> base
                          (assoc-in [:settings :mode] :layers)
                          (assoc-in [:layer-trainer :transfer?] true)
                          (assoc-in [:session :status] :running)))))))

(deftest layer-orbit-is-only-interactive-in-explore-test
  (let [base {:settings {:mode :layers}
              :layer-trainer {:stage :explore :transfer? false}
              :ui {:settings-open? false}}]
    (is (true? (state/layer-orbit-enabled? base)))
    (is (false? (state/layer-orbit-enabled?
                 (assoc-in base [:layer-trainer :stage] :guided))))
    (is (false? (state/layer-orbit-enabled?
                 (assoc-in base [:ui :settings-open?] true))))
    (is (false? (state/layer-orbit-enabled?
                 (assoc-in base [:settings :mode] :flow))))))

(deftest layer-orbit-keyboard-controls-dispatch-coarse-scene-commands-test
  (let [state {:settings {:mode :layers}
               :layer-trainer {:stage :explore :transfer? false}
               :ui {:settings-open? false}}]
    (is (= [[:dom/prevent-default ::event]
            [:scene/command :orbit-by! 0 -0.09]]
           (nexus/scene-orbit-key state "ArrowUp" ::event)))
    (is (= [[:dom/prevent-default ::event]
            [:scene/reset-camera]]
           (nexus/scene-orbit-key state "Home" ::event)))
    (is (= [] (nexus/scene-orbit-key state "Enter" ::event)))
    (is (nil? (nexus/scene-orbit-key
               (assoc-in state [:layer-trainer :stage] :guided)
               "ArrowUp" ::event)))))

(deftest language-change-updates-document-language-test
  (let [effects (nexus/settings-change
                 {:app {:now 0}
                  :settings {:mode :layers :language :en}
                  :layer-trainer {:transfer? false}}
                 :language :nb)]
    (is (= :nb (get-in (second (first effects)) [:settings :language])))
    (is (some #{[:dom/set-language :nb]} effects))
    (is (some #{[:persistence/save-settings]} effects))
    (is (some #{[:scene/sync]} effects))))

(deftest appearance-change-resolves-one-trusted-theme-snapshot-test
  (let [base (-> (state/initial-state)
                 (assoc-in [:layout :active]
                           {:theme {:version 1
                                    :schemes {:light {:accent "#112233"}}}}))
        effects (nexus/settings-change base :appearance :light)
        next-state (second (first effects))]
    (is (= :light (get-in next-state [:theme :resolved :scheme])))
    (is (= "#112233" (get-in next-state [:theme :resolved :tokens :accent])))
    (is (some #{[:scene/sync]} effects))))

(deftest system-theme-events-respect-fixed-preference-test
  (let [system-state (assoc-in (state/initial-state) [:settings :appearance] :system)
        system-next (second (first (nexus/system-scheme-changed system-state false)))
        fixed-state (assoc-in system-state [:settings :appearance] :dark)
        fixed-next (second (first (nexus/system-scheme-changed fixed-state false)))]
    (is (= :light (get-in system-next [:theme :resolved :scheme])))
    (is (= :dark (get-in fixed-next [:theme :resolved :scheme])))
    (is (false? (get-in fixed-next [:theme :system-dark?])))))

(deftest import-preview-keeps-binary-out-of-application-state-test
  (let [layout {:package/id "quiet-layout"
                :package/name "Quiet Layout"
                :package/revision 2
                :theme {:version 1
                        :schemes {:dark {:accent "#778899"}}}}
        preview (nexus/layout-preview layout)
        state-effects (nexus/layout-import-preview-ready (state/initial-state) preview)
        next-state (second (first state-effects))]
    (is (= :preview (get-in next-state [:layout :status])))
    (is (= "Quiet Layout" (get-in next-state [:ui :layout-import-preview :name])))
    (is (= "#778899"
           (get-in next-state [:ui :layout-import-preview :schemes :dark :tokens :accent])))
    (is (nil? (get-in next-state [:ui :layout-import-preview :model-buffer])))
    (is (= [:layout/install-pending]
           (second (nexus/layout-import-confirmed next-state))))))

(deftest selecting-an-import-clears-the-file-input-before-reading-test
  (let [effects ((get nexus/expansions :layout/file-selected)
                 (state/initial-state) ::file ::input)]
    (is (some #{[:input/clear ::input]} effects))
    (is (some #{[:layout/read-file ::file]} effects))))

(deftest opening-settings-syncs-brighter-scene-test
  (let [effects (nexus/settings-open {:app {:now 200}
                                      :session {:status :running}
                                      :ui {:settings-open? false}})]
    (is (= :settings (get-in (second (first effects)) [:session :pause-reason])))
    (is (some #{[:scene/sync]} effects))))

(deftest landing-route-is-semantic-and-preserves-the-return-path-test
  (let [fresh (state/initial-state)
        moved-effects (nexus/landing-chapter-entered fresh :rhythm)
        moved-state (second (first moved-effects))
        finish-effects (nexus/landing-continue moved-state)
        finished-state (second (first finish-effects))]
    (is (= :landing (state/scene-presentation fresh)))
    (is (= :rhythm (get-in moved-state [:landing :chapter])))
    (is (= :complete (get-in finished-state [:landing :status])))
    (is (= :setup (get-in finished-state [:onboarding :step])))
    (is (some #{[:persistence/save-landing]} finish-effects))
    (is (some #{[:scene/sync]} finish-effects))))

(deftest revisiting-the-story-pauses-active-prose-without-losing-it-test
  (let [session (input/new-session {:id :landing :title "Landing" :chunks ["calm prose"]}
                                   :flow 0)
        running (input/enter-text session "c" 100)
        application (-> (state/initial-state)
                        (assoc :landing {:version 1 :status :complete :seen? true
                                         :chapter :begin :return-to :trainer})
                        (assoc :session running)
                        (assoc-in [:app :now] 120))
        effects (nexus/landing-open application)
        next-state (second (first effects))]
    (is (= :active (get-in next-state [:landing :status])))
    (is (= :trainer (get-in next-state [:landing :return-to])))
    (is (= :paused (get-in next-state [:session :status])))
    (is (= :landing (get-in next-state [:session :pause-reason])))
    (is (= 1 (get-in next-state [:session :cursor])))))

(deftest typing-expansion-does-not-discard-space-test
  (let [session (input/enter-text
                 (input/new-session {:id :test/space :title "Space" :chunks ["ab" "cd"]}
                                    :flow
                                    0)
                 "ab"
                 100)
        state {:settings {:mode :flow :adaptive? false}
               :session session}
        effects (nexus/typing-input state " " 150 ::input-node)
        next-state (second (first effects))]
    (is (= :state/set (ffirst effects)))
    (is (= 3 (get-in next-state [:session :cursor])))
    (is (= "c" (input/expected-character (:session next-state))))
    (is (some #{[:input/clear ::input-node]} effects))))

(deftest empty-input-only-clears-hidden-control-test
  (is (= [[:input/clear ::input-node]]
         (nexus/typing-input {:settings {:mode :flow}} "" 150 ::input-node))))

(deftest physical-space-keydown-advances-at-word-boundary-test
  (let [session (input/enter-text
                 (input/new-session {:id :test/space-key :title "Space key" :chunks ["ab" "cd"]}
                                    :flow
                                    0)
                 "ab"
                 100)
        state {:settings {:mode :flow :adaptive? false}
               :session session}
        effects (nexus/typing-key-down state " " "Space" {} 150 ::event ::input-node)
        next-state (second (some #(when (= :state/set (first %)) %) effects))]
    (is (some #{[:dom/prevent-default ::event]} effects))
    (is (= 3 (get-in next-state [:session :cursor])))
    (is (false? (get-in next-state [:session :pending-error?])))
    (is (some #{[:input/clear ::input-node]} effects))))

(deftest modified-space-shortcut-is-left-to-the-browser-test
  (is (= []
         (nexus/typing-key-down {:settings {:mode :flow}
                                 :session {:status :running}}
                                " " "Space" {:control? true} 150 ::event ::input-node))))

(deftest input-placeholder-prefers-the-inserted-delta-test
  (let [resolve-input (:event/input-text nexus/placeholders)]
    (is (= " "
           (resolve-input {:replicant/dom-event
                           #js {:data " " :target #js {:value "stale prefix "}}})))
    (is (= "pasted text"
           (resolve-input {:replicant/dom-event
                           #js {:data nil :target #js {:value "pasted text"}}})))))

(deftest clock-samples-display-wpm-and-syncs-scene-test
  (let [session (input/enter-text
                 (input/new-session {:id :display-clock :title "Clock" :chunks ["abcdefghij"]}
                                    :flow 0)
                 "abcde"
                 6000)
        effects (nexus/clock-tick {:app {:now 0}
                                   :display {:wpm-history (vec (repeat 10 0))}
                                   :session session}
                                  6000)
        next-state (second (first effects))]
    (is (= 6000 (get-in next-state [:app :now])))
    (is (= 10 (last (get-in next-state [:display :wpm-history]))))
    (is (= [:scene/sync] (second effects)))
    (is (= [] (nexus/clock-tick {:session {:status :paused}} 7000)))))

(deftest completed-session-produces-and-persists-a-prescription-test
  (let [session (-> (input/new-session {:id :focused :title "Focused" :chunks ["ab"]}
                                       :flow 0)
                    (assoc :run-kind :practice)
                    (input/enter-character "x" 100)
                    (input/backspace 120)
                    (input/enter-character "a" 220))
        state (-> (state/initial-state)
                  (assoc :session session)
                  (assoc-in [:app :status] :ready)
                  (assoc-in [:settings :adaptive?] true)
                  (assoc-in [:layout :active :strokes]
                            {"a" [[:tap :ka]] "b" [[:tap :kb]]}))
        effects (nexus/typing-input state "b" 320 2000000000000 ::input-node)
        next-state (second (first effects))]
    (is (= :complete (get-in next-state [:session :status])))
    (is (= "a" (get-in next-state [:session :analysis :weakness :target])))
    (is (= :character (get-in next-state [:progress :next-prescription :kind])))
    (is (= 1 (get-in next-state [:progress :profile :sessions])))
    (is (= 2000000000000
           (get-in next-state [:recent-summaries 0 :completed-on])))
    (is (some #{[:persistence/save-progress]} effects))
    (is (some #{[:persistence/save-summaries]} effects))))

(deftest composition-is-accepted-once-after-ime-commit-test
  (let [session (input/new-session {:id :ime :title "IME" :chunks ["åb"]} :flow 0)
        state {:settings {:mode :flow :adaptive? false}
               :session session
               :ui {:notice nil}}
        composing (nexus/typing-dom-input state "a\u030a" "insertCompositionText"
                                          true 100 2000000000000 ::node)
        effects (nexus/typing-dom-input state "a\u030a" "insertCompositionText"
                                        false 120 2000000000100 ::node)
        next-state (second (first effects))]
    (is (= [] composing) "Intermediate composition updates do not mutate progress")
    (is (= :running (get-in next-state [:session :status])))
    (is (= 1 (get-in next-state [:session :cursor])))
    (is (= 1 (get-in next-state [:session :correct-keys])))
    (is (= [] (nexus/typing-key-down state "Escape" "Escape" {}
                                      true 130 2000000000200 ::event ::node)))))

(deftest paste-drop-and-replacement-input-are-explicitly-blocked-test
  (let [state {:ui {:notice nil}}
        before (nexus/typing-before-input state "insertFromPaste" false ::event)
        slipped (nexus/typing-dom-input state "pasted" "insertReplacementText"
                                        false 100 2000000000000 ::node)
        dropped (nexus/typing-blocked-edit state :drop ::event ::node)]
    (is (= [[:dom/prevent-default ::event]
            [:state/set (assoc-in state [:ui :notice] :notice/blocked-edit)]]
           before))
    (is (= :notice/blocked-edit
           (get-in (second (some #(when (= :state/set (first %)) %) slipped))
                   [:ui :notice])))
    (is (some #{[:input/clear ::node]} slipped))
    (is (= [:dom/prevent-default ::event] (first dropped)))
    (is (some #{[:dom/focus-input]} dropped))))

(deftest virtual-keyboard-backspace-uses-the-same-deliberate-correction-path-test
  (let [session (input/enter-text
                 (input/new-session {:id :mobile-delete :title "Delete" :chunks ["ab"]}
                                    :flow 0)
                 "a" 100)
        state {:settings {:mode :flow}
               :session session
               :ui {:notice :old}}
        effects (nexus/typing-dom-input state nil "deleteContentBackward"
                                        false 200 2000000000000 ::node)
        next-state (second (first effects))]
    (is (zero? (get-in next-state [:session :cursor])))
    (is (= 1 (get-in next-state [:session :corrections])))
    (is (nil? (get-in next-state [:ui :notice])))
    (is (some #{[:input/clear ::node]} effects))))

(deftest consolidation-action-starts-fresh-relevant-prose-at-captured-difficulty-test
  (let [prescription {:id "focus-a" :kind :character :target "a" :reason :errors
                      :mode :flow :language :en :source-passage-id :old
                      :difficulty {:punctuation-density 0.2}
                      :copy {:key :analysis/prescription-character
                             :params {:character "a"}}}
        state (-> (state/initial-state)
                  (assoc :corpora {:en [{:id :old :title "Old" :chunks ["An old line"]}
                                        {:id :fresh :title "Fresh" :chunks ["A calm lake"]}]
                                     :nb []})
                  (assoc-in [:progress :next-prescription] prescription))
        effects (nexus/session-consolidate state 900)
        next-state (second (first effects))]
    (is (= :consolidation (get-in next-state [:session :run-kind])))
    (is (= :fresh (get-in next-state [:session :passage-id])))
    (is (= {:punctuation-density 0.2}
           (get-in next-state [:session :focus :difficulty])))
    (is (= :running (get-in next-state [:session :status])))
    (is (some #{[:dom/focus-input]} effects))))

(deftest printable-keydown-records-observed-key-without-claiming-composed-input-test
  (let [session (input/new-session {:id :observed :title "Observed" :chunks ["a"]} :flow 0)
        state {:settings {:mode :flow}
               :session session
               :layout {:active {:keys [{:id :ka :observed-codes ["KeyA"]}]}}}
        effects (nexus/typing-key-down state "a" "KeyA" {} 10 ::event ::node)
        next-state (second (some #(when (= :state/set (first %)) %) effects))]
    (is (= :ka (get-in next-state [:session :pending-observed-key :physical-key-id])))
    (is (= "KeyA" (get-in next-state [:session :pending-observed-key :physical-code])))
    (is (= [[:scene/press :ka]] (take 1 effects)))))

(def gesture-layout
  {:keys [{:id :shift-key} {:id :letter-key} {:id :num-key}
          {:id :ae-key} {:id :oe-key} {:id :aa-key}]
   :layers {:base {:bindings
                   {:shift-key {:actions {:tap [:one-shot :modifier :shift]}}
                    :num-key {:actions {:hold [:layer :momentary :num]}}}}
            :num {:bindings {}}}
   :strokes {"A" [[:one-shot :shift-key] [:tap :letter-key]]
             "a" [[:tap :letter-key]]
             "æ" [[:hold :num-key] [:tap :ae-key] [:release :num-key]]
             "ø" [[:hold :num-key] [:tap :oe-key] [:release :num-key]]
             "å" [[:hold :num-key] [:tap :aa-key] [:release :num-key]]}})

(deftest gesture-replay-expands-to-one-scene-command-and-expiry-test
  (let [state (-> (state/initial-state)
                  (assoc :session {:id "session-7"})
                  (assoc-in [:layout :active] gesture-layout))
        effects (nexus/gesture-replay state "æ" 240)
        next-state (second (first effects))
        timeline (second (second effects))]
    (is (= "æ" (get-in next-state [:ui :gesture-playback :character])))
    (is (= :inferred (get-in next-state [:ui :gesture-playback :source])))
    (is (= :illustrative (get-in next-state [:ui :gesture-playback :timing])))
    (is (= [:scene/play-gesture timeline] (second effects)))
    (is (= [:hold :tap :release] (mapv :kind (:steps timeline))))
    (is (= [:timer/gesture-finished "gesture-session-7-240" (:duration-ms timeline)]
           (last effects)))))

(deftest stale-gesture-expiry-cannot-clear-newer-playback-test
  (let [state {:ui {:gesture-playback {:id "newer"}}}]
    (is (= [] (nexus/gesture-finished state "older")))
    (is (nil? (get-in (second (first (nexus/gesture-finished state "newer")))
                      [:ui :gesture-playback])))))

(deftest prose-errors-only-replay-inferred-complex-gestures-test
  (let [base-state {:settings {:mode :flow :adaptive? false}
                    :layout {:active gesture-layout}}
        complex-effects (nexus/typing-input
                         (assoc base-state :session
                                (input/new-session {:id :complex :title "Complex" :chunks ["A"]}
                                                   :flow 0))
                         "x" 120 ::node)
        direct-effects (nexus/typing-input
                        (assoc base-state :session
                               (input/new-session {:id :direct :title "Direct" :chunks ["a"]}
                                                  :flow 0))
                        "x" 120 ::node)]
    (is (some #{[:gesture/replay "A" 120]} complex-effects))
    (is (not (some #{[:gesture/replay "a" 120]} direct-effects)))))

(deftest layer-practice-remembers-trained-target-and-recall-stays-blank-test
  (let [base-state (-> (state/initial-state)
                       (assoc-in [:settings :mode] :layers)
                       (assoc-in [:layout :active] gesture-layout)
                       (assoc-in [:layer-trainer :stage] :guided))
        correct-effects (nexus/typing-input base-state "æ" 100 ::node)
        correct-state (second (first correct-effects))
        guided-error (nexus/typing-input base-state "x" 120 ::node)
        recall-error (nexus/typing-input
                      (assoc-in base-state [:layer-trainer :stage] :recall)
                      "x" 120 ::node)]
    (is (= "æ" (get-in correct-state [:layer-trainer :last-trained-target :character])))
    (is (true? (get-in correct-state [:layer-trainer :transfer-offered?])))
    (is (= "ø" (:character (state/current-target correct-state))))
    (is (some #{[:gesture/replay "æ" 120]} guided-error))
    (is (not (some #{[:gesture/replay "æ" 120]} recall-error)))))

(deftest layer-transfer-uses-last-trained-target-in-fresh-real-prose-test
  (let [trained-target (first state/layer-targets)
        initial (-> (state/initial-state)
                    (assoc :corpora {:en [{:id :plain :title "Plain" :chunks ["A quiet road"]}
                                          {:id :ae :title "Norwegian name"
                                           :chunks ["We copied the word blåbær carefully"]}]
                                       :nb []})
                    (assoc-in [:settings :mode] :layers)
                    (assoc-in [:layout :active] gesture-layout)
                    (assoc-in [:layer-trainer :target-index] 1)
                    (assoc-in [:layer-trainer :last-trained-target] trained-target)
                    (assoc-in [:layer-trainer :transfer-offered?] true))
        effects (nexus/layer-transfer-start initial 900)
        next-state (second (first effects))]
    (is (= :transfer (get-in next-state [:session :run-kind])))
    (is (= :layers (get-in next-state [:session :mode])))
    (is (= "æ" (get-in next-state [:session :focus :target])))
    (is (= :ae (get-in next-state [:session :passage-id])))
    (is (re-find #"æ" (get-in next-state [:session :text])))
    (is (true? (get-in next-state [:layer-trainer :transfer?])))
    (is (false? (get-in next-state [:layer-trainer :transfer-offered?])))
    (is (some #{[:scene/cancel-gesture]} effects))
    (is (some #{[:dom/focus-input]} effects))))

(deftest onboarding-start-is-a-persisted-semantic-transition-test
  (let [effects (nexus/onboarding-start (state/initial-state) 120)
        next-state (second (first effects))]
    (is (= [:baseline :intro]
           ((juxt :step :phase) (:onboarding next-state))))
    (is (= :flow (get-in next-state [:settings :mode])))
    (is (some #{[:persistence/save-onboarding]} effects))
    (is (some #{[:scene/sync]} effects))))

(deftest onboarding-session-two-starts-visible-authored-chunks-test
  (let [initial (-> (state/initial-state)
                    (assoc :corpora {:en [{:id :phrases :title "Phrases"
                                           :chunks ["Read this whole phrase" "then move together now"]}]
                                     :nb []})
                    (assoc :onboarding {:version onboarding/version :status :active
                                        :step :rhythm :phase :intro :records []}))
        effects (nexus/onboarding-session-start initial 200)
        next-state (second (first effects))]
    (is (= :calibration (get-in next-state [:session :run-kind])))
    (is (= :chunks (get-in next-state [:session :mode])))
    (is (= 1 (get-in next-state [:session :calibration-difficulty :boundary-opacity])))
    (is (every? #(<= (count (str/split % #"\s+")) 4)
                (get-in next-state [:session :chunks])))
    (is (= :running (get-in next-state [:onboarding :phase])))
    (is (some #{[:persistence/save-onboarding]} effects))))

(deftest onboarding-layer-answers-advance-the-onboarding-checkpoint-test
  (let [initial (-> (state/initial-state)
                    (assoc :onboarding {:version onboarding/version :status :active
                                        :step :layers :phase :gestures
                                        :gesture-index 0 :records []})
                    (assoc-in [:settings :mode] :layers)
                    (assoc-in [:layer-trainer :stage] :guided)
                    (assoc-in [:layout :active] gesture-layout))
        effects (nexus/typing-input initial "æ" 100 ::node)
        next-state (second (first effects))]
    (is (= 1 (get-in next-state [:onboarding :gesture-index])))
    (is (some #{[:persistence/save-onboarding]} effects))))

(deftest onboarding-transfer-selects-prose-containing-all-three-layout-targets-test
  (let [initial (-> (state/initial-state)
                    (assoc :corpora {:en [{:id :distractor :title "Distractor"
                                           :chunks ["A plain sentence ends here."]}
                                          {:id :transfer :title "Transfer"
                                           :chunks ["Write æ, ø and å." ]}]
                                     :nb []})
                    (assoc :onboarding {:version onboarding/version :status :active
                                        :step :layers :phase :transfer-ready
                                        :gesture-index 3 :records []})
                    (assoc-in [:settings :mode] :layers)
                    (assoc-in [:layout :active] gesture-layout))
        effects (nexus/onboarding-session-start initial 500)
        next-state (second (first effects))]
    (is (= ["æ" "ø" "å"]
           (mapv :character (state/onboarding-targets initial))))
    (is (= "Write æ, ø and å."
           (get-in initial [:corpora :en 1 :chunks 0])))
    (is (str/includes? "Write æ, ø and å." "æ"))
    (is (= "Write æ, ø and å."
           (chunks/passage-text (second (get-in initial [:corpora :en])))))
    (is (= 3 (nexus/onboarding-target-score
              (second (get-in initial [:corpora :en]))
              ["æ" "ø" "å"])))
    (is (= :transfer (get-in next-state [:session :passage-id])))
    (is (= "Write æ, ø and å." (get-in next-state [:session :text])))))
