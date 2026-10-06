(ns pretypist.ui.landing
  (:require [pretypist.landing :as landing]
            [pretypist.ui.brand :as brand]
            [pretypist.ui.icons :as icons]
            [pretypist.ui.settings :as settings]))

(def chapter-copy
  {:read-ahead {:number "01"
                :eyebrow :landing/read-eyebrow
                :title :landing/read-title
                :copy :landing/read-copy}
   :movement {:number "02"
              :eyebrow :landing/movement-eyebrow
              :title :landing/movement-title
              :copy :landing/movement-copy}
   :rhythm {:number "03"
            :eyebrow :landing/rhythm-eyebrow
            :title :landing/rhythm-title
            :copy :landing/rhythm-copy}
   :layout {:number "04"
            :eyebrow :landing/layout-eyebrow
            :title :landing/layout-title
            :copy :landing/layout-copy}
   :begin {:number "05"
           :eyebrow :landing/begin-eyebrow
           :title :landing/begin-title
           :copy :landing/begin-copy}})

(defn- language-select [state]
  [:select.landing-language
   {:aria-label [:t :settings/language]
    :value (name (get-in state [:settings :language]))
    :on {:change [[:settings/change :language [:event/keyword-value]]]}}
   [:option {:value "en"} [:t :language/en]]
   [:option {:value "nb"} [:t :language/nb]]])

(defn- header [state]
  [:header.landing-header
   [:span.wordmark.landing-wordmark (brand/mark) [:span [:t :app/wordmark]]]
   [:div.landing-header-actions
    (language-select state)
    (if (= :trainer (get-in state [:landing :return-to]))
      [:button.landing-skip
       {:type "button" :on {:click [[:landing/continue]]}}
       [:t :landing/back-to-training]]
      [:a.landing-skip {:href "#landing-begin"} [:t :landing/skip]])]])

(defn- chapter-navigation [state]
  (let [active (:chapter (:landing state))]
    [:nav.landing-chapter-nav {:aria-label [:t :landing/chapter-navigation]}
     (for [chapter landing/chapters]
       [:a {:replicant/key (name chapter)
            :href (str "#landing-" (name chapter))
            :class [(when (= active chapter) "is-active")]
            :aria-current (when (= active chapter) "step")
            :aria-label [:t :landing/chapter-label
                         {:number (inc (landing/chapter-index chapter))}]}
        [:span]])]))

(defn- phrase-demo []
  [:p.landing-phrase-demo {:aria-hidden "true"}
   [:span.is-read [:t :landing/read-demo-complete]] " "
   [:span.is-current [:t :landing/read-demo-current]] " "
   [:span [:t :landing/read-demo-upcoming]]])

(defn- gesture-demo []
  [:div.landing-gesture-demo {:aria-hidden "true"}
   [:span "OS SYM"]
   (icons/icon :arrow-right)
   [:span "?"]
   (icons/icon :arrow-right)
   [:span [:t :landing/gesture-prose]]])

(defn- rhythm-demo []
  [:div.landing-rhythm-demo {:aria-hidden "true"}
   [:span "BASE"]
   [:i]
   [:span "NUM"]
   [:i]
   [:span "SYM"]
   [:i]
   [:span "BASE"]])

(defn- layout-demo [state]
  (let [resolved (get-in state [:theme :resolved])]
    [:div.landing-layout-demo {:aria-hidden "true"}
     [:span "layout.edn"]
     [:span "model.glb"]
     [:span [:t :landing/layout-key-count]]
     [:div.landing-theme-strip
      (for [layer [:base :num :sym :nav :mouse]
            :let [color (get-in resolved [:layer-colors layer])]]
        [:i {:replicant/key (name layer)
             :style {:background color}}])]]))

(defn- begin-actions [state]
  (let [return-to (get-in state [:landing :return-to])
        layout-name (get-in state [:layout :active :package/name] "Modified Engram")
        ready? (and (= :ready (get-in state [:app :status]))
                    (= :ready (get-in state [:layout :status])))
        imported? (not= "modified-engram" (get-in state [:layout :active :package/id]))]
    [:div.landing-begin-actions
     [:button.primary-button.landing-primary
      {:type "button"
       :disabled (not ready?)
       :on {:click [[:landing/continue]]}}
      [:span [:t (cond
                   (= return-to :trainer) :landing/continue-training
                   imported? :landing/continue-with-layout
                   :else :landing/start-modified-engram)
              {:name layout-name}]]
      (icons/icon :arrow-right)]
     [:span.landing-or [:t :landing/or]]
     [:input.sr-only
      {:id "layout-package-input"
       :type "file"
       :accept ".pretypist,application/vnd.pretypist+zip,application/zip"
       :aria-label [:t :layout/input-label]
       :on {:change [[:layout/file-selected [:event/file] [:event/node]]]}}]
     [:button.secondary-button
      {:type "button" :on {:click [[:layout/import-requested]]}}
      (icons/icon :upload)
      [:span [:t :landing/import-layout]]]
     (when (= :importing (get-in state [:layout :status]))
       [:p.setting-note {:role "status"} [:t :layout/importing]])
     (settings/layout-import-preview (get-in state [:ui :layout-import-preview])
                                     (get-in state [:layout :status]))
     (when (seq (get-in state [:ui :validation-errors]))
       [:div.validation-errors {:role "alert"}
        (icons/icon :warning)
        [:div
         [:strong [:t :layout/invalid]]
         [:ul
          (for [[index error] (map-indexed vector (get-in state [:ui :validation-errors]))]
            [:li {:replicant/key index}
             [:code (if (seq (:path error)) (pr-str (:path error)) "package")]
             [:span (:message error)]])]]])]))

(defn- chapter-view [state chapter]
  (let [{:keys [number eyebrow title copy]} (get chapter-copy chapter)
        first? (= chapter :read-ahead)]
    [:section.landing-chapter
     {:id (str "landing-" (name chapter))
      :data-landing-chapter (name chapter)
      :aria-labelledby (str "landing-title-" (name chapter))}
     [:div.landing-copy
      [:span.landing-number number]
      [:span.eyebrow [:t eyebrow]]
      [(if first? :h1 :h2)
       {:id (str "landing-title-" (name chapter))}
       [:t title]]
      [:p [:t copy]]
      (case chapter
        :read-ahead (phrase-demo)
        :movement (gesture-demo)
        :rhythm (rhythm-demo)
        :layout (layout-demo state)
        :begin (begin-actions state)
        nil)]]))

(defn view [state]
  [:div.landing-surface
   {:class [(str "is-chapter-" (name (get-in state [:landing :chapter] :read-ahead)))]
    :replicant/on-mount [[:landing/mount [:event/node]]]
    :replicant/on-unmount [[:landing/unmount]]}
   (header state)
   (chapter-navigation state)
   [:main.landing-story
    (for [chapter landing/chapters]
      (chapter-view state chapter))]
   [:div.landing-scroll-cue {:aria-hidden "true"}
    [:span [:t :landing/scroll]]
    [:i]]])
