;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns structor.shadow-cljs
  (:require [structor.available :refer [rn?]]
            [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.api :as shadow]
            [crusta.core :as sh]
            [integrant.core :as ig]))

(declare watch stop)

(defmethod ig/init-key :structor.shadow-cljs/watcher [_ _]
  {:watcher (watch)})

(defmethod ig/halt-key! :structor.shadow-cljs/watcher [_ {:keys [watcher]}]
  (stop watcher))

(defn release
  []
  (shadow/release :prod))

(defn watch
  []
  (server/start!)
  (shadow/watch :dev))

(defn stop
  [_watcher]
  (server/stop!))

(defn clean
  []
  @(sh/run ["rm" "-rf" "target" "resources/public/js/compiled" ".shadow-cljs"])
  (when rn?
    @(sh/run ["rm" "-rf" "app"])))
