(ns pretypist.pwa
  (:require [pretypist.landing :as landing]
            [pretypist.onboarding :as onboarding]))

(defn update-safe? [state]
  (and (not (landing/active? (:landing state)))
       (not (onboarding/active? (:onboarding state)))
       (#{:idle :complete} (get-in state [:session :status]))))

(defn start! [changed!]
  (let [worker-api (.-serviceWorker js/navigator)
        display-mode (js/window.matchMedia "(display-mode: standalone)")
        installed? #(or (.-matches display-mode) (true? (.-standalone js/navigator)))
        registration* (atom nil)
        install-prompt* (atom nil)
        reload-requested?* (atom false)
        disposed?* (atom false)
        listeners* (atom [])]
    (letfn [(emit! [values] (when-not @disposed?* (changed! values)))
            (listen! [target event handler]
              (.addEventListener target event handler)
              (swap! listeners* conj [target event handler]))
            (installation-changed! []
              (when (installed?) (reset! install-prompt* nil))
              (emit! {:installed? (boolean (installed?))
                      :installable? (and (not (installed?)) (some? @install-prompt*))}))
            (before-install! [event]
              (.preventDefault event)
              (reset! install-prompt* event)
              (installation-changed!))
            (controller-changed! []
              (emit! {:offline-ready? true :update-ready? false})
              (when @reload-requested?* (js/window.location.reload)))
            (check-waiting! []
              (emit! {:update-ready? (some? (some-> @registration* .-waiting))}))
            (watch-installing! []
              (when-let [worker (some-> @registration* .-installing)]
                (listen! worker "statechange" check-waiting!)))
            (check-update! []
              (when (and (not (.-hidden js/document)) @registration*)
                ;; Update failures simply retain the working offline release.
                (-> (.update @registration*) (.catch (fn [_] nil)))))
            (install! []
              (when-let [event @install-prompt*]
                (reset! install-prompt* nil)
                (emit! {:installable? false})
                (-> (.prompt event)
                    (.then (fn [_] (.-userChoice event)))
                    (.then (fn [choice]
                             (when (= "accepted" (.-outcome choice))
                               (emit! {:installed? true}))))
                    (.catch (fn [_] nil)))))
            (update! []
              (when-let [worker (some-> @registration* .-waiting)]
                (reset! reload-requested?* true)
                (.postMessage worker #js {:type "PRETYPIST_APPLY_UPDATE"})))]
      (installation-changed!)
      (listen! js/window "beforeinstallprompt" before-install!)
      (listen! js/window "appinstalled" (fn [_]
                                         (reset! install-prompt* nil)
                                         (emit! {:installed? true :installable? false})))
      (listen! display-mode "change" installation-changed!)
      (when (and worker-api js/window.isSecureContext)
        (when (.-controller worker-api) (emit! {:offline-ready? true}))
        (listen! worker-api "controllerchange" controller-changed!)
        (listen! js/document "visibilitychange" check-update!)
        (-> (.register worker-api "/sw.js" #js {:scope "/" :updateViaCache "none"})
            (.then (fn [registration]
                     (when-not @disposed?*
                       (reset! registration* registration)
                       (listen! registration "updatefound" watch-installing!)
                       (watch-installing!)
                       (check-waiting!))
                     (.-ready worker-api)))
            (.then (fn [_] (emit! {:offline-ready? true})))
            (.catch (fn [_] nil))))
      {:install! install!
       :update! update!
       :dispose! (fn []
                   (reset! disposed?* true)
                   (doseq [[target event handler] @listeners*]
                     (.removeEventListener target event handler))
                   (reset! listeners* [])
                   (reset! install-prompt* nil))})))
