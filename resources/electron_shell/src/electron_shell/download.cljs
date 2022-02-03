(ns electron-shell.download
  (:require [electron-shell.file-utils :refer [pathname file-url]]
            [electron-log :as log]
            [https :as https]
            [http :as http]
            [js-yaml :as yaml]
            [fs :as fs]
            [path :as path]
            [utilis.js :as j]
            [utilis.map :refer [map-vals]]
            [clojure.set :refer [rename-keys]]))

(defn prep-mtls
  [mtls]
  (->> {:client-cert :cert
        :client-key :key
        :ca-cert :ca}
       (rename-keys mtls)
       (map-vals (comp fs/readFileSync file-url pathname))))

(defn download-file-to-disk
  [{:keys [hostname
           port
           path-prefix
           protocol
           mtls
           uri
           file-path]}]
  (js/Promise.
   (fn [resolve reject]
     (try
       (let [file (fs/createWriteStream file-path)
             file-info (atom nil)
             https? (= protocol "https:")
             request ((if https?
                        https/request
                        http/request) (->> (when (and https? (seq mtls))
                                             (prep-mtls mtls))
                                           (merge {:hostname hostname
                                                   :port port
                                                   :path (str path-prefix (js/encodeURIComponent uri))
                                                   :method "GET"})
                                           clj->js)
                      (fn [response]
                        (if (not= 200 (j/get response :statusCode))
                          (reject (js/Error. (str "Failed to download "
                                                  protocol
                                                  "//"
                                                  hostname
                                                  ":"
                                                  port
                                                  (str path-prefix (js/encodeURIComponent uri)))))
                          (do (reset! file-info {:mime (j/get-in response [:headers :content-type])
                                                 :size (js/parseInt (j/get-in response [:headers :content-length]) 10)})
                              (j/call response :pipe file)))))]

         (j/call file :on "finish" #(resolve @file-info))
         (j/call request :on "error"
                 (fn [error]
                   (fs/unlink file-path)
                   (reject error)))
         (j/call file :on "error"
                 (fn [error]
                   (fs/unlink file-path)
                   (reject error)))
         (j/call request :end))
       (catch js/Error e
         (reject e))))))

(defn download-latest
  [{:keys [hostname
           port
           path-prefix
           protocol
           mtls
           uri]
    :as foo}]
  (js/Promise.
   (fn [resolve reject]
     (let [https? (= protocol "https:")]
       (prn (->> (when (and https? (seq mtls))
                   (prep-mtls mtls))
                 (merge {:hostname hostname
                         :port port
                         :path (str path-prefix "latest.yml")
                         :method "GET"})
                 clj->js))
       (try (-> (->> (when (and https? (seq mtls))
                       (prep-mtls mtls))
                     (merge {:hostname hostname
                             :port port
                             :path (str path-prefix "/latest.yml")
                             :method "GET"})
                     clj->js)
                ((if https?
                   https/request
                   http/request) (fn [response]
                                   (if (not= 200 (j/get response :statusCode))
                                     (reject (js/Error. (str "Failed to download "
                                                             protocol
                                                             "//"
                                                             hostname
                                                             ":"
                                                             port
                                                             (str path-prefix "/latest.yml"))))
                                     (do (j/call response :on "data"
                                                 (fn [data]
                                                   (resolve {:obj (yaml/load data)
                                                             :text data})))
                                         (j/call response :on "error" reject)))))
                (j/call :end))
            (catch js/Error e
              (reject e)))))))
