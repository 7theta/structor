(ns electron-shell.auto-updater
  (:require [electron-shell.file-utils :refer [pathname file-url]]
            [electron-shell.download :refer [download download-file-to-disk]]
            [electron :refer [app BrowserWindow dialog autoUpdater]]
            [electron-log :as log]
            [crypto :as crypto]
            [js-yaml :as yaml]
            [fs :as fs]
            [path :as path]
            [express :default express]
            [utilis.js :as j]))

(def default-check-interval-s 3600)
(def updates-directory-path (path/join (j/call app :getPath "temp") "updates"))

(defn alert
  [msg]
  (j/call dialog :showErrorBox (str msg) (str msg)))

(defn start-server
  [directory]
  (let [app (express)]
    (j/call app :use "/update" (j/call express :static directory))
    (j/call app :listen 0 "localhost")))

(defn dissect-url
  [url]
  (let [url (js/URL. url)]
    {:hostname (j/get url :hostname)
     :port (j/get url :port)
     :path-prefix (j/get url :pathname)
     :protocol (j/get url :protocol)}))

(defn file-checksum
  [path]
  (try (let [file-buffer (fs/readFileSync path)
             hash-sum (doto (crypto/createHash "sha256")
                        (j/call :update file-buffer))]
         (j/call hash-sum :digest "hex"))
       (catch js/Error e
         nil)))

(defn electron-builder-auto-updater
  [{:keys [url] :as auto-update-config}]
  (let [auto-updater autoUpdater
        server (atom nil)
        shutdown-server (fn []
                          (try (when-let [server @server]
                                 (j/call server :close))
                               (catch js/Error e
                                 (log/info e)))
                          (reset! server nil))]
    (j/assoc! auto-updater :logger log)
    (j/assoc-in! auto-updater [:logger :transports :file :level] "info")
    (j/call auto-updater :on "error"
            (fn [error]
              (log/info error)
              (shutdown-server)))
    (j/call auto-updater :on "checking-for-update" (fn []))
    (j/call auto-updater :on "update-available" (fn []))
    (j/call auto-updater :on "update-not-available" (fn [] (shutdown-server)))
    (j/call auto-updater :on "download-progress" (fn []))
    (j/call auto-updater :on "update-downloaded"
            (fn [info]
              (shutdown-server)
              (-> dialog
                  (j/call :showMessageBox
                          (clj->js {:type "info"
                                    :buttons ["Restart" "Later"]
                                    :title "Application Update"
                                    :message ""
                                    :detail "A new version has been downloaded. Restart the application to apply the updates."}))
                  (j/call :then (fn [return-value]
                                  (when (zero? (j/get return-value :response))
                                    (j/call auto-updater :quitAndInstall)))))))
    (let [check-for-updates (fn []
                              (-> (download (assoc auto-update-config :uri "latest.yml"))
                                  (j/call :then (fn [text]
                                                  (let [obj (yaml/load text)
                                                        url (j/get-in obj [:files 0 :url])]
                                                    (fs/writeFileSync (str updates-directory-path "/latest.yml") text)
                                                    (download-file-to-disk
                                                     (assoc auto-update-config
                                                            :uri url
                                                            :file-path (str updates-directory-path "/" url))))))
                                  (j/call :then (fn []
                                                  (shutdown-server)
                                                  (reset! server (start-server updates-directory-path))
                                                  (j/call autoUpdater :setFeedURL
                                                          (str "http://localhost:"
                                                               (-> @server
                                                                   (j/call :address)
                                                                   (j/get :port))
                                                               "/update/"))
                                                  (j/call auto-updater :checkForUpdatesAndNotify)))
                                  (j/call :catch (fn [error]
                                                   (shutdown-server)
                                                   (log/info error)))))]
      (check-for-updates))))

(defn auto-update-resources
  [{:keys [url] :as auto-update-config}]
  (let [check-for-updates (fn []
                            (-> (download (assoc auto-update-config :uri "latest.json"))
                                (j/call :then (fn [text]
                                                (log/info (str "Got latest.json from update server\n" text))
                                                (let [{:keys [resources]} (-> text
                                                                              js/JSON.parse
                                                                              (js->clj :keywordize-keys true))]
                                                  (fs/writeFileSync (str updates-directory-path "/latest.json") text)
                                                  (let [to-download (filter (fn [{:keys [path checksum]}]
                                                                              (try (not= (->> path
                                                                                              (str "extraResources/")
                                                                                              pathname
                                                                                              file-checksum)
                                                                                         checksum)
                                                                                   (catch js/Error e
                                                                                     (log/info e)
                                                                                     false)))
                                                                            resources)
                                                        downloaded (atom 0)]
                                                    (doseq [{:keys [path checksum]} to-download]
                                                      (log/info (str "Downloading updated resource file: " path))
                                                      (-> (assoc auto-update-config
                                                                 :uri path
                                                                 :file-path (str updates-directory-path "/" path))
                                                          download-file-to-disk
                                                          (j/call :then (fn []
                                                                          (log/info (str "Downloaded updated resource file: " path))
                                                                          (when (= (swap! downloaded inc) (count to-download))
                                                                            (-> dialog
                                                                                (j/call :showMessageBox
                                                                                        (clj->js {:type "info"
                                                                                                  :buttons ["Restart" "Later"]
                                                                                                  :title "Application Update"
                                                                                                  :message ""
                                                                                                  :detail "A new version has been downloaded. Restart the application to apply the updates."}))
                                                                                (j/call :then (fn [return-value]
                                                                                                (when (zero? (j/get return-value :response))
                                                                                                  (j/call app :relaunch)
                                                                                                  (j/call app :exit 0))))))))))))))
                                (j/call :catch (fn [error] (log/info error)))))]
    (check-for-updates)))

(defn check-for-updates-timer
  [auto-update-config]
  (let [check-interval-s (or (:check-interval-s auto-update-config)
                             default-check-interval-s)
        check-for-updates (fn check-for-updates []
                            (log/info "Checking for updates...")
                            (-> (dissect-url (:url auto-update-config))
                                (merge auto-update-config)
                                auto-update-resources)
                            (js/setTimeout check-for-updates (* 1000 check-interval-s)))]
    (check-for-updates)))

(defn install-downloaded-updates
  []
  (js/Promise.
   (fn [resolve reject]
     (let [latest-json-path (str updates-directory-path "/latest.json")]
       (if (fs/existsSync latest-json-path)
         (let [to-install (->> (-> (fs/readFileSync latest-json-path)
                                   js/JSON.parse
                                   (js->clj :keywordize-keys true)
                                   :resources)
                               (filter (fn [{:keys [path checksum]}]
                                         (try (not= (->> path
                                                         (str "extraResources/")
                                                         pathname
                                                         file-checksum)
                                                    checksum)
                                              (catch js/Error e
                                                (log/info e)
                                                false)))))
               installed (atom 0)]
           (doseq [{:keys [path checksum]} to-install]
             (let [downloaded-file (str updates-directory-path "/" path)
                   current-file (->> path (str "extraResources/") pathname)
                   local-checksum (file-checksum downloaded-file)
                   check-done (fn []
                                (fs/unlink downloaded-file (fn [error] (when error (log/info error))))
                                (swap! installed inc)
                                (when (= @installed (count to-install))
                                  (resolve true)))]
               (if (= checksum local-checksum)
                 (do (log/info (str "Installing new resource file " path))
                     (fs/copyFile downloaded-file current-file
                                  (fn [error]
                                    (when error
                                      (js/console.info error))
                                    (check-done))))
                 (do (log/info (str "Checksum for resource file '" path "' ("
                                    local-checksum
                                    ") does not match checksum in update config ("
                                    checksum
                                    ")"))
                     (check-done)))))
           (fs/unlink latest-json-path (fn [error] (when error (log/info error)))))
         (resolve false))))))

(defn init
  [auto-update-config]
  (js/Promise.
   (fn [resolve reject]
     (if true #_(= "win32" js/process.platform)
         (try
           (when (not (fs/existsSync updates-directory-path))
             (fs/mkdirSync updates-directory-path))
           (-> (install-downloaded-updates)
               (j/call :then (fn [installed?]
                               (js/setTimeout #(check-for-updates-timer auto-update-config) 5000)
                               (resolve installed?))))
           (catch js/Error e
             (log/info e)
             (resolve false)))
         (do (log/warn "AutoUpdater not available on this platform.")
             (resolve false))))))
