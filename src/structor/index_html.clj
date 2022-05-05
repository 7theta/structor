(ns structor.index-html
  (:require [clojure.string :as st]))

(defn generate
  []
  (let [version (last (re-find #"defproject .* \"(.*)\"" (slurp "project.clj")))]
    (-> (slurp "dev-resources/templates/index.html")
        (st/replace #"APP_VERSION" version)
        (->> (spit "resources/public/index.html")))))
