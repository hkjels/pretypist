(ns pretypist.layout.theme-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.layout.theme :as theme]))

(deftest opaque-color-and-contrast-test
  (is (theme/opaque-hex-color? "#8b83ff"))
  (is (theme/opaque-hex-color? "#ABCDEF"))
  (is (not (theme/opaque-hex-color? "#fff")))
  (is (not (theme/opaque-hex-color? "rgba(0,0,0,.5)")))
  (is (> (theme/contrast-ratio "#f1eee7" "#090a0f") 15)))

(deftest theme-resolution-test
  (let [layout {:theme {:version 1
                        :schemes {:dark {:accent "#123456"}}
                        :layer-colors {:num "#abcdef"}}}
        dark (theme/resolve-theme layout :system true)
        light (theme/resolve-theme layout :light true)]
    (testing "declared schemes inherit only missing semantic tokens"
      (is (= "#123456" (get-in dark [:tokens :accent])))
      (is (= "#f1eee7" (get-in dark [:tokens :foreground])))
      (is (= :package (:source dark))))
    (testing "a wholly absent scheme uses the complete PRETYPIST fallback"
      (is (= (get theme/default-schemes :light) (:tokens light)))
      (is (= :pretypist (:source light))))
    (is (= "#abcdef" (get-in dark [:layer-colors :num])))
    (is (= "#8b83ff" (get-in dark [:layer-colors :sym])))))

(deftest css-properties-are-fixed-test
  (let [properties (theme/css-properties
                    (theme/resolve-theme {} :dark true))]
    (is (= "#090a0f" (get properties "--pt-background")))
    (is (= "#d6a75d" (get properties "--pt-layer-num")))
    (is (= 15 (count properties)))))
