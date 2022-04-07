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
            [integrant.core :as ig]
            [utilis.map :refer [compact]]
            [clojure.java.io :as io]
            [clojure.string :as st]))

(declare watch stop)

(def default-input-file "resources/css/input.css")
(def default-output-file "resources/public/css/main.css")

(defmethod ig/init-key :structor.tailwind/watcher [_ opts]
  {:watcher (watch opts)})

(defmethod ig/halt-key! :structor.tailwind/watcher [_ {:keys [watcher]}]
  (stop watcher))

(defn release
  ([] (release nil))
  ([{:keys [input-file output-file]
     :or {input-file default-input-file
          output-file default-output-file}}]
   @(sh/run ["npx" "tailwindcss" "-i" input-file "-o" output-file "--minify"]
      :environment {"NODE_ENV" "production"
                    "TAILWIND_MODE" "build"})))

(defn watch
  ([] (watch nil))
  ([{:keys [input-file output-file]
     :or {input-file default-input-file
          output-file default-output-file}}]
   (sh/exec ["npx" "tailwindcss" "-i" input-file "-o" output-file])
   (sh/exec ["npx" "tailwindcss" "-i" input-file "-o" output-file "--watch"]
            :environment {"TAILWIND_MODE" "watch"})))

(defn stop
  [watcher]
  (sh/kill watcher))

(defn clean
  ([] (clean nil))
  ([{:keys [output-file]
     :or {output-file default-output-file}}]
   @(sh/run ["rm" "-f" output-file])))
