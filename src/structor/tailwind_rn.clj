(ns structor.tailwind-rn
  (:require [tailwind.react-native :as tw-rn]
            [shadow.cljs.devtools.server.fs-watch :as fs-watch]
            [crusta.core :as sh]
            [integrant.core :as ig]
            [utilis.map :refer [compact]]
            [clojure.java.io :as io]
            [clojure.string :as st]))

(declare watch stop)

(def default-input-tailwind-css tw-rn/default-input-tailwind-css)
(def default-output-tailwind-css tw-rn/default-output-tailwind-css)

(defmethod ig/init-key :structor.tailwind-rn/watcher
  [_ opts]
  {:watcher (watch opts)})

(defmethod ig/halt-key! :structor.tailwind-rn/watcher
  [_ {:keys [watcher]}]
  (stop watcher))

(defn flesh-out-opts
  [{:keys [tailwind-json tailwind-js tailwind-css] :as opts}]
  {:tailwind-json (or tailwind-json tw-rn/default-tailwind-json)
   :tailwind-js (or tailwind-js tw-rn/default-tailwind-utilities-js)
   :tailwind-css (or tailwind-css tw-rn/default-output-tailwind-css)})

(defn write-dummy-js
  ([] (write-dummy-js nil))
  ([{:keys [tailwind-js]
     :or {tailwind-js tw-rn/default-tailwind-utilities-js}}]
   (spit tailwind-js "export default {}")))

(defn release
  ([] (release nil))
  ([opts]
   (let [{:keys [tailwind-css tailwind-json tailwind-js]} (flesh-out-opts opts)]
     (tw-rn/ensure-directories tailwind-css)
     (tw-rn/ensure-directories tailwind-json)
     (tw-rn/ensure-directories tailwind-js)
     @(sh/run ["npx" "tailwind-rn" "-i" tailwind-css "-o" tailwind-json])
     (tw-rn/write-tailwind-js
      (select-keys opts [:tailwind-json
                         :tailwind-js
                         :tailwind-css])))))

(defn watch
  ([] (watch nil))
  ([{:keys [tailwind-json
            tailwind-js
            tailwind-css
            watch-dir]
     :as opts}]
   (let [{:keys [tailwind-css tailwind-json tailwind-js]} (flesh-out-opts opts)
         tailwind-json-filename (-> tailwind-json (st/split #"/") last)]
     (tw-rn/ensure-directories tailwind-css)
     (tw-rn/ensure-directories tailwind-json)
     (tw-rn/ensure-directories tailwind-js)
     (when (not (.exists (io/file tailwind-js)))
       (spit tailwind-js "export default {};"))
     (try @(sh/run ["npx" "tailwind-rn" "-i" tailwind-css "-o" tailwind-json])
          (catch Exception e))
     (let [watcher (sh/exec ["npx" "tailwind-rn" "-i" tailwind-css "-o" tailwind-json "--watch"])
           watch-dir (or watch-dir
                         (->> (-> tw-rn/default-tailwind-json
                                  (st/split #"/")
                                  drop-last)
                              (st/join "/")))

           json-watcher (fs-watch/start nil
                                        [(io/file watch-dir)]
                                        ["json"]
                                        (fn [changes]
                                          (when (some (fn [{:keys [file name]}]
                                                        (= name tailwind-json-filename))
                                                      changes)
                                            (tw-rn/write-tailwind-js
                                             (select-keys opts [:tailwind-json
                                                                :tailwind-js
                                                                :tailwind-ss])))))]
       {:stop (fn []
                (sh/kill watcher)
                (fs-watch/stop json-watcher))}))))

(defn stop
  [{:keys [stop] :as watcher}]
  (stop))

(defn clean
  ([] (clean nil))
  ([{:keys [tailwind-json tailwind-js]
     :or {tailwind-json tw-rn/default-tailwind-json
          tailwind-js tw-rn/default-tailwind-utilities-js}}]
   @(sh/run ["rm" "-f" tailwind-json])
   @(sh/run ["rm" "-f" tailwind-js])))
