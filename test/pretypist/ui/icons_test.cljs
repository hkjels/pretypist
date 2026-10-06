(ns pretypist.ui.icons-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [pretypist.ui.icons :as icons]))

(deftest lucide-nodes-become-replicant-data-test
  (doseq [icon-name (keys icons/icons)]
    (testing (name icon-name)
      (let [[tag attrs & children] (icons/icon icon-name)]
        (is (= :svg tag))
        (is (= "icon" (:class attrs)))
        (is (= "0 0 24 24" (:viewBox attrs)))
        (is (seq children))
        (is (every? #(keyword? (first %)) children))))))
