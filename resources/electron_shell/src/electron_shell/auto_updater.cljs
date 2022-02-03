(ns electron-shell.auto-updater
  (:require [electron-shell.download :refer [download-latest download-file-to-disk]]
            [electron :refer [app BrowserWindow dialog autoUpdater]]
            [electron-log :as log]
            [fs :as fs]
            [path :as path]
            [express :default express]
            [utilis.js :as j]))

(defn alert
  [msg]
  (j/call dialog :showErrorBox (str msg) (str msg)))

(defn start-server
  [directory]
  (let [app (express)]
    (j/call app :use "/update" (j/call express :static directory))
    (j/call app :listen 0 "localhost")))

(defn init
  [{:keys [url]}]
  (if (= "win32" js/process.platform)
    (try
      (let [auto-updater autoUpdater
            server (atom nil)
            shutdown-server (fn []
                              (try (when-let [server @server]
                                     (j/call server :close))
                                   (catch js/Error e
                                     (log/error e)))
                              (reset! server nil))]
        (j/assoc! auto-updater :logger log)
        (j/assoc-in! auto-updater [:logger :transports :file :level] "info")
        (j/call auto-updater :on "error"
                (fn [error]
                  (log/error error)
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
        (let [temp-path (path/join (j/call app :getPath "temp") "updates")]
          (when (not (fs/existsSync temp-path))
            (fs/mkdirSync temp-path))
          (-> (download-latest)
              (j/call :then (fn [{:keys [obj text]}]
                              (fs/writeFileSync (str temp-path "/latest.yml") text)
                              (let [url (j/get-in obj [:files 0 :url])]
                                (download-file-to-disk url (str temp-path "/" url)))))
              (j/call :then (fn []
                              (shutdown-server)
                              (reset! server (start-server temp-path))
                              (j/call autoUpdater :setFeedURL
                                      (str "http://localhost:"
                                           (-> @server
                                               (j/call :address)
                                               (j/get :port))
                                           "/update/"))
                              (j/call auto-updater :checkForUpdatesAndNotify)))
              (j/call :catch (fn [error]
                               (shutdown-server)
                               (log/error error))))))
      (catch js/Error e
        (log/error e)))
    (log/warn "AutoUpdater not available on this platform.")))
