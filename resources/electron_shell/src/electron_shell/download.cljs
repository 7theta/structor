(ns electron-shell.download
  (:require [electron-shell.file-utils :refer [pathname file-url]]
            [electron-log :as log]
            [https :as https]
            [http :as http]
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
       (let [uri (js/encodeURIComponent uri)
             uri (if (j/call uri :startsWith "/")
                   uri
                   (str "/" uri))
             file (fs/createWriteStream file-path)
             file-info (atom nil)
             https? (= protocol "https:")
             request ((if https?
                        https/request
                        http/request) (->> (when (and https? (seq mtls))
                                             (prep-mtls mtls))
                                           (merge {:hostname hostname
                                                   :port port
                                                   :path (str path-prefix uri)
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
                                                  (str path-prefix uri))))
                          (do (reset! file-info {:mime (j/get-in response [:headers :content-type])
                                                 :size (js/parseInt (j/get-in response [:headers :content-length]) 10)})
                              (j/call response :pipe file)))))]

         (j/call file :on "finish" #(resolve @file-info))
         (j/call request :on "error"
                 (fn [error]
                   (fs/unlink file-path (fn [error] (when error (log/info error))))
                   (reject error)))
         (j/call file :on "error"
                 (fn [error]
                   (fs/unlink file-path (fn [error] (when error (log/info error))))
                   (reject error)))
         (j/call request :end))
       (catch js/Error e
         (reject e))))))

(defn download
  [{:keys [hostname
           port
           path-prefix
           protocol
           mtls
           uri]}]
  (js/Promise.
   (fn [resolve reject]
     (let [https? (= protocol "https:")
           uri (if (j/call uri :startsWith "/")
                 uri
                 (str "/" uri))]
       (try (let [request ((if https?
                             https/request
                             http/request) (->> (when (and https? (seq mtls))
                                                  (prep-mtls mtls))
                                                (merge {:hostname hostname
                                                        :port port
                                                        :path (str path-prefix uri)
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
                                                       (str path-prefix uri))))
                               (do (j/call response :on "data" resolve)
                                   (j/call response :on "error" reject)))))]
              (j/call request :on "error" (fn [error] (reject error)))
              (j/call request :end))
            (catch js/Error e
              (reject e)))))))
