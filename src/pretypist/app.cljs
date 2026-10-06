(ns pretypist.app
  (:require [pretypist.state :as state]))

(defn create-system []
  {:state (atom (state/initial-state))
   :scene (atom nil)
   :database (atom nil)
   :active-package (atom nil)
   :pending-package (atom nil)
   :system-theme-cleanup (atom nil)
   :pwa (atom nil)
   :landing-scroll-cleanup (atom nil)
   :landing-progress (atom 0)
   :timers (atom {})
   :render-scheduled? (atom false)
   :root (atom nil)})

(defn snapshot [system]
  @(:state system))
