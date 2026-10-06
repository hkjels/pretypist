(ns pretypist.ui.icons
  (:require [goog.object :as gobj]
            ["lucide/dist/esm/icons/settings.js" :as settings-module]
            ["lucide/dist/esm/icons/upload.js" :as upload-module]
            ["lucide/dist/esm/icons/keyboard.js" :as keyboard-module]
            ["lucide/dist/esm/icons/x.js" :as x-module]
            ["lucide/dist/esm/icons/play.js" :as play-module]
            ["lucide/dist/esm/icons/pause.js" :as pause-module]
            ["lucide/dist/esm/icons/rotate-ccw.js" :as rotate-ccw-module]
            ["lucide/dist/esm/icons/trash-2.js" :as trash-module]
            ["lucide/dist/esm/icons/check.js" :as check-module]
            ["lucide/dist/esm/icons/triangle-alert.js" :as triangle-alert-module]
            ["lucide/dist/esm/icons/arrow-right.js" :as arrow-right-module]
            ["lucide/dist/esm/icons/chevron-down.js" :as chevron-down-module]))

(def icons
  ;; A string lookup keeps Closure Advanced from renaming the ESM `default`
  ;; property while esbuild preserves Lucide's small, framework-free nodes.
  {:settings (gobj/get settings-module "default")
   :upload (gobj/get upload-module "default")
   :keyboard (gobj/get keyboard-module "default")
   :close (gobj/get x-module "default")
   :play (gobj/get play-module "default")
   :pause (gobj/get pause-module "default")
   :reset-camera (gobj/get rotate-ccw-module "default")
   :trash (gobj/get trash-module "default")
   :check (gobj/get check-module "default")
   :warning (gobj/get triangle-alert-module "default")
   :arrow-right (gobj/get arrow-right-module "default")
   :chevron-down (gobj/get chevron-down-module "default")})

(defn- node->hiccup [[tag attrs]]
  (into [(keyword tag) (js->clj attrs :keywordize-keys true)] []))

(defn icon
  ([name] (icon name {}))
  ([name attrs]
   (let [[_ root-attrs children] (js->clj (get icons name) :keywordize-keys true)]
     (into [:svg (merge root-attrs
                        {:aria-hidden "true" :focusable "false" :class "icon"}
                        attrs)]
           (map node->hiccup children)))))
