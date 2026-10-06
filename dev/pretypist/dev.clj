(ns pretypist.dev
  (:require [cljs.main :as cljs]
            [cljs.repl.browser :as browser]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def package-mime "application/vnd.pretypist+zip")

(defn prepare-development-shell! []
  ;; Closure's unoptimized loader inserts inline scripts, and the browser REPL
  ;; evaluates incoming JavaScript. Scope those permissions to the dev shell.
  (let [output (io/file "target/dev-shell/index.html")
        html (slurp "resources/public/index.html")]
    (io/make-parents output)
    (spit output (str/replace html "script-src 'self';"
                              "script-src 'self' 'unsafe-inline' 'unsafe-eval';"))))

(defn -main [& args]
  (prepare-development-shell!)
  ;; cljs.repl.browser dispatches only extensions it knows and otherwise reads
  ;; static files as UTF-8. Register the package as byte-preserving Latin-1 so
  ;; the exact ZIP can pass through its small development server.
  (alter-var-root #'browser/ext->mime-type assoc ".pretypist" package-mime)
  (alter-var-root #'browser/ext->mime-type assoc ".webmanifest" "application/manifest+json"
                   ".svg" "image/svg+xml" ".png" "image/png")
  (alter-var-root #'browser/mime-type->encoding assoc "image/png" "ISO-8859-1")
  (alter-var-root #'browser/mime-type->encoding assoc package-mime "ISO-8859-1")
  (apply cljs/-main args))
