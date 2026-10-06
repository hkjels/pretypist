(ns pretypist.layout.validate
  (:require [clojure.string :as str]
            [pretypist.layout.schema :as schema]
            [pretypist.layout.theme :as theme]))

(defn validation-error [path code message]
  {:path path :code code :message message})

(defn absolute-or-unsafe-path? [value]
  (and (string? value)
       (or (and (> (count value) 1) (str/starts-with? value "/"))
           (and (> (count value) 1) (str/starts-with? value "\\"))
           (boolean (re-find #"^[A-Za-z]:[\\/]" value))
           (some #{".."} (str/split value #"[\\/]")))))

(defn remote-url? [value]
  (and (string? value)
       (boolean (re-find #"(?i)^(https?|ftp|file|data|blob):" value))))

(defn safe-package-path? [path]
  (and (string? path)
       (not (str/blank? path))
       (not (absolute-or-unsafe-path? path))
       (not (remote-url? path))
       (not (str/includes? path "\\"))))

(defn unsafe-reader-form? [text]
  ;; EDN sets are allowed; discard forms and all tagged/custom reader forms are
  ;; rejected. Scan instead of using a regexp so literal `#` characters inside
  ;; legends and emitted text stay valid.
  (loop [index 0
         in-string? false
         escaped? false
         in-comment? false]
    (if (>= index (count text))
      false
      (let [character (.charAt text index)
            following (when (< (inc index) (count text)) (.charAt text (inc index)))]
        (cond
          in-comment?
          (recur (inc index) false false (not (or (= character "\n") (= character "\r"))))

          in-string?
          (cond
            escaped? (recur (inc index) true false false)
            (= character "\\") (recur (inc index) true true false)
            (= character "\"") (recur (inc index) false false false)
            :else (recur (inc index) true false false))

          (= character ";")
          (recur (inc index) false false true)

          (= character "\"")
          (recur (inc index) true false false)

          ;; Skip an EDN character literal (for example \#).
          (= character "\\")
          (recur (+ index 2) false false false)

          (and (= character "#") (not= following "{"))
          true

          :else
          (recur (inc index) false false false))))))

(defn- duplicate-values [xs]
  (->> xs frequencies (keep (fn [[x n]] (when (> n 1) x))) set))

(defn- bounded-number? [value lower upper]
  (and (number? value) (<= lower value upper)))

(defn- bounded-positive-pair? [value integer-values? maximum]
  (and (vector? value)
       (= 2 (count value))
       (every? #(and (if integer-values? (int? %) (number? %))
                     (< 0 %)
                     (<= % maximum))
               value)))

(defn- all-strings [value]
  (cond
    (string? value) [value]
    (map? value) (mapcat (fn [[k v]] (concat (all-strings k) (all-strings v))) value)
    (coll? value) (mapcat all-strings value)
    :else []))

(defn- action-errors [path action layer-ids behavior-ids]
  (let [kind (first action)
        layer-ref (cond
                    (= kind :layer) (last action)
                    (and (= kind :one-shot) (= :layer (second action))) (nth action 2 nil)
                    :else nil)
        behavior-ref (when (= kind :macro) (second action))]
    (vec
     (concat
      (when-not (and (vector? action) (keyword? kind))
        [(validation-error path :invalid-action "Actions must be non-empty vectors beginning with a keyword.")])
      (when (and (keyword? kind) (not (contains? schema/action-kinds kind)))
        [(validation-error path :unsupported-action (str "Unsupported action kind " kind "."))])
      (when (and layer-ref (not (contains? layer-ids layer-ref)))
        [(validation-error (conj path (dec (count action))) :unknown-layer
                           (str "Action references unknown layer " layer-ref "."))])
      (when (and behavior-ref (not (contains? behavior-ids behavior-ref)))
        [(validation-error (conj path 1) :unknown-behavior
                           (str "Action references unknown macro behavior " behavior-ref "."))])))))

(defn- stroke-errors [layout]
  (let [key-ids (set (map :id (:keys layout)))
        layer-ids (set (keys (:layers layout)))]
    (mapcat
     (fn [[character steps]]
       (mapcat
        (fn [[idx step]]
          (let [[op target] step
                path [:strokes character idx]]
            (cond
              (not (and (vector? step) (keyword? op)))
              [(validation-error path :invalid-stroke "Stroke steps must be action vectors.")]

              (and (#{:tap :hold :release :double-tap :double-hold :one-shot} op)
                   (not (contains? key-ids target)))
              [(validation-error (conj path 1) :unknown-key
                                 (str "Stroke references unknown key " target "."))]

              (and (= :layer op) (not (contains? layer-ids target)))
              [(validation-error (conj path 1) :unknown-layer
                                 (str "Stroke references unknown layer " target "."))]

              :else [])))
        (map-indexed vector steps)))
     (:strokes layout))))

(defn- layer-errors [layout]
  (let [key-ids (set (map :id (:keys layout)))
        layer-ids (set (keys (:layers layout)))
        behavior-ids (set (keys (:behaviors layout)))
        combo-ids (set (map :id (:combos layout)))
        emission-ids (set (mapcat #(keys (:emissions %)) (vals (:host-profiles layout))))]
    (mapcat
     (fn [[layer-id layer]]
       (concat
        (when-not (map? (:bindings layer))
          [(validation-error [:layers layer-id :bindings] :invalid-bindings
                             "Layer bindings must be a map keyed by physical key ID.")])
        (mapcat
         (fn [[key-id binding]]
           (let [path [:layers layer-id :bindings key-id]
                 [inherited-layer inherited-key] (:inherited-from binding)]
             (concat
              (cond-> []
                (not (contains? key-ids key-id))
                (conj (validation-error path :unknown-key
                                        (str "Layer binding references unknown key " key-id ".")))

                (not (string? (:legend binding)))
                (conj (validation-error (conj path :legend)
                                        :invalid-legend "Every binding needs a display legend string."))

                (and (:behavior binding) (not (contains? behavior-ids (:behavior binding))))
                (conj (validation-error (conj path :behavior) :unknown-behavior
                                        (str "Binding references unknown behavior " (:behavior binding) ".")))

                (and (:combo-part binding) (not (contains? combo-ids (:combo-part binding))))
                (conj (validation-error (conj path :combo-part) :unknown-combo
                                        (str "Binding references unknown combo " (:combo-part binding) ".")))

                (and (:inherited-from binding) (not (contains? layer-ids inherited-layer)))
                (conj (validation-error (conj path :inherited-from 0) :unknown-layer
                                        (str "Inherited binding references unknown layer " inherited-layer ".")))

                (and (:inherited-from binding) (not (contains? key-ids inherited-key)))
                (conj (validation-error (conj path :inherited-from 1) :unknown-key
                                        (str "Inherited binding references unknown key " inherited-key "."))))
              (mapcat (fn [[gesture action]]
                        (action-errors (conj path :actions gesture) action layer-ids behavior-ids))
                      (:actions binding))
              (mapcat (fn [[gesture emission]]
                        (when-not (contains? emission-ids emission)
                          [(validation-error (conj path :firmware-emissions gesture)
                                             :unknown-emission
                                             (str "Binding references unknown host emission " emission "."))]))
                      (:firmware-emissions binding)))))
         (:bindings layer))))
     (:layers layout))))

(defn- behavior-errors [layout]
  (let [layer-ids (set (keys (:layers layout)))
        behavior-ids (set (keys (:behaviors layout)))]
    (mapcat
     (fn [[behavior-id behavior]]
       (let [path [:behaviors behavior-id]
             direct-actions (keep (fn [field]
                                    (when-let [action (get behavior field)] [field action]))
                                  [:tap :hold :normal :modified])
             sequence-actions (map-indexed vector (:after behavior))]
         (concat
          (mapcat (fn [[field action]]
                    (action-errors (conj path field) action layer-ids behavior-ids))
                  direct-actions)
          (mapcat (fn [[index action]]
                    (action-errors (conj path :after index) action layer-ids behavior-ids))
                  sequence-actions))))
     (:behaviors layout))))

(defn- combo-errors [layout]
  (let [key-ids (set (map :id (:keys layout)))
        layer-ids (set (keys (:layers layout)))
        behavior-ids (set (keys (:behaviors layout)))]
    (mapcat
     (fn [[index combo]]
       (let [path [:combos index]]
         (concat
          (keep (fn [key-id]
                  (when-not (contains? key-ids key-id)
                    (validation-error (conj path :keys) :unknown-key
                                      (str "Combo references unknown key " key-id "."))))
                (:keys combo))
          (keep (fn [layer-id]
                  (when-not (contains? layer-ids layer-id)
                    (validation-error (conj path :layers) :unknown-layer
                                      (str "Combo references unknown layer " layer-id "."))))
                (:layers combo))
          (when (and (:behavior combo) (not (contains? behavior-ids (:behavior combo))))
            [(validation-error (conj path :behavior) :unknown-behavior
                               (str "Combo references unknown behavior " (:behavior combo) "."))])
          (when-let [action (:action combo)]
            (action-errors (conj path :action) action layer-ids behavior-ids)))))
     (map-indexed vector (:combos layout)))))

(defn- battery-errors [layout]
  (let [batteries (:batteries layout)]
    (concat
     (when (and (contains? layout :batteries) (not (map? batteries)))
       [(validation-error [:batteries] :invalid-batteries
                          "Batteries must be a map keyed by stable battery ID.")])
     (when (map? batteries)
       (mapcat
        (fn [[battery-id battery]]
          (let [path [:batteries battery-id]]
            (cond-> []
              (not (keyword? battery-id))
              (conj (validation-error (conj path :id) :invalid-battery-id
                                      "Battery IDs must be keywords."))

              (not (map? battery))
              (conj (validation-error path :invalid-battery
                                      "Each battery definition must be a map."))

              (and (map? battery)
                   (not (contains? schema/battery-kinds (:kind battery))))
              (conj (validation-error (conj path :kind) :unsupported-battery-kind
                                      (str "Unsupported battery kind " (pr-str (:kind battery)) ".")))

              (and (map? battery)
                   (not (contains? schema/telemetry-sources (:telemetry battery))))
              (conj (validation-error (conj path :telemetry) :unsupported-telemetry-source
                                      "Version 1 battery telemetry must be explicitly marked :simulated.")))))
        batteries)))))

(defn- display-definition-errors [layout]
  (let [displays (:displays layout)
        battery-ids (set (keys (:batteries layout)))
        duplicate-nodes (when (map? displays)
                          (duplicate-values (keep :node (vals displays))))]
    (concat
     (when (and (contains? layout :displays) (not (map? displays)))
       [(validation-error [:displays] :invalid-displays
                          "Displays must be a map keyed by stable display ID.")])
     (when (map? displays)
       (mapcat
        (fn [[display-id display]]
          (let [path [:displays display-id]
                kind (:kind display)
                role (:role display)
                template (:template display)
                widgets (:widgets display)
                contract (get schema/display-template-contracts template)
                node (:node display)]
            (concat
             (cond-> []
               (not (keyword? display-id))
               (conj (validation-error (conj path :id) :invalid-display-id
                                       "Display IDs must be keywords."))

               (not (map? display))
               (conj (validation-error path :invalid-display
                                       "Each display definition must be a map."))

               (and (map? display) (not (contains? schema/display-kinds kind)))
               (conj (validation-error (conj path :kind) :unsupported-display-kind
                                       (str "Unsupported display kind " (pr-str kind) ".")))

               (and (map? display)
                    (not (and (string? node) (not (str/blank? node)))))
               (conj (validation-error (conj path :node) :invalid-display-node
                                       "Every display must name a non-empty GLB node."))

               (and (string? node) (contains? duplicate-nodes node))
               (conj (validation-error (conj path :node) :duplicate-display-node
                                       (str "Display GLB node " node " is used more than once.")))

               (and (map? display) (not (contains? schema/display-roles role)))
               (conj (validation-error (conj path :role) :unsupported-display-role
                                       (str "Unsupported display role " (pr-str role) ".")))

               (and (map? display)
                    (not (bounded-positive-pair? (:resolution display) true
                                                 schema/maximum-display-resolution)))
               (conj (validation-error (conj path :resolution) :invalid-display-resolution
                                       "Display resolution must be two positive integers no larger than 4096."))

               (and (map? display)
                    (not (bounded-positive-pair? (:surface-size-mm display) false
                                                 schema/maximum-display-surface-mm)))
               (conj (validation-error (conj path :surface-size-mm) :invalid-display-surface
                                       "Display surface dimensions must be two positive numbers no larger than 1000 mm."))

               (and (map? display)
                    (not (contains? schema/display-orientations (:orientation display))))
               (conj (validation-error (conj path :orientation) :unsupported-display-orientation
                                       (str "Unsupported display orientation "
                                            (pr-str (:orientation display)) ".")))

               (and (map? display) (not (contains? schema/display-templates template)))
               (conj (validation-error (conj path :template) :unsupported-display-template
                                       (str "Unsupported display template " (pr-str template) ".")))

               (and contract (not= role (:role contract)))
               (conj (validation-error (conj path :role) :display-template-role-mismatch
                                       (str "Template " template " requires role " (:role contract) ".")))

               (and contract (not= (:resolution display) (:resolution contract)))
               (conj (validation-error (conj path :resolution)
                                       :display-template-resolution-mismatch
                                       (str "Template " template " requires native resolution "
                                            (pr-str (:resolution contract)) ".")))

               (and contract
                    (not (contains? (:orientations contract) (:orientation display))))
               (conj (validation-error (conj path :orientation)
                                       :display-template-orientation-mismatch
                                       (str "Template " template " supports orientations "
                                            (pr-str (:orientations contract)) ".")))

               (and (map? display) (not (keyword? (:battery display))))
               (conj (validation-error (conj path :battery) :invalid-battery-reference
                                       "A display battery reference must be a battery ID keyword."))

               (and (keyword? (:battery display))
                    (not (contains? battery-ids (:battery display))))
               (conj (validation-error (conj path :battery) :unknown-battery
                                       (str "Display references unknown battery " (:battery display) ".")))

               (and (map? display) (not (vector? widgets)))
               (conj (validation-error (conj path :widgets) :invalid-display-widgets
                                       "Display widgets must be an explicit vector."))

               (and (vector? widgets) (seq (duplicate-values widgets)))
               (conj (validation-error (conj path :widgets) :duplicate-display-widget
                                       "A display widget may occur only once."))

               (and contract (vector? widgets) (not= (set widgets) (:widgets contract)))
               (conj (validation-error (conj path :widgets) :display-template-widget-mismatch
                                       (str "Template " template " requires widgets "
                                            (pr-str (:widgets contract)) "."))))
             (when (vector? widgets)
               (keep-indexed
                (fn [index widget]
                  (when-not (contains? schema/display-widgets widget)
                    (validation-error (conj path :widgets index)
                                      :unsupported-display-widget
                                      (str "Unsupported display widget " (pr-str widget) "."))))
                widgets)))))
        displays)))))

(defn- display-status-errors [layout]
  (let [status (:display/default-status layout)
        status? (contains? layout :display/default-status)
        displays (:displays layout)
        display-values (if (map? displays) (vals displays) [])
        declared-batteries (set (keys (:batteries layout)))
        referenced-batteries (set (keep :battery display-values))
        widgets (set (mapcat :widgets display-values))
        status-batteries (:batteries status)
        profiles (:bluetooth-profiles status)
        profile-indices (when (vector? profiles) (map :index profiles))
        profile-index-set (set profile-indices)
        selected-profiles (when (vector? profiles) (filter :selected? profiles))
        endpoint (:endpoint status)
        wpm (:wpm status)
        known-layer-ids (into (set (keys (:layers layout)))
                              (keys (:layout/firmware-layer-indices layout)))
        validates-widget? (fn [widget] (or (contains? widgets widget)
                                           (contains? status widget)))]
    (concat
     (when (and status? (not (map? status)))
       [(validation-error [:display/default-status] :invalid-display-status
                          "Default display status must be a map.")])
     (when (and status? (empty? displays))
       [(validation-error [:display/default-status] :orphan-display-status
                          "Default display status requires at least one declared display.")])
     (when (map? status)
       (concat
        (when-not (contains? schema/telemetry-sources (:source status))
          [(validation-error [:display/default-status :source]
                             :unsupported-display-status-source
                             "Version 1 default display values must be explicitly marked :simulated.")])
        (when-not (map? status-batteries)
          [(validation-error [:display/default-status :batteries]
                             :invalid-battery-status
                             "Default battery status must be a map keyed by battery ID.")])
        (when (map? status-batteries)
          (concat
           (keep (fn [battery-id]
                   (when-not (contains? status-batteries battery-id)
                     (validation-error [:display/default-status :batteries battery-id]
                                       :missing-battery-status
                                       (str "No default status is defined for display battery " battery-id "."))))
                 referenced-batteries)
           (mapcat
            (fn [[battery-id battery-status]]
              (let [path [:display/default-status :batteries battery-id]]
                (cond-> []
                  (not (contains? declared-batteries battery-id))
                  (conj (validation-error path :unknown-battery
                                          (str "Default status references unknown battery " battery-id ".")))

                  (not (map? battery-status))
                  (conj (validation-error path :invalid-battery-status
                                          "Each default battery status must be a map."))

                  (and (map? battery-status)
                       (not (and (int? (:percent battery-status))
                                 (<= 0 (:percent battery-status) 100))))
                  (conj (validation-error (conj path :percent) :invalid-battery-percentage
                                          "Battery percentage must be an integer from 0 through 100."))

                  (and (map? battery-status)
                       (not (boolean? (:usb-powered? battery-status))))
                  (conj (validation-error (conj path :usb-powered?) :invalid-usb-powered-state
                                          "USB-powered state must be true or false.")))))
            status-batteries)))
        (when (validates-widget? :endpoint)
          (cond
            (not (map? endpoint))
            [(validation-error [:display/default-status :endpoint] :invalid-endpoint-status
                               "Endpoint status must be a map.")]

            (not (contains? schema/display-transports (:transport endpoint)))
            [(validation-error [:display/default-status :endpoint :transport]
                               :unsupported-display-transport
                               (str "Unsupported endpoint transport " (pr-str (:transport endpoint)) "."))]

            (and (contains? endpoint :profile)
                 (not (and (int? (:profile endpoint))
                           (contains? profile-index-set (:profile endpoint)))))
            [(validation-error [:display/default-status :endpoint :profile]
                               :unknown-bluetooth-profile
                               "The endpoint profile must reference a declared Bluetooth profile index.")]
            :else []))
        (when (validates-widget? :bluetooth-profiles)
          (concat
           (when-not (and (vector? profiles) (seq profiles)
                          (<= (count profiles) schema/maximum-bluetooth-profiles))
             [(validation-error [:display/default-status :bluetooth-profiles]
                                :invalid-bluetooth-profiles
                                "Bluetooth profiles must be a non-empty vector of at most 16 profiles.")])
           (when (vector? profiles)
             (concat
              (mapcat
               (fn [[index profile]]
                 (let [path [:display/default-status :bluetooth-profiles index]]
                   (cond-> []
                     (not (map? profile))
                     (conj (validation-error path :invalid-bluetooth-profile
                                             "Each Bluetooth profile status must be a map."))

                     (and (map? profile)
                          (not (and (int? (:index profile))
                                    (<= 0 (:index profile))
                                    (< (:index profile) schema/maximum-bluetooth-profiles))))
                     (conj (validation-error (conj path :index) :invalid-bluetooth-profile-index
                                             "Bluetooth profile indices must be integers from 0 through 15."))

                     (and (map? profile)
                          (not (every? boolean? (map profile [:selected? :bonded? :connected?]))))
                     (conj (validation-error path :invalid-bluetooth-profile-state
                                             "Selected, bonded, and connected profile states must be booleans."))

                     (and (map? profile) (:connected? profile) (not (:bonded? profile)))
                     (conj (validation-error (conj path :connected?) :invalid-bluetooth-profile-state
                                             "A connected Bluetooth profile must also be bonded.")))))
               (map-indexed vector profiles))
              (keep (fn [index]
                      (when (contains? (duplicate-values profile-indices) index)
                        (validation-error [:display/default-status :bluetooth-profiles]
                                          :duplicate-bluetooth-profile-index
                                          (str "Bluetooth profile index " index " occurs more than once."))))
                    (distinct profile-indices))
              (when (not= 1 (count selected-profiles))
                [(validation-error [:display/default-status :bluetooth-profiles]
                                   :invalid-selected-profile-count
                                   "Exactly one Bluetooth profile must be selected.")])))))
        (when (validates-widget? :wpm)
          (concat
           (when-not (map? wpm)
             [(validation-error [:display/default-status :wpm] :invalid-wpm-status
                                "WPM status must be a map.")])
           (when (and (map? wpm)
                      (not (bounded-number? (:current wpm) 0 schema/maximum-display-wpm)))
             [(validation-error [:display/default-status :wpm :current] :invalid-display-wpm
                                "Current WPM must be a number from 0 through 255.")])
           (when (and (map? wpm)
                      (not (and (vector? (:history wpm))
                                (= schema/nice-view-wpm-history-length (count (:history wpm)))
                                (every? #(bounded-number? % 0 schema/maximum-display-wpm)
                                        (:history wpm)))))
             [(validation-error [:display/default-status :wpm :history] :invalid-wpm-history
                                "nice!view WPM history must contain exactly ten values from 0 through 255.")])))
        (when (validates-widget? :layer)
          (when-not (contains? known-layer-ids (:layer status))
            [(validation-error [:display/default-status :layer] :unknown-layer
                               (str "Default display status references unknown layer " (:layer status) "."))]))
        (when (validates-widget? :split-connection)
          (when-not (contains? schema/split-connection-states (:split-connection status))
            [(validation-error [:display/default-status :split-connection]
                               :invalid-split-connection-state
                               (str "Unsupported split connection state "
                                    (pr-str (:split-connection status)) "."))]))
        (when (validates-widget? :peripheral-art)
          (when-not (contains? schema/peripheral-art-options (:peripheral-art status))
            [(validation-error [:display/default-status :peripheral-art]
                               :invalid-peripheral-art
                               (str "Unsupported peripheral illustration "
                                    (pr-str (:peripheral-art status)) "."))])))))))

(defn- display-errors [layout]
  (concat (battery-errors layout)
          (display-definition-errors layout)
          (display-status-errors layout)))

(defn- unknown-key-errors [path allowed value label]
  (when (map? value)
    (keep (fn [key]
            (when-not (contains? allowed key)
              (validation-error (conj path key) :unknown-theme-key
                                (str "Unknown " label " key " (pr-str key) "."))))
          (keys value))))

(defn- theme-scheme-errors [theme-value scheme tokens]
  (when (contains? theme/scheme-keys scheme)
    (concat
     (when-not (map? tokens)
       [(validation-error [:theme :schemes scheme] :invalid-theme-scheme
                          "A theme scheme must be a map of semantic color tokens.")])
     (unknown-key-errors [:theme :schemes scheme]
                         theme/scheme-token-keys tokens "scheme token")
     (when (map? tokens)
       (keep (fn [[token color]]
               (when-not (theme/opaque-hex-color? color)
                 (validation-error [:theme :schemes scheme token]
                                   :invalid-theme-color
                                   "Theme colors must be opaque #RRGGBB values.")))
             tokens))
     (when (and (map? tokens)
                (every? theme/opaque-hex-color? (vals tokens)))
       (keep (fn [{:keys [token against minimum label ratio passes?]}]
               (when-not passes?
                 (validation-error
                  [:theme :schemes scheme token]
                  :insufficient-theme-contrast
                  (str "The " label " has " (.toFixed ratio 2)
                       ":1 contrast against " (name against)
                       "; at least " minimum ":1 is required."))))
             (theme/contrast-report (theme/resolved-scheme theme-value scheme)))))))

(defn- theme-errors [layout]
  (when (contains? layout :theme)
    (let [value (:theme layout)]
      (if-not (map? value)
        [(validation-error [:theme] :invalid-theme
                           "Theme must be a declarative map.")]
        (let [schemes (:schemes value)
              layer-colors (:layer-colors value)]
          (concat
           (unknown-key-errors [:theme] theme/root-keys value "theme")
           (when-not (= theme/theme-version (:version value))
             [(validation-error [:theme :version] :unsupported-theme-version
                                (str "Unsupported theme version " (pr-str (:version value))
                                     "; expected 1."))])
           (when-not (and (map? schemes) (seq schemes))
             [(validation-error [:theme :schemes] :invalid-theme-schemes
                                "Theme schemes must be a non-empty map containing :dark, :light, or both.")])
           (when (map? schemes)
             (concat
              (unknown-key-errors [:theme :schemes] theme/scheme-keys schemes "scheme")
              (mapcat (fn [[scheme tokens]]
                        (theme-scheme-errors value scheme tokens))
                      schemes)))
           (when (and (contains? value :layer-colors) (not (map? layer-colors)))
             [(validation-error [:theme :layer-colors] :invalid-layer-colors
                                "Layer colors must be a map keyed by a supported layer role.")])
           (when (map? layer-colors)
             (concat
              (unknown-key-errors [:theme :layer-colors]
                                  theme/layer-color-keys layer-colors "layer color")
              (keep (fn [[layer color]]
                      (when (and (contains? theme/layer-color-keys layer)
                                 (not (theme/opaque-hex-color? color)))
                        (validation-error [:theme :layer-colors layer]
                                          :invalid-theme-color
                                          "Layer colors must be opaque #RRGGBB values.")))
                    layer-colors)))))))))

(defn validate-layout [layout]
  (let [keys* (:keys layout)
        ids (map :id keys*)
        indices (map :index keys*)
        nodes (map :node keys*)
        duplicate-ids (duplicate-values ids)
        duplicate-indices (duplicate-values indices)
        strings (all-strings layout)]
    (vec
     (concat
      (when-not (map? layout)
        [(validation-error [] :invalid-root "layout.edn must contain one EDN map.")])
      (when-not (= schema/supported-format-version (:pretypist/format-version layout))
        [(validation-error [:pretypist/format-version] :unsupported-format
                           (str "Unsupported PRETYPIST format version "
                                (pr-str (:pretypist/format-version layout)) "; expected 1."))])
      (when-not (and (string? (:package/id layout)) (not (str/blank? (:package/id layout))))
        [(validation-error [:package/id] :required "Package ID must be a non-empty string.")])
      (when-not (vector? keys*)
        [(validation-error [:keys] :invalid-keys "Physical keys must be a vector.")])
      (when (> (count keys*) schema/maximum-key-count)
        [(validation-error [:keys] :too-many-keys "A layout may define at most 256 physical keys.")])
      (mapcat (fn [[idx key]]
                (concat
                 (when-not (keyword? (:id key))
                   [(validation-error [:keys idx :id] :invalid-key-id "Key IDs must be keywords.")])
                 (when-not (and (int? (:index key)) (<= 0 (:index key)))
                   [(validation-error [:keys idx :index] :invalid-key-index "Key indices must be non-negative integers.")])
                 (when-not (and (string? (:node key)) (not (str/blank? (:node key))))
                   [(validation-error [:keys idx :node] :invalid-node "Every key must name its GLB node.")])
                 (when-not (or (nil? (:observed-codes key))
                               (and (vector? (:observed-codes key))
                                    (every? #(and (string? %) (not (str/blank? %))) (:observed-codes key))))
                   [(validation-error [:keys idx :observed-codes] :invalid-code
                                      "Observed KeyboardEvent codes must be a vector of non-empty strings.")])))
              (map-indexed vector keys*))
      (map (fn [id] (validation-error [:keys] :duplicate-key-id (str "Duplicate physical key ID " id "."))) duplicate-ids)
      (map (fn [idx] (validation-error [:keys] :duplicate-key-index (str "Duplicate physical key index " idx "."))) duplicate-indices)
      (when (seq (duplicate-values nodes))
        [(validation-error [:keys] :duplicate-key-node "Physical keys must map to unique GLB node names.")])
      (when-not (map? (:layers layout))
        [(validation-error [:layers] :invalid-layers "Layers must be a map.")])
      (when (and (contains? layout :layout/default-layer)
                 (not (contains? (set (keys (:layers layout))) (:layout/default-layer layout))))
        [(validation-error [:layout/default-layer] :unknown-layer
                           "The default layer must reference a declared layer.")])
      (when (and (contains? layout :layout/default-profile)
                 (not (contains? (set (keys (:host-profiles layout))) (:layout/default-profile layout))))
        [(validation-error [:layout/default-profile] :unknown-host-profile
                           "The default host profile must reference a declared host profile.")])
      (keep (fn [[profile-index profile-id]]
              (when-not (contains? (set (keys (:host-profiles layout))) profile-id)
                (validation-error [:layout/host-selection :profiles profile-index]
                                  :unknown-host-profile
                                  (str "Host selection references unknown profile " profile-id "."))))
            (get-in layout [:layout/host-selection :profiles]))
      (layer-errors layout)
      (behavior-errors layout)
      (combo-errors layout)
      (stroke-errors layout)
      (display-errors layout)
      (theme-errors layout)
      (when-not (contains? #{nil :normalized :preserve} (get-in layout [:model :material-policy]))
        [(validation-error [:model :material-policy] :invalid-material-policy
                           "Material policy must be :normalized or :preserve.")])
      (when-not (safe-package-path? (get-in layout [:model :file]))
        [(validation-error [:model :file] :unsafe-path "Model file must be a safe relative package path.")])
      (when (and (safe-package-path? (get-in layout [:model :file]))
                 (not= "model.glb" (get-in layout [:model :file])))
        [(validation-error [:model :file] :invalid-model-file
                           "Version 1 packages must reference model.glb at the archive root.")])
      (keep-indexed
       (fn [idx value]
         (cond
           (> (count value) 16384)
           (validation-error [:strings idx] :string-too-long "Layout strings may not exceed 16 KiB.")
           (remote-url? value)
           (validation-error [:strings idx] :remote-url "Remote URLs are not allowed in version 1 packages.")
           (absolute-or-unsafe-path? value)
           (validation-error [:strings idx] :unsafe-path "Absolute and parent-relative paths are not allowed.")
           :else nil))
       strings)))))

(defn valid-layout? [layout]
  (empty? (validate-layout layout)))

(defn humanize-errors [errors]
  (str/join "\n" (map (fn [{:keys [path message]}]
                          (str (if (seq path) (pr-str path) "layout.edn") ": " message))
                        errors)))
