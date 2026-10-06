(ns pretypist.layout.validate-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.layout.validate :as validate]))

(def valid-layout
  {:pretypist/format-version 1
   :package/id "test-layout"
   :model {:file "model.glb"}
   :keys [{:id :k00 :index 0 :node "key_00" :observed-codes ["KeyA"]}
          {:id :k01 :index 1 :node "key_01"}]
   :layers {:base {:bindings {:k00 {:legend "A"} :k01 {:legend "B"}}}}
   :strokes {"a" [[:tap :k00]]}})

(def valid-display-layout
  (assoc valid-layout
         :batteries
         {:left {:kind :li-po :telemetry :simulated}
          :right {:kind :li-po :telemetry :simulated}}
         :displays
         {:left {:kind :nice-view
                 :node "display_screen_left"
                 :role :central
                 :resolution [160 68]
                 :surface-size-mm [11.3 26.5]
                 :orientation :portrait
                 :template :zmk/nice-view-status-v0.3
                 :battery :left
                 :widgets [:battery :endpoint :bluetooth-profiles :wpm :layer]}
          :right {:kind :nice-view
                  :node "display_screen_right"
                  :role :peripheral
                  :resolution [160 68]
                  :surface-size-mm [11.3 26.5]
                  :orientation :portrait
                  :template :zmk/nice-view-peripheral-v0.3
                  :battery :right
                  :widgets [:battery :split-connection :peripheral-art]}}
         :display/default-status
         {:source :simulated
          :batteries {:left {:percent 82 :usb-powered? false}
                      :right {:percent 76 :usb-powered? false}}
          :endpoint {:transport :ble :profile 0}
          :bluetooth-profiles
          [{:index 0 :selected? true :bonded? true :connected? true}
           {:index 1 :selected? false :bonded? false :connected? false}
           {:index 2 :selected? false :bonded? false :connected? false}
           {:index 3 :selected? false :bonded? false :connected? false}
           {:index 4 :selected? false :bonded? false :connected? false}]
          :wpm {:current 42 :history [0 0 12 18 24 31 36 39 42 42]}
          :layer :base
          :split-connection :connected
          :peripheral-art :mountain}))

(defn error-codes [layout]
  (set (map :code (validate/validate-layout layout))))

(defn error-at? [errors path code]
  (some #(and (= path (:path %)) (= code (:code %))) errors))

(deftest valid-layout-test
  (testing "legacy version 1 layouts do not need displays"
    (is (empty? (validate/validate-layout valid-layout))))
  (testing "a complete nice!view declaration is valid"
    (is (empty? (validate/validate-layout valid-display-layout)))))

(deftest unsupported-format-test
  (is (contains? (error-codes (assoc valid-layout :pretypist/format-version 9))
                 :unsupported-format)))

(deftest material-policy-validation-test
  (is (empty? (validate/validate-layout (assoc-in valid-layout [:model :material-policy] :preserve))))
  (is (contains? (error-codes (assoc-in valid-layout [:model :material-policy] :arbitrary))
                 :invalid-material-policy)))

(deftest duplicate-key-id-and-index-test
  (let [duplicate (assoc valid-layout :keys
                         [{:id :same :index 4 :node "key_00"}
                          {:id :same :index 4 :node "key_01"}])
        codes (error-codes duplicate)]
    (is (contains? codes :duplicate-key-id))
    (is (contains? codes :duplicate-key-index))))

(deftest unknown-key-and-layer-references-test
  (let [layout (-> valid-layout
                   (assoc-in [:layers :base :bindings :ghost] {:legend "?"})
                   (assoc-in [:strokes "x"] [[:tap :ghost] [:layer :missing]]))
        codes (error-codes layout)]
    (is (contains? codes :unknown-key))
    (is (contains? codes :unknown-layer))))

(deftest unsafe-path-and-remote-url-test
  (is (validate/absolute-or-unsafe-path? "../model.glb"))
  (is (validate/absolute-or-unsafe-path? "/tmp/model.glb"))
  (is (validate/remote-url? "https://example.test/model.glb"))
  (is (not (validate/safe-package-path? "../layout.edn")))
  (is (contains? (error-codes (assoc-in valid-layout [:model :file] "models/keyboard.glb"))
                 :invalid-model-file)))

(deftest reader-form-rejection-test
  (is (validate/unsafe-reader-form? "{:x #custom/tag 1}"))
  (is (not (validate/unsafe-reader-form? "{:languages #{:en :nb}}"))))

(deftest package-theme-validation-test
  (let [valid-theme {:version 1
                     :schemes {:dark {:background "#090a0f"
                                      :foreground "#f1eee7"
                                      :accent "#8b83ff"}
                               :light {:background "#eeece6"
                                       :foreground "#17171b"
                                       :accent "#6258cf"}}
                     :layer-colors {:base "#c8c4bd" :num "#d6a75d"}}
        themed (assoc valid-layout :theme valid-theme)]
    (is (empty? (validate/validate-layout themed)))
    (testing "legacy packages remain valid"
      (is (empty? (validate/validate-layout valid-layout))))
    (testing "unknown keys and non-opaque values point to the exact path"
      (let [errors (validate/validate-layout
                    (-> themed
                        (assoc-in [:theme :schemes :dark :font] "Comic Sans")
                        (assoc-in [:theme :layer-colors :num] "url(https://bad.test/a)")))]
        (is (error-at? errors [:theme :schemes :dark :font] :unknown-theme-key))
        (is (error-at? errors [:theme :layer-colors :num] :invalid-theme-color))))
    (testing "functions and alpha colors are not part of the package surface"
      (let [errors (validate/validate-layout
                    (assoc-in themed [:theme :schemes :dark :accent]
                              "color-mix(in srgb, red 50%, blue)"))]
        (is (error-at? errors [:theme :schemes :dark :accent] :invalid-theme-color))))
    (testing "theme structure and version errors keep exact paths"
      (is (error-at? (validate/validate-layout (assoc themed :theme []))
                     [:theme] :invalid-theme))
      (is (error-at? (validate/validate-layout
                      (assoc-in themed [:theme :version] 8))
                     [:theme :version] :unsupported-theme-version))
      (is (error-at? (validate/validate-layout
                      (assoc-in themed [:theme :schemes :sepia]
                                {:background "#ffffff"}))
                     [:theme :schemes :sepia] :unknown-theme-key))
      (is (error-at? (validate/validate-layout
                      (assoc-in themed [:theme :layer-colors :gaming] "#abcdef"))
                     [:theme :layer-colors :gaming] :unknown-theme-key)))
    (testing "unreadable core pairs are rejected"
      (let [errors (validate/validate-layout
                    (-> themed
                        (assoc-in [:theme :schemes :dark :background] "#111111")
                        (assoc-in [:theme :schemes :dark :foreground] "#121212")))]
        (is (error-at? errors [:theme :schemes :dark :foreground]
                       :insufficient-theme-contrast))))))

(deftest display-definition-validation-test
  (let [layout (-> valid-display-layout
                   (assoc-in [:displays :left :kind] :scripted-display)
                   (assoc-in [:displays :left :role] :decorative)
                   (assoc-in [:displays :left :template] :custom/code)
                   (assoc-in [:displays :left :orientation] :sideways)
                   (assoc-in [:displays :left :widgets 1] :arbitrary-widget)
                   (assoc-in [:displays :left :resolution] [0 9000])
                   (assoc-in [:displays :left :surface-size-mm] [-1 26.5]))
        errors (validate/validate-layout layout)]
    (is (error-at? errors [:displays :left :kind] :unsupported-display-kind))
    (is (error-at? errors [:displays :left :role] :unsupported-display-role))
    (is (error-at? errors [:displays :left :template] :unsupported-display-template))
    (is (error-at? errors [:displays :left :orientation] :unsupported-display-orientation))
    (is (error-at? errors [:displays :left :widgets 1] :unsupported-display-widget))
    (is (error-at? errors [:displays :left :resolution] :invalid-display-resolution))
    (is (error-at? errors [:displays :left :surface-size-mm] :invalid-display-surface))))

(deftest display-identity-and-reference-validation-test
  (let [duplicate-node (assoc-in valid-display-layout
                                 [:displays :right :node]
                                 "display_screen_left")
        duplicate-errors (validate/validate-layout duplicate-node)
        missing-battery (assoc-in valid-display-layout [:displays :left :battery] :missing)
        battery-errors (validate/validate-layout missing-battery)]
    (is (error-at? duplicate-errors [:displays :left :node] :duplicate-display-node))
    (is (error-at? duplicate-errors [:displays :right :node] :duplicate-display-node))
    (is (error-at? battery-errors [:displays :left :battery] :unknown-battery))
    (is (error-at? battery-errors
                   [:display/default-status :batteries :missing]
                   :missing-battery-status))))

(deftest display-template-contract-validation-test
  (let [role-errors (validate/validate-layout
                     (assoc-in valid-display-layout [:displays :left :role] :peripheral))
        widget-errors (validate/validate-layout
                       (update-in valid-display-layout [:displays :left :widgets] pop))
        resolution-errors (validate/validate-layout
                           (assoc-in valid-display-layout
                                     [:displays :left :resolution] [68 160]))
        orientation-errors (validate/validate-layout
                            (assoc-in valid-display-layout
                                      [:displays :left :orientation] :portrait-inverted))]
    (is (error-at? role-errors [:displays :left :role] :display-template-role-mismatch))
    (is (error-at? widget-errors [:displays :left :widgets]
                   :display-template-widget-mismatch))
    (is (error-at? resolution-errors [:displays :left :resolution]
                   :display-template-resolution-mismatch))
    (is (error-at? orientation-errors [:displays :left :orientation]
                   :display-template-orientation-mismatch))))

(deftest display-status-value-validation-test
  (let [layout (-> valid-display-layout
                   (assoc-in [:display/default-status :source] :hardware)
                   (assoc-in [:display/default-status :batteries :left :percent] 101)
                   (assoc-in [:display/default-status :batteries :right :usb-powered?] :maybe)
                   (assoc-in [:display/default-status :endpoint :transport] :radio)
                   (assoc-in [:display/default-status :wpm :current] 256)
                   (assoc-in [:display/default-status :wpm :history] [0 1])
                   (assoc-in [:display/default-status :layer] :unknown)
                   (assoc-in [:display/default-status :split-connection] :pairing)
                   (assoc-in [:display/default-status :peripheral-art] :remote-url))
        errors (validate/validate-layout layout)]
    (is (error-at? errors [:display/default-status :source]
                   :unsupported-display-status-source))
    (is (error-at? errors [:display/default-status :batteries :left :percent]
                   :invalid-battery-percentage))
    (is (error-at? errors [:display/default-status :batteries :right :usb-powered?]
                   :invalid-usb-powered-state))
    (is (error-at? errors [:display/default-status :endpoint :transport]
                   :unsupported-display-transport))
    (is (error-at? errors [:display/default-status :wpm :current] :invalid-display-wpm))
    (is (error-at? errors [:display/default-status :wpm :history] :invalid-wpm-history))
    (is (error-at? errors [:display/default-status :layer] :unknown-layer))
    (is (error-at? errors [:display/default-status :split-connection]
                   :invalid-split-connection-state))
    (is (error-at? errors [:display/default-status :peripheral-art]
                   :invalid-peripheral-art))))

(deftest bluetooth-profile-validation-test
  (let [layout (-> valid-display-layout
                   (assoc-in [:display/default-status :endpoint :profile] 9)
                   (assoc-in [:display/default-status :bluetooth-profiles 1 :index] 0)
                   (assoc-in [:display/default-status :bluetooth-profiles 1 :selected?] true)
                   (assoc-in [:display/default-status :bluetooth-profiles 2 :bonded?] false)
                   (assoc-in [:display/default-status :bluetooth-profiles 2 :connected?] true))
        errors (validate/validate-layout layout)]
    (is (error-at? errors [:display/default-status :endpoint :profile]
                   :unknown-bluetooth-profile))
    (is (some #(= :duplicate-bluetooth-profile-index (:code %)) errors))
    (is (some #(= :invalid-selected-profile-count (:code %)) errors))
    (is (error-at? errors [:display/default-status :bluetooth-profiles 2 :connected?]
                   :invalid-bluetooth-profile-state))))
