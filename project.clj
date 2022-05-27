;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/structor "0.8.5"
  :description "shadow-cljs and tailwind builds"
  :url "https://github.com/7theta/structor"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[com.7theta/crusta "1.0.1"]
                 [com.7theta/tailwind "0.6.0"]
                 [binaryage/devtools "1.0.6"]
                 [thheller/shadow-cljs "2.19.0"]
                 [metosin/jsonista "0.3.5"]
                 [integrant "0.8.0"]
                 [integrant/repl "0.3.2"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.11.1"]]}}
  :scm {:name "git"
        :url "https://github.com/7theta/structor"})
