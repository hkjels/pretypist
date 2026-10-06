(ns pretypist.corpus-build
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [pretypist.corpus.analysis :as analysis]))

(def source-directory "resources/corpora")
(def layout-file "resources/layout-sources/modified-engram/layout.edn")
(def output-file "resources/corpora/metadata.edn")

(defn- read-edn [path]
  (with-open [reader (java.io.PushbackReader. (io/reader path))]
    (edn/read {:eof nil} reader)))

(defn- render [value]
  (str (with-out-str (pprint/pprint value))))

(defn- validate-passage! [passage]
  (when-not (keyword? (:id passage))
    (throw (ex-info "Every passage requires a keyword :id." {:passage passage})))
  (when-not (contains? #{:en :nb} (:language passage))
    (throw (ex-info "Every passage requires :language :en or :nb." {:id (:id passage)})))
  (when-not (and (string? (:title passage)) (not-empty (:title passage)))
    (throw (ex-info "Every passage requires a non-empty :title." {:id (:id passage)})))
  (when-not (and (contains? (set (:tags passage)) :reviewed)
                 (contains? (set (:tags passage)) :coherent-prose))
    (throw (ex-info "Every passage must be tagged :reviewed and :coherent-prose."
                    {:id (:id passage)})))
  (when-not (seq (:chunks passage))
    (throw (ex-info "Every passage requires authored phrase chunks." {:id (:id passage)})))
  (doseq [[index chunk] (map-indexed vector (:chunks passage))
          :let [word-count (if (string? chunk) (analysis/token-count chunk) 0)]]
    (when-not (and (string? chunk) (<= 2 word-count 5))
      (throw (ex-info "Authored phrase chunks must contain two to five words."
                      {:id (:id passage) :chunk-index index
                       :word-count word-count :chunk chunk}))))
  passage)

(defn -main [& arguments]
  (let [layout (read-edn layout-file)
        passages (vec (mapcat #(read-edn (str source-directory "/" %))
                              ["en.edn" "nb.edn"]))
        ids (map :id passages)
        _ (when-not (= (count ids) (count (distinct ids)))
            (throw (ex-info "Corpus passage IDs must be unique." {})))
        _ (run! validate-passage! passages)
        output (render (analysis/analyze-corpora layout passages))
        check? (some #{"--check"} arguments)
        existing (when (.exists (io/file output-file)) (slurp output-file))]
    (if check?
      (if (= existing output)
        (println "Corpus metadata is current.")
        (throw (ex-info (str output-file " is stale; run npm run analyze:corpora.") {})))
      (do
        (spit output-file output)
        (println (str "Analyzed " (count passages) " passages into " output-file "."))))))
