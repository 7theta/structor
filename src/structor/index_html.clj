(ns structor.index-html
  (:require [clojure.string :as st]))

(defn generate
  [{:keys [output-file input-file]
    :or {output-file "resources/public/index.html"
         input-file "dev-resources/templates/index.html"}}]
  (let [version (last (re-find #"defproject .* \"(.*)\"" (slurp "project.clj")))]
    (-> (slurp input-file)
        (st/replace #"APP_VERSION" version)
        (->> (spit output-file)))))
