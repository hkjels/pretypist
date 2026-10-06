(ns pretypist.test-runner
  (:require [cljs.test :as test]
            [pretypist.support.image-bitmap :as image-bitmap]
            [pretypist.corpus.analysis-test]
            [pretypist.corpus.selector-test]
            [pretypist.continuity-test]
            [pretypist.display.status-test]
            [pretypist.landing-test]
            [pretypist.layout.package-test]
            [pretypist.layout.schema-test]
            [pretypist.layout.theme-test]
            [pretypist.layout.validate-test]
            [pretypist.nexus-test]
            [pretypist.onboarding-test]
            [pretypist.persistence-test]
            [pretypist.scene.controller-test]
            [pretypist.scene.displays-test]
            [pretypist.scene.legends-test]
            [pretypist.scene.model-test]
            [pretypist.state-test]
            [pretypist.typing.adaptive-test]
            [pretypist.typing.analysis-test]
            [pretypist.typing.gesture-test]
            [pretypist.typing.input-test]
            [pretypist.typing.metrics-test]
            [pretypist.ui.completion-test]
            [pretypist.ui.icons-test]
            [pretypist.ui.refinement-test]))

(defmethod test/report [::test/default :end-run-tests] [summary]
  (set! (.-exitCode js/process) (if (test/successful? summary) 0 1)))

(defn -main []
  (image-bitmap/install!)
  (test/run-tests 'pretypist.corpus.analysis-test
                  'pretypist.corpus.selector-test
                  'pretypist.continuity-test
                  'pretypist.display.status-test
                  'pretypist.landing-test
                  'pretypist.layout.package-test
                  'pretypist.layout.schema-test
                  'pretypist.layout.theme-test
                  'pretypist.layout.validate-test
                  'pretypist.nexus-test
                  'pretypist.onboarding-test
                  'pretypist.persistence-test
                  'pretypist.scene.controller-test
                  'pretypist.scene.displays-test
                  'pretypist.scene.legends-test
                  'pretypist.scene.model-test
                  'pretypist.state-test
                  'pretypist.typing.adaptive-test
                  'pretypist.typing.analysis-test
                  'pretypist.typing.gesture-test
                  'pretypist.typing.input-test
                  'pretypist.typing.metrics-test
                  'pretypist.ui.completion-test
                  'pretypist.ui.icons-test
                  'pretypist.ui.refinement-test))

(set! *main-cli-fn* -main)
