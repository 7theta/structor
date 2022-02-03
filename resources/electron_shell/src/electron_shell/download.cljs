(ns electron-shell.download
  (:require [https :as https]
            [js-yaml :as yaml]
            [fs :as fs]
            [path :as path]
            [utilis.js :as j]))

(def hostname "192.168.18.6")
(def port 8044)
(def path-prefix "/update/win")

(defn download-file-to-disk
  [relative-path file-path]
  (js/Promise.
   (fn [resolve reject]
     (try
       (let [file (fs/createWriteStream file-path)
             file-info (atom nil)
             request (https/request
                      (clj->js {:hostname hostname
                                :port port
                                :path (str path-prefix (js/encodeURIComponent relative-path))
                                :method "GET"
                                :cert (fs/readFileSync "certs/client.crt")
                                :key (fs/readFileSync "certs/client.key")
                                :ca (fs/readFileSync "certs/root.crt")})
                      (fn [response]
                        (if (not= 200 (j/get response :statusCode))
                          (reject (js/Error. (str "Failed to download https://"
                                                  hostname
                                                  ":"
                                                  port
                                                  "/"
                                                  (str path-prefix (js/encodeURIComponent relative-path)))))
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
       (catch js/Erorr e
         (reject e))))))

(defn download-latest
  []
  (js/Promise.
   (fn [resolve reject]
     (try
       (-> (https/request
            (clj->js {:hostname hostname
                      :port port
                      :path (str path-prefix "latest.yml")
                      :method "GET"
                      :cert (fs/readFileSync "certs/client.crt")
                      :key (fs/readFileSync "certs/client.key")
                      :ca (fs/readFileSync "certs/root.crt")})
            (fn [response]
              (if (not= 200 (j/get response :statusCode))
                (reject (js/Error. (str "Failed to download https://"
                                        hostname
                                        ":"
                                        port
                                        "/"
                                        (str path-prefix "latest.yml"))))
                (do (j/call response :on "data"
                            (fn [data]
                              (resolve {:obj (yaml/load data)
                                        :text data})))
                    (j/call response :on "error" reject)))))
           (j/call :end))
       (catch js/Erorr e
         (reject e))))))
