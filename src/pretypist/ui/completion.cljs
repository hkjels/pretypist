(ns pretypist.ui.completion
  (:require [clojure.string :as str]
            [pretypist.typing.metrics :as metrics]
            [pretypist.ui.icons :as icons]
            [pretypist.ui.pwa :as pwa]))

(def chart-width 100)
(def chart-height 36)
(def chart-padding 2)

(defn format-ms [value]
  (if (>= (or value 0) 1000)
    (str (.toFixed (/ value 1000) 1) " s")
    (str (js/Math.round (or value 0)) " ms")))

(defn- plot-x [value]
  (+ chart-padding (* (- chart-width (* 2 chart-padding)) (or value 0))))

(defn- plot-y [value]
  (+ chart-padding (* (- chart-height (* 2 chart-padding))
                      (- 1 (or value 0.5)))))

(defn chart-points-attribute
  "Converts normalized analysis points into stable SVG geometry."
  [points]
  (->> points
       (map (fn [{:keys [x y]}]
              (str (.toFixed (plot-x x) 2) "," (.toFixed (plot-y y) 2))))
       (str/join " ")))

(defn- copy-node [{:keys [key params]}]
  (when key [:t key (or params {})]))

(defn- marker-copy [marker]
  (case (:kind marker)
    :error [:t :analysis/event-error
            {:character (:character marker)
             :typed (:typed marker)
             :position (inc (:cursor marker))}]
    :stall [:t :analysis/event-stall
            {:character (:character marker)
             :latency (js/Math.round (:latency-ms marker))
             :position (inc (:cursor marker))}]
    :gesture [:t :analysis/event-gesture
              {:character (:character marker)
               :position (inc (:cursor marker))}]
    [:t :analysis/event-chunk {:position (inc (:cursor marker))}]))

(defn marker-symbol
  "An eight-pixel viewport shared by the plot and its legend. Keep this outside
  the stretched line SVG so every symbol retains its screen-space proportions."
  [kind attrs]
  [:svg.chart-symbol
   (merge {:viewBox "0 0 8 8" :width 8 :height 8
           :class [(str "is-" (name kind))]
           :aria-hidden "true" :focusable "false"} attrs)
   (case kind
     :error [:rect {:x 1 :y 1 :width 6 :height 6 :rx 0.8}]
     ;; Side length 8, altitude 4√3; centered in the square viewport.
     :gesture [:path {:d "M 4 0.536 L 8 7.464 L 0 7.464 Z"}]
     [:circle {:cx 4 :cy 4 :r 3}])])

(defn chart-marker [index marker]
  (let [x (plot-x (:x marker))
        kind (:kind marker)
        ;; Separate the error and pause rows so both remain visible when they
        ;; describe the same character, including in the shorter mobile plot.
        y (case kind :error 2 :gesture 32.5 5)]
    (marker-symbol kind
                   {:class ["chart-marker" (str "is-" (name kind))]
                    :replicant/key (str (name kind) "-" (:cursor marker) "-" index)
                    :style {:left (str x "%") :top (str (* 100 (/ y chart-height)) "%")}
                    :tabindex "0" :role "img" :focusable "true"
                    :aria-hidden "false" :aria-label (marker-copy marker)})))

(defn rhythm-chart [{:keys [points baseline-y markers error-count stall-count typical-latency-ms]}]
  (when (seq points)
    [:section.rhythm-section {:aria-labelledby "rhythm-title"}
     [:div.rhythm-heading
      [:h3#rhythm-title [:t :analysis/rhythm-title]]
      [:span [:t :analysis/rhythm-summary
              {:latency typical-latency-ms :errors error-count :stalls stall-count}]]]
     [:div.rhythm-plot
      [:svg.rhythm-chart
       {:viewBox (str "0 0 " chart-width " " chart-height)
        :role "img"
        :aria-labelledby "rhythm-title rhythm-description"
        :preserveAspectRatio "none"}
       [:desc#rhythm-description
        [:t :analysis/rhythm-description
         {:errors error-count :stalls stall-count}]]
       (when baseline-y
         [:line.chart-baseline
          {:x1 chart-padding :x2 (- chart-width chart-padding)
           :y1 (plot-y baseline-y) :y2 (plot-y baseline-y)}])
       [:polyline.chart-line {:points (chart-points-attribute points)}]
       (for [[index marker] (map-indexed vector markers) :when (= :chunk (:kind marker))]
         [:line.chart-chunk
          {:replicant/key index :x1 (plot-x (:x marker)) :x2 (plot-x (:x marker))
           :y1 30.5 :y2 34 :aria-hidden "true"}])]
      (map-indexed chart-marker (remove #(= :chunk (:kind %)) markers))]
     [:div.chart-key {:aria-hidden "true"}
      [:span (marker-symbol :stall {}) [:t :analysis/chart-stall]]
      [:span (marker-symbol :error {}) [:t :analysis/chart-error]]
      [:span (marker-symbol :gesture {}) [:t :analysis/chart-gesture]]]]))

(defn- insight [class-name label-key copy]
  (when copy
    [:div.completion-insight {:class [class-name]}
     [:span.eyebrow [:t label-key]]
     [:p (copy-node copy)]]))

(defn- focus-insight [state weakness]
  (when-let [copy (:copy weakness)]
    (let [character (:target weakness)
          playing? (= character (get-in state [:ui :gesture-playback :character]))]
      (cond-> [:div.completion-insight.is-focus
               [:span.eyebrow [:t :analysis/focus]]
               [:p (copy-node copy)]]
        (:stroke weakness)
        (conj [:button.analysis-replay-button
               {:type "button"
                :disabled playing?
                :aria-label [:t (if playing? :action/replaying-gesture :action/replay-gesture)
                             {:character character}]
                :title [:t :layers/illustrative-timing]
                :on {:click [[:gesture/replay character [:event/time]]]}}
               (icons/icon :play)
               [:span [:t (if playing? :action/replaying :action/replay)]]])))))

(defn completion [state]
  (let [session (:session state)
        summary (or (:summary session) (metrics/summarize session))
        analysis (:analysis session)
        weakness (:weakness analysis)
        prescription (:prescription analysis)
        explanation (get-in state [:progress :next-change])
        consolidation? (= :consolidation (:run-kind session))]
    [:div.completion-overlay
     [:section.completion-panel
      {:role "dialog"
       :aria-modal "true"
       :aria-labelledby "completion-title"
       :tabindex "-1"
       :on {:keydown [[:completion/key-down [:event/key] [:event/dom-event]]]}
       :replicant/on-mount [[:completion/mounted [:event/node]]]}
      [:header.completion-header
       [:div
        [:span.eyebrow [:t (if consolidation?
                             :session/consolidation-complete
                             :session/complete)]]
        [:h2#completion-title (:title session)]
        [:p.completion-copy
         [:t (if consolidation?
               :session/consolidation-copy
               :session/complete-copy)]]]
       [:span.completion-check {:aria-hidden "true"} (icons/icon :check)]]
      [:div.completion-metrics
       [:div [:strong (js/Math.round (:wpm summary))] [:span [:t :metric/wpm-label]]]
       [:div [:strong (str (js/Math.round (* 100 (:accuracy summary))) "%")] [:span [:t :metric/accuracy-label]]]
       [:div [:strong (format-ms (:longest-hesitation-ms summary))] [:span [:t :metric/hesitation]]]
       [:div [:strong (str (js/Math.round (* 100 (:rhythm-consistency summary))) "%")] [:span [:t :metric/rhythm]]]]
      (rhythm-chart (:chart analysis))
      [:div.completion-insights
       (insight "is-improvement" :analysis/improvement (:improvement analysis))
       (focus-insight state weakness)]
      (when (or prescription explanation)
        [:div.next-change
         [:span.eyebrow [:t :analysis/next-run]]
         (when prescription [:p (copy-node (:copy prescription))])
         (when explanation [:small [:t explanation]])])
      [:div.completion-actions
       (when prescription
         [:button.secondary-button
          {:type "button" :on {:click [[:session/new]]}}
          [:span [:t :action/continue]]])
       [:button.primary-button
        {:id "completion-next"
         :type "button"
         :on {:click [(if prescription
                        [:session/consolidate [:event/time]]
                        [:session/new])]}}
        (icons/icon (if prescription :keyboard :play))
        [:span [:t (if prescription :action/consolidate :action/restart)]]]]
      (pwa/actions state)]]))
