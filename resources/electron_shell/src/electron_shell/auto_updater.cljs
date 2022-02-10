(ns electron-shell.auto-updater
  (:require [electron-shell.file-utils :refer [pathname file-url]]
            [electron-shell.download :refer [download download-file-to-disk]]
            [electron :refer [app BrowserWindow dialog autoUpdater]]
            [electron-log :as log]
            [cljs.core.async :refer [go <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [crypto :as crypto]
            [fs :as fs]
            [path :as path]
            [utilis.js :as j]
            [utilis.types.number :refer [string->long]]))

(def default-check-interval-s 3600)
(def updates-directory-path (path/join (j/call app :getPath "temp") "updates"))
(def latest-json-path (str updates-directory-path "/latest.json"))
(def platform js/process.platform)

(defn alert
  [msg]
  (j/call dialog :showErrorBox (str msg) (str msg)))

(defn dissect-url
  [url]
  (let [url (js/URL. url)]
    {:hostname (j/get url :hostname)
     :port (string->long (j/get url :port))
     :path-prefix (j/get url :pathname)
     :protocol (j/get url :protocol)}))

(defn file-checksum
  [path]
  (js/Promise.
   (fn [resolve reject]
     (try (let [input (fs/createReadStream path)
                hash (crypto/createHash "sha256")]
            (j/call input :on "readable"
                    (fn []
                      (try (if-let [data (j/call input :read)]
                             (j/call hash :update data)
                             (resolve (j/call hash :digest "hex")))
                           (catch js/Error e
                             (log/info e)
                             (reject nil))))))
          (catch js/Error e
            (reject e))))))

(defn updated-resource-checksum
  [resource]
  (file-checksum (str updates-directory-path "/" resource)))

(defn installed-resource-checksum
  [resource]
  (->> resource
       (str "extraResources/")
       pathname
       file-checksum))

(defn latest-json-audit
  ([] (latest-json-audit
       (when (fs/existsSync latest-json-path)
         (-> (fs/readFileSync latest-json-path)
             js/JSON.parse
             (js->clj :keywordize-keys true)))))
  ([latest-json]
   (go (try (when latest-json
              (let [resources-atom (atom [])]
                (doseq [{:keys [checksum path] :as resource} (:resources latest-json)]
                  (swap! resources-atom conj
                         (assoc resource
                                :checksums {:remote checksum
                                            :local-installed (<p! (installed-resource-checksum path))
                                            :local-downloaded (<p! (updated-resource-checksum path))})))
                (assoc latest-json :resources (not-empty @resources-atom))))
            (catch js/Error e
              (log/info e)
              nil)))))

(defn check-for-updates
  [{:keys [url] :as auto-update-config}]
  (js/Promise.
   (fn [resolve reject]
     (-> auto-update-config
         (assoc :uri "latest.json")
         download
         (j/call :then (fn [text]
                         (log/info (str "Got latest.json from update server\n" text))
                         (go (try (if-let [to-download (->> (<! (-> text
                                                                    js/JSON.parse
                                                                    (js->clj :keywordize-keys true)
                                                                    latest-json-audit))
                                                            :resources
                                                            (filter (fn [{:keys [path checksums]}]
                                                                      (let [{:keys [remote local-installed local-downloaded]} checksums]
                                                                        (and remote local-installed
                                                                             (not= remote local-installed)
                                                                             (or (not local-downloaded)
                                                                                 (not= local-downloaded remote))))))
                                                            not-empty)]
                                    (let [downloaded (atom [])
                                          check-done (fn []
                                                       (when (= (count @downloaded) (count to-download))
                                                         (if (some :success @downloaded)
                                                           (-> dialog
                                                               (j/call :showMessageBox
                                                                       (clj->js {:type "info"
                                                                                 :buttons ["Restart" "Later"]
                                                                                 :title "Application Update"
                                                                                 :message ""
                                                                                 :detail "A new version has been downloaded. Restart the application to apply the updates."}))
                                                               (j/call :then (fn [return-value]
                                                                               (resolve @downloaded)
                                                                               (when (zero? (j/get return-value :response))
                                                                                 (j/call app :relaunch)
                                                                                 (j/call app :exit 0)))))
                                                           (resolve @downloaded))))]
                                      (fs/writeFileSync (str updates-directory-path "/latest.json") text)
                                      (doseq [{:keys [path] :as resource} to-download]
                                        (log/info (str "Downloading updated resource file: " path))
                                        (-> (assoc auto-update-config
                                                   :uri path
                                                   :file-path (str updates-directory-path "/" path))
                                            download-file-to-disk
                                            (j/call :then (fn []
                                                            (log/info (str "Downloaded updated resource file: " path))
                                                            (swap! downloaded conj {:resource resource
                                                                                    :success true})
                                                            (check-done)))
                                            (j/call :catch (fn [error]
                                                             (log/info error)
                                                             (swap! downloaded conj {:resource resource
                                                                                     :error error})
                                                             (check-done))))))
                                    (do (log/info "No resources to download.")
                                        (resolve nil)))
                                  (catch js/Error e
                                    (log/info e)
                                    (reject e))))))
         (j/call :catch (fn [error]
                          (log/info error)
                          (reject error)))))))

(defn check-for-updates-loop
  [auto-update-config]
  (let [check-interval-s (or (:check-interval-s auto-update-config)
                             default-check-interval-s)
        check-for-updates* (fn check-for-updates* []
                             (log/info "Checking for updates...")
                             (let [schedule-next (fn [] (js/setTimeout check-for-updates* (* 1000 check-interval-s)))]
                               (-> (check-for-updates auto-update-config)
                                   (j/call :then #(schedule-next))
                                   (j/call :catch #(schedule-next)))))]
    (check-for-updates*)))

(defn install-downloaded-updates
  []
  (js/Promise.
   (fn [resolve reject]
     (go (try (let [updates (atom [])
                    to-install (->> (<! (latest-json-audit))
                                    :resources
                                    (filter (fn [{:keys [path checksums] :as resource}]
                                              (let [{:keys [remote local-installed local-downloaded]} checksums]
                                                (and remote local-installed local-downloaded
                                                     (= remote local-downloaded)
                                                     (not= local-downloaded local-installed))))))]
                (doseq [{:keys [path checksums] :as resource} to-install]
                  (let [check-done (fn [result]
                                     (swap! updates conj {:resource resource
                                                          :result result})
                                     (when (= (count @updates)
                                              (count to-install))
                                       (resolve @updates)))]
                    (try (let [downloaded-file (str updates-directory-path "/" path)
                               current-file (->> path (str "extraResources/") pathname)]
                           (log/info (str "Installing new resource file " resource))
                           (fs/copyFile downloaded-file current-file
                                        (fn [error]
                                          (if error
                                            (do (js/console.info error)
                                                (check-done {:error error}))
                                            (check-done {:success true})))))
                         (catch js/Error e
                           (check-done {:error e})))))
                (when (not (seq to-install))
                  (resolve nil)))
              (catch js/Error e
                (reject e)))))))

(defn init
  [auto-update-config]
  (let [{:keys [platforms] :as auto-update-config} (-> (:url auto-update-config)
                                                       dissect-url
                                                       (merge auto-update-config))]
    (js/Promise.
     (fn [resolve reject]
       (if (or (not (seq platforms))
               (get (set (map (comp {:windows "win32"
                                     :macos "darwin"}
                                    keyword)
                              platforms))
                    platform))
         (try (when (not (fs/existsSync updates-directory-path))
                (fs/mkdirSync updates-directory-path))
              (-> (install-downloaded-updates)
                  (j/call :then (fn [installed]
                                  (js/setTimeout #(check-for-updates-loop auto-update-config))
                                  (if (seq installed)
                                    (log/info (str "Installed " installed))
                                    (log/info "No downloaded updates to install."))
                                  (resolve installed)))
                  (j/call :catch (fn [error]
                                   (log/info error)
                                   (resolve nil))))
              (catch js/Error e
                (log/info e)
                (resolve nil)))
         (do (log/warn "AutoUpdater not available on this platform.")
             (resolve nil)))))))
