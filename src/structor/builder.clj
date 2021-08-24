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
            [crusta.core :as sh]
            [integrant.core :as ig])
  (:import [java.net URL URLConnection]))

(declare watch stop clean npm-available?)

(defmethod ig/init-key :structor.builder/watcher [_ _]
  (watch))

(defmethod ig/halt-key! :structor.builder/watcher [_ watchers]
  (stop watchers))

(defn init
  []
  (println @(sh/run ["npm" "install"]))
  (println @(sh/run ["npx" "browserslist@latest" "--update-db"])))

(defn release
  []
  (when (npm-available?) (init))
  (clean)
  (println (shadow-cljs/release))
  (println (tailwind/release))
  (println @(sh/run ["lein" "uberjar"])))

(defn watch
  []
  (when (npm-available?) (init))
  (clean)
  {:shadow-cljs (shadow-cljs/watch)
   :tailwind (tailwind/watch)})

(defn stop
  [watchers]
  (println (shadow-cljs/stop (:shadow-cljs watchers)))
  (println (tailwind/stop (:tailwind watchers)))
  nil)

(defn clean
  []
  (shadow-cljs/clean)
  (tailwind/clean))


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