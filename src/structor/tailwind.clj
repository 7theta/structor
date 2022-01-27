;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns structor.tailwind
  (:require [crusta.core :as sh]
            [integrant.core :as ig]))

(declare watch stop)

(defmethod ig/init-key :structor.tailwind/watcher [_ _]
  {:watcher (watch)})

(defmethod ig/halt-key! :structor.tailwind/watcher [_ {:keys [watcher]}]
  (stop watcher))

(defn release
  []
  @(sh/run ["npx" "tailwindcss" "-i" "resources/css/input.css" "-o" "resources/public/css/main.css" "--minify"]
     :environment {"NODE_ENV" "production"
                   "TAILWIND_MODE" "build"}))

(defn watch
  []
  (sh/exec ["npx" "tailwindcss" "-i" "resources/css/input.css" "-o" "resources/public/css/main.css"]
           :environment {"TAILWIND_MODE" "watch"})
  (sh/exec ["npx" "tailwindcss" "-i" "resources/css/input.css" "-o" "resources/public/css/main.css" "--watch"]
           :environment {"TAILWIND_MODE" "watch"}))

(defn stop
  [watcher]
  (sh/kill watcher))

(defn clean
  []
  @(sh/run ["rm" "-f" "resources/public/css/main.css"]))
