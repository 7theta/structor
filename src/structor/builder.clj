;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns structor.builder
  (:refer-clojure :exclude [read-string])
  (:require [structor.shadow-cljs :as shadow-cljs]
            [structor.tailwind :as tailwind]
            [structor.tailwind-rn :as tailwind-rn]
            [structor.electron :as electron]
            [structor.available :refer [rn? npm? electron?]]
            [clojure.edn :refer [read-string]]
            [crusta.core :as sh]
            [utilis.fn :refer [fsafe]]
            [integrant.core :as ig]
            [clojure.java.io :as io]))

(declare watch stop clean)

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
  ([{:keys [hooks electron tailwind tailwind-rn]}]
   (clean)
   (when (npm?) (init))
   ((fsafe (:init hooks)))
   (when (rn?)
     (println (tailwind-rn/write-dummy-js)))
   (println (shadow-cljs/release))
   (println (tailwind/release
             (merge (when (rn?)
                      {:input-file tailwind-rn/default-input-tailwind-css
                       :output-file tailwind-rn/default-output-tailwind-css})
                    tailwind)))
   (when (rn?)
     (println (tailwind-rn/release tailwind-rn))
     (println "Rebuilding with tailwind-rn utilities...")
     (println (shadow-cljs/release)))
   (when (not (rn?))
     (println @(sh/run ["lein" "uberjar"]))
     ((fsafe (:uberjar hooks))))
   (when (and electron (electron?))
     (electron/release))))

(defn watch
  ([] (watch nil))
  ([{:keys [hooks tailwind tailwind-rn]}]
   (clean)
   (when (npm?) (init))
   ((fsafe (:init hooks)))
   (let [tailwind-watcher (tailwind/watch
                           (merge (when (rn?)
                                    {:input-file tailwind-rn/default-input-tailwind-css
                                     :output-file tailwind-rn/default-output-tailwind-css})
                                  tailwind))
         rn-watcher (when (rn?)
                      (tailwind-rn/watch tailwind-rn))]
     (merge {:shadow-cljs (shadow-cljs/watch)
             :tailwind tailwind-watcher}
            (when rn-watcher {:tailwind-rn rn-watcher})))))

(defn stop
  [watchers]
  (println (shadow-cljs/stop (:shadow-cljs watchers)))
  (println (tailwind/stop (:tailwind watchers)))
  (when-let [tailwind-rn (:tailwind-rn watchers)]
    (println (tailwind-rn/stop tailwind-rn)))
  nil)

(defn clean
  []
  (shadow-cljs/clean)
  (if (rn?)
    (tailwind/clean {:output-file tailwind-rn/default-output-tailwind-css})
    (tailwind/clean))
  (tailwind-rn/clean)
  (electron/clean))
