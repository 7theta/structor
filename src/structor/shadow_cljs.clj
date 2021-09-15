;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns structor.shadow-cljs
  (:require [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.api :as shadow]
            [crusta.core :as sh]
            [integrant.core :as ig]))

(declare watch stop)

(defmethod ig/init-key :structor.shadow-cljs/watcher [_ opts]
  {:watcher (watch opts)})

(defmethod ig/halt-key! :structor.shadow-cljs/watcher [_ {:keys [watcher]}]
  (stop watcher))

(defn release
  [{:keys [build-ids]}]
  (doseq [build-id build-ids]
    (shadow/release build-id)))

(defn watch
  [{:keys [build-ids]}]
  (server/start!)
  (doseq [build-id build-ids]
    (shadow/watch build-id)))

(defn stop
  [watcher]
  (server/stop!))

(defn clean
  [{:keys [paths]}]
  @(sh/run (into ["rm" "-rf" "target" "resources/public/js/compiled" ".shadow-cljs"] paths)))
