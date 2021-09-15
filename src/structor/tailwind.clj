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

(defmethod ig/init-key :structor.tailwind/watcher [_ opts]
  {:watcher (watch opts)})

(defmethod ig/halt-key! :structor.tailwind/watcher [_ {:keys [watcher]}]
  (stop watcher))

(defn release
  [{:keys [configs]}]
  (doseq [{:keys [input-file output-file]} configs]
    @(sh/run ["node_modules/.bin/postcss"
              input-file "-o" output-file]
             :environment {"NODE_ENV" "production"
                           "TAILWIND_MODE" "build"})))

(defn watch
  [{:keys [configs]}]
  (let [configs (or configs [{:input-file "resources/css/tailwind.css"
                              :output-file "resources/public/css/main.css"}])]
    (doseq [{:keys [input-file output-file]} configs]
      (sh/exec ["node_modules/.bin/postcss"
                input-file "-o" output-file]
               :environment {"TAILWIND_MODE" "watch"}))
    (map (fn [{:keys [input-file output-file]}]
           (sh/exec ["node_modules/.bin/postcss"
                     input-file "-o" output-file "-w"]
                    :environment {"TAILWIND_MODE" "watch"})) configs)))

(defn stop
  [watcher]
  (doseq [handle watcher]
    (sh/kill handle)))

(defn clean
  [{:keys [configs]}]
  (let [configs (or configs [{:input-file "resources/css/tailwind.css"
                              :output-file "resources/public/css/main.css"}])]
    (map (fn [{:keys [input-file output-file]}]
           @(sh/run ["rm" "-f" output-file])) configs)))
