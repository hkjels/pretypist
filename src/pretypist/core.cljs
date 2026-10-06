(ns pretypist.core
  (:require [pretypist.app :as app]
            [pretypist.nexus :as app-nexus]
            [replicant.dom :as replicant]))

(defonce system (app/create-system))
(defonce started? (atom false))

(defn dispatch! [dispatch-data actions]
  (app-nexus/dispatch! system dispatch-data actions))

(defn- refresh-mutable-services! []
  ;; Hot reload keeps the system map, so explicitly replace services whose
  ;; closures contain reloaded implementation code.
  (when-let [controller @(:scene system)]
    ((:dispose! controller))
    (reset! (:scene system) nil))
  (doseq [[_ timer] @(:timers system)]
    (js/clearTimeout timer)
    (js/clearInterval timer))
  (reset! (:timers system) {})
  (when-let [service @(:pwa system)]
    ((:dispose! service))
    (reset! (:pwa system) nil))
  (when-let [cleanup @(:system-theme-cleanup system)]
    (cleanup)
    (reset! (:system-theme-cleanup system) nil))
  (when-let [cleanup @(:landing-scroll-cleanup system)]
    (cleanup)
    (reset! (:landing-scroll-cleanup system) nil)))

(defn init! []
  (when-let [root (js/document.getElementById "app")]
    (reset! (:root system) root)
    (replicant/set-dispatch! dispatch!)
    (if (compare-and-set! started? false true)
      (do
        (app-nexus/render! system)
        (dispatch! {} [[:boot/start]]))
      (do
        (refresh-mutable-services!)
        (app-nexus/render! system)
        (let [canvas (js/document.querySelector ".keyboard-canvas")
              landing-node (js/document.querySelector ".landing-surface")]
          (dispatch! {} (cond-> [[:clock/start]
                                 [:appearance/watch-system]]
                          canvas (conj [:scene/mount canvas])
                          landing-node (conj [:landing/mount landing-node]))))))))

(defn ^:export init [] (init!))

;; cljs-reload re-evaluates namespace top levels, so initialization is guarded
;; while rendering still refreshes after every successful reload.
(init!)
