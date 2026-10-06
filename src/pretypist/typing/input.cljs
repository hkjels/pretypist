(ns pretypist.typing.input
  (:require [pretypist.typing.chunks :as chunks]))

(defn normalize-text
  "Normalizes browser input to NFC so composed and decomposed Norwegian
  characters advance through the same prose. Invalid deltas become empty."
  [text]
  (if (string? text) (.normalize text "NFC") ""))

(defn new-session [passage mode now]
  (let [text (normalize-text (chunks/passage-text passage))]
    {:id (str "session-" (long now) "-" (name (:id passage)))
     :passage-id (:id passage)
     :title (:title passage)
     :mode mode
     :chunks (:chunks passage)
     :selection (:selection passage)
     :text text
     :cursor 0
     :status :running
     :started-at now
     :last-input-at now
     :completed-at nil
     :correct-keys 0
     :total-keys 0
     :errors 0
     :corrections 0
     :pending-error? false
     :latencies []
     :boundary-pauses {:word [] :chunk [] :within []}
     :events []}))

(defn expected-character [session]
  (when (< (:cursor session) (count (:text session)))
    (subs (:text session) (:cursor session) (inc (:cursor session)))))

(defn observe-key
  "Associates the next emitted text delta with an observable browser key.
  Composed/IME text may intentionally arrive without this information."
  [session key-id code]
  (if (= :running (:status session))
    (assoc session :pending-observed-key {:physical-key-id key-id
                                          :physical-code code})
    session))

(defn- input-context [session now]
  (let [latency (max 0 (- now (:last-input-at session)))
        kind (chunks/boundary-kind (:text session)
                                   (chunks/chunk-boundaries (:chunks session))
                                   (:cursor session))]
    {:latency-ms latency :boundary-kind kind}))

(defn- record-latency [session now {:keys [latency-ms boundary-kind]}]
  (let [latency latency-ms
        kind boundary-kind]
    (-> session
        (update :latencies conj latency)
        (update-in [:boundary-pauses kind] conj latency)
        (assoc :last-input-at now))))

(defn enter-character [session character now]
  (if (not= :running (:status session))
    session
    (let [expected (expected-character session)
          accepted? (and (not (:pending-error? session)) (= character expected))
          context (input-context session now)
          base (-> session
                   (update :total-keys inc)
                   (update :events conj (merge {:at now
                                                :character character
                                                :expected expected
                                                :accepted? accepted?
                                                :cursor (:cursor session)}
                                               context
                                               (:pending-observed-key session)))
                   (dissoc :pending-observed-key))]
      (if accepted?
        (let [advanced (-> base
                           (record-latency now context)
                           (update :correct-keys inc)
                           (update :cursor inc))]
          (if (= (:cursor advanced) (count (:text advanced)))
            (assoc advanced :status :complete :completed-at now)
            advanced))
        (-> base
            (assoc :pending-error? true :last-input-at now)
            (update :errors inc))))))

(defn enter-text [session text now]
  (reduce (fn [s ch] (enter-character s ch now))
          session
          (js/Array.from (normalize-text text))))

(defn backspace [session now]
  (cond
    (not= :running (:status session)) session
    (:pending-error? session)
    (-> session
        (assoc :pending-error? false :last-input-at now)
        (dissoc :pending-observed-key)
        (update :corrections inc)
        (update :events conj {:at now :kind :correction :cursor (:cursor session)}))

    (pos? (:cursor session))
    (-> session
        (update :cursor dec)
        (assoc :last-input-at now)
        (dissoc :pending-observed-key)
        (update :corrections inc)
        (update :events conj {:at now :kind :revision :cursor (:cursor session)}))

    :else session))

(defn pause [session reason now]
  (if (= :running (:status session))
    (assoc session :status :paused :pause-reason reason :paused-at now)
    session))

(defn resume [session now]
  (if (= :paused (:status session))
    (-> session
        (assoc :status :running :last-input-at now)
        (dissoc :pause-reason :paused-at))
    session))
