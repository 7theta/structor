(ns structor.available
  (:require [clojure.java.io :as io])
  (:import [java.net URL URLConnection]))

(defn npm?
  []
  (try
    (let [url (URL. "http://npmjs.org")
          connection (doto (.openConnection url)
                       (.connect))]
      (-> connection (.getInputStream) (.close))
      true)
    (catch Exception _ false)))

(defn rn?
  ([] (rn? "shadow-cljs.edn"))
  ([shadow-cljs-edn]
   (boolean
    (when-let [edn (not-empty (slurp shadow-cljs-edn))]
      (let [{:keys [builds]} (read-string edn)]
        (some (fn [[_ {:keys [target]}]]
                (= target :react-native))
              builds))))))

(defn electron?
  []
  (boolean
   (let [file (io/file "electron")]
     (and (.exists file)
          (.isDirectory file)
          (or (.exists (io/file "electron/main/index.html"))
              (.exists (io/file "electron/config.edn")))))))
