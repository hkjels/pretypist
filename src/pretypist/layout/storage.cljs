(ns pretypist.layout.storage)

(def database-name "pretypist-layouts")
(def database-version 1)
(def package-store "packages")

(defn open-database []
  (js/Promise.
   (fn [resolve reject]
     (let [request (.open js/indexedDB database-name database-version)]
       (set! (.-onupgradeneeded request)
             (fn [_]
               (let [db (.-result request)]
                 (when-not (.contains (.-objectStoreNames db) package-store)
                   (.createObjectStore db package-store #js {:keyPath "id"})))))
       (set! (.-onsuccess request) #(resolve (.-result request)))
       (set! (.-onerror request) #(reject (.-error request)))))))

(defn- transaction-promise [db mode operation]
  (js/Promise.
   (fn [resolve reject]
     (let [tx (.transaction db #js [package-store] mode)
           store (.objectStore tx package-store)
           request (operation store)]
       (set! (.-onsuccess request) #(resolve (.-result request)))
       (set! (.-onerror request) #(reject (.-error request)))))))

(defn install! [db package]
  (let [layout (:layout package)
        record #js {:id (:package/id layout)
                    :name (:package/name layout)
                    :revision (:package/revision layout)
                    :installedAt (.toISOString (js/Date.))
                    :layoutText (:layout-text package)
                    :modelBuffer (:model-buffer package)
                    :previewBuffer (:preview-buffer package)
                    :zipBuffer (:zip-buffer package)}]
    (transaction-promise db "readwrite" #(.put % record))))

(defn remove! [db id]
  (transaction-promise db "readwrite" #(.delete % id)))

(defn get-one [db id]
  (-> (transaction-promise db "readonly" #(.get % id))
      (.then #(when % (js->clj % :keywordize-keys true)))))

(defn list-all [db]
  (-> (transaction-promise db "readonly" #(.getAll %))
      (.then #(mapv (fn [item]
                      (select-keys (js->clj item :keywordize-keys true)
                                   [:id :name :revision :installedAt]))
                    (array-seq %)))))
