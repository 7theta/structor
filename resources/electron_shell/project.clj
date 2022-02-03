(defproject com.7theta/electron-shell "0.1.0"
  :dependencies [[org.clojure/clojure "1.10.3"]
                 [org.clojure/clojurescript "1.10.879"
                  :exclusions
                  [com.google.javascript/closure-compiler-unshaded
                   org.clojure/google-closure-library
                   org.clojure/google-closure-library-third-party
                   com.cognitect/transit-clj]]
                 [com.7theta/utilis "1.15.0"]]
  :profiles {:dev {:source-paths ["src"]
                   :dependencies [[thheller/shadow-cljs "2.15.2"]]}})
