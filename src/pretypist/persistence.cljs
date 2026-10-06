(ns pretypist.persistence
  (:require [cljs.reader :as reader]
            [pretypist.landing :as landing]
            [pretypist.onboarding :as onboarding]
            [pretypist.state :as state]
            [pretypist.typing.adaptive :as adaptive]))

(def settings-key "pretypist/settings")
(def progress-key "pretypist/progress")
(def summaries-key "pretypist/recent-summaries")
(def layouts-key "pretypist/installed-layouts")
(def onboarding-key "pretypist/onboarding")
(def landing-key "pretypist/landing")

(defn- safe-read [text fallback]
  (try
    (if text (reader/read-string text) fallback)
    (catch :default _ fallback)))

(defn migrate-settings [value]
  (let [migrated (case (:version value)
                   1 (assoc (merge state/default-settings value)
                            :version state/persistence-version)
                   2 (merge state/default-settings value)
                   state/default-settings)]
    (cond-> migrated
      (not (contains? state/appearance-options (:appearance migrated)))
      (assoc :appearance :system))))

(defn migrate-progress [value]
  (adaptive/migrate-progress value))

(defn load-local []
  (let [storage js/localStorage
        settings (safe-read (.getItem storage settings-key) nil)
        progress (safe-read (.getItem storage progress-key) nil)
        summaries (safe-read (.getItem storage summaries-key) [])
        layouts (safe-read (.getItem storage layouts-key) [])
        onboarding-value (safe-read (.getItem storage onboarding-key) nil)
        landing-value (safe-read (.getItem storage landing-key) nil)
        migrated-progress (migrate-progress progress)
        returning-user? (or (seq summaries)
                            (seq (:calibration migrated-progress))
                            (pos? (get-in migrated-progress [:profile :sessions] 0)))
        existing-user? (or returning-user? (some? onboarding-value))]
    {:settings (migrate-settings settings)
     :stored-settings? (some? settings)
     :progress (or migrated-progress nil)
     :summaries (vec (take-last 20 summaries))
     :layouts (vec layouts)
     :landing (landing/migrate landing-value existing-user?)
     :onboarding (onboarding/migrate onboarding-value returning-user?)}))

(defn save-settings! [settings]
  (.setItem js/localStorage settings-key (pr-str settings)))

(defn save-progress! [progress]
  (.setItem js/localStorage progress-key (pr-str progress)))

(defn save-summaries! [summaries]
  (.setItem js/localStorage summaries-key (pr-str (vec (take-last 20 summaries)))))

(defn save-layouts! [layouts]
  (.setItem js/localStorage layouts-key
            (pr-str (vec (remove :builtin? layouts)))))

(defn save-onboarding! [onboarding]
  (.setItem js/localStorage onboarding-key (pr-str onboarding)))

(defn save-landing! [landing]
  (.setItem js/localStorage landing-key (pr-str landing)))

(defn reset-progress! []
  (.removeItem js/localStorage progress-key)
  (.removeItem js/localStorage summaries-key))
