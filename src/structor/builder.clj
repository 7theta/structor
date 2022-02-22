;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns structor.builder
  (:require [structor.shadow-cljs :as shadow-cljs]
            [structor.tailwind :as tailwind]
            [structor.electron :as electron]
            [crusta.core :as sh]
            [utilis.fn :refer [fsafe]]
            [integrant.core :as ig]
            [clojure.java.io :as io])
  (:import [java.net URL URLConnection]))

(declare watch stop clean npm-available?)

(defmethod ig/init-key :structor.builder/watcher [_ {:keys [hooks] :as opts}]
  (watch opts))

(defmethod ig/halt-key! :structor.builder/watcher [_ watchers]
  (stop watchers))

(defn init
  []
  (println @(sh/run ["sh" "-c" ["npm" "install"]]))
  (println @(sh/run ["npx" "browserslist@latest" "--update-db"])))

(defn release
  ([] (release nil))
  ([{:keys [hooks electron tailwind]}]
   (clean)
   (when (npm-available?) (init))
   ((fsafe (:init hooks)))
   (println (shadow-cljs/release))
   (println (tailwind/release tailwind))
   (println @(sh/run ["lein" "uberjar"]))
   ((fsafe (:uberjar hooks)))
   (when (and electron (electron/available?))
     (electron/release))))

(defn watch
  ([] (watch nil))
  ([{:keys [hooks tailwind]}]
   (clean)
   (when (npm-available?) (init))
   ((fsafe (:init hooks)))
   {:shadow-cljs (shadow-cljs/watch)
    :tailwind (tailwind/watch tailwind)}))

(defn stop
  [watchers]
  (println (shadow-cljs/stop (:shadow-cljs watchers)))
  (println (tailwind/stop (:tailwind watchers)))
  nil)

(defn clean
  []
  (shadow-cljs/clean)
  (tailwind/clean)
  (electron/clean))

;;; Private

(defn- npm-available?
  []
  (try
    (let [url (URL. "http://npmjs.org")
          connection (doto (.openConnection url)
                       (.connect))]
      (-> connection (.getInputStream) (.close))
      true)
    (catch Exception _ false)))
