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
            [structor.index-html :as index-html]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.errors :as e]
            [crusta.core :as sh]
            [integrant.core :as ig]))

(declare watch stop)

(defmethod ig/init-key :structor.shadow-cljs/watcher [_ opts]
  {:watcher (watch opts)})

(defmethod ig/halt-key! :structor.shadow-cljs/watcher [_ {:keys [watcher]}]
  (stop watcher))

(defn release
  ([] (release nil))
  ([{:keys [build-id build-config index-html]
     :or {build-id :prod}
     :as opts}]
   (when (not (rn?))
     (index-html/generate index-html))
   (if build-config
     (do (shadow/with-runtime
           (shadow/release* build-config {}))
         :done)
     (shadow/release build-id))))

(defn watch
  ([] (watch nil))
  ([{:keys [build-id build-config index-html]
     :or {build-id :dev}
     :as opts}]
   (when (not (rn?))
     (index-html/generate index-html))
   (server/start!)
   (if build-config
     (do (shadow/watch* build-config {})
         :watching)
     (shadow/watch build-id))))

(defn stop
  [_watcher]
  (server/stop!))

(defn clean
  []
  @(sh/run (vec
            (concat
             ["rm" "-rf" "target" ".shadow-cljs"]
             (when rn? ["app"])
             (when (not rn?)
               ["resources/public/js/compiled"
                "resources/public/index.html"]))))
  (when rn?
    @(sh/run ["rm" "-rf" "app"])))
