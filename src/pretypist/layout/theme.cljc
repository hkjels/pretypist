(ns pretypist.layout.theme)

(def theme-version 1)

(def scheme-token-keys
  #{:background
    :foreground
    :muted-foreground
    :accent
    :error
    :focus
    :prose-current
    :prose-completed
    :prose-upcoming
    :panel})

(def layer-color-keys #{:base :num :sym :nav :mouse})
(def scheme-keys #{:dark :light})
(def root-keys #{:version :schemes :layer-colors})

(def default-schemes
  {:dark
   {:background "#090a0f"
    :foreground "#f1eee7"
    :muted-foreground "#686a75"
    :accent "#8b83ff"
    :error "#b96f72"
    :focus "#aaa4ff"
    :prose-current "#f1eee7"
    :prose-completed "#74757e"
    :prose-upcoming "#686a75"
    :panel "#111218"}

   :light
   {:background "#f7f6f2"
    :foreground "#111216"
    :muted-foreground "#616269"
    :accent "#5b50c8"
    :error "#994b52"
    :focus "#493db9"
    :prose-current "#111216"
    :prose-completed "#6b6c72"
    :prose-upcoming "#595a62"
    :panel "#ebe9e3"}})

(def default-layer-colors
  {:base "#c8c4bd"
   :num "#d6a75d"
   :sym "#8b83ff"
   :nav "#6297d5"
   :mouse "#54aaa2"})

(def css-token-names
  {:background "--pt-background"
   :foreground "--pt-foreground"
   :muted-foreground "--pt-muted-foreground"
   :accent "--pt-accent"
   :error "--pt-error"
   :focus "--pt-focus"
   :prose-current "--pt-prose-current"
   :prose-completed "--pt-prose-completed"
   :prose-upcoming "--pt-prose-upcoming"
   :panel "--pt-panel"})

(def layer-css-token-names
  {:base "--pt-layer-base"
   :num "--pt-layer-num"
   :sym "--pt-layer-sym"
   :nav "--pt-layer-nav"
   :mouse "--pt-layer-mouse"})

(defn opaque-hex-color? [value]
  (and (string? value) (boolean (re-matches #"(?i)^#[0-9a-f]{6}$" value))))

(defn- parse-hex-byte [value start]
  #?(:cljs (js/parseInt (subs value start (+ start 2)) 16)
     :clj (Integer/parseInt (subs value start (+ start 2)) 16)))

(defn hex->rgb [value]
  (when (opaque-hex-color? value)
    (mapv #(/ % 255.0)
          [(parse-hex-byte value 1)
           (parse-hex-byte value 3)
           (parse-hex-byte value 5)])))

(defn- pow [base exponent]
  #?(:cljs (js/Math.pow base exponent)
     :clj (Math/pow base exponent)))

(defn- linear-channel [channel]
  (if (<= channel 0.04045)
    (/ channel 12.92)
    (pow (/ (+ channel 0.055) 1.055) 2.4)))

(defn relative-luminance [color]
  (when-let [[red green blue] (hex->rgb color)]
    (+ (* 0.2126 (linear-channel red))
       (* 0.7152 (linear-channel green))
       (* 0.0722 (linear-channel blue)))))

(defn contrast-ratio [first-color second-color]
  (when-let [first-luminance (relative-luminance first-color)]
    (when-let [second-luminance (relative-luminance second-color)]
      (let [[lighter darker] (sort > [first-luminance second-luminance])]
        (/ (+ lighter 0.05) (+ darker 0.05))))))

(def contrast-pairs
  [{:token :foreground :against :background :minimum 4.5 :label "foreground text"}
   {:token :muted-foreground :against :background :minimum 3.0 :label "muted text"}
   {:token :focus :against :background :minimum 3.0 :label "focus indicator"}
   {:token :prose-current :against :background :minimum 4.5 :label "current prose"}
   {:token :prose-completed :against :background :minimum 3.0 :label "completed prose"}
   {:token :prose-upcoming :against :background :minimum 3.0 :label "upcoming prose"}
   {:token :foreground :against :panel :minimum 4.5 :label "panel text"}])

(defn resolved-scheme [theme scheme]
  ;; A completely absent scheme falls back wholesale. A declared scheme may
  ;; override a subset of semantic tokens and inherits the remaining safe
  ;; PRETYPIST defaults.
  (merge (get default-schemes scheme)
         (get-in theme [:schemes scheme])))

(defn contrast-report [tokens]
  (mapv (fn [{:keys [token against minimum label]}]
          (let [ratio (contrast-ratio (get tokens token) (get tokens against))]
            {:token token
             :against against
             :minimum minimum
             :label label
             :ratio ratio
             :passes? (and ratio (>= ratio minimum))}))
        contrast-pairs))

(defn resolve-theme [layout preference system-dark?]
  (let [theme (:theme layout)
        scheme (case preference
                 :dark :dark
                 :light :light
                 (if system-dark? :dark :light))
        package-scheme? (map? (get-in theme [:schemes scheme]))]
    {:scheme scheme
     :source (if package-scheme? :package :pretypist)
     :tokens (resolved-scheme theme scheme)
     :layer-colors (merge default-layer-colors (:layer-colors theme))}))

(defn preview [layout]
  (into {}
        (map (fn [scheme]
               (let [resolved (resolve-theme layout scheme (= scheme :dark))]
                 [scheme (assoc resolved
                                :provided? (map? (get-in layout [:theme :schemes scheme]))
                                :contrast (contrast-report (:tokens resolved)))])))
        [:dark :light]))

(defn css-properties [{:keys [tokens layer-colors]}]
  (merge
   (into {} (map (fn [[token custom-property]]
                   [custom-property (get tokens token)]))
         css-token-names)
   (into {} (map (fn [[layer custom-property]]
                   [custom-property (get layer-colors layer)]))
         layer-css-token-names)))
