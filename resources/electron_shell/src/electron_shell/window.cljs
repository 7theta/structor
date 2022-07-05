(ns electron-shell.window
  (:require [electron-shell.auto-updater :as auto-updater]
            [electron-shell.file-utils :refer [pathname file-url]]
            [electron-shell.processes :as p]
            [electron :as e :refer [app BrowserWindow globalShortcut Menu dialog]]
            [electron-log :as log]
            [path :as path]
            [url :as url]
            [fs :as fs]
            [clojure.string :refer [includes?] :as st]
            [utilis.js :as j]
            [utilis.fn :refer [fsafe]]))

(def platform js/process.platform)

(declare main splash setup-auto-updater register-shortcuts)

(defn create
  [{:keys [hide-menu-bar auto-update] :as config}]
  (when-let [main-window (main)]
    (let [splash-window (splash)
          cleanup-all #(do (log/info "Main window setup timed out. Destroying process and window instances.")
                           ((fsafe j/call) main-window :destroy)
                           ((fsafe j/call) splash-window :destroy)
                           (j/call js/process :exit))
          ready-timeout (atom nil)]
      (try (when (and hide-menu-bar (= "win32" platform))
             (j/call main-window :removeMenu))
           (if auto-update
             (-> (auto-updater/init auto-update)
                 (j/call :then #(p/spawn-all main-window config))
                 (j/call :catch log/info))
             (p/spawn-all main-window config))
           (register-shortcuts main-window)
           (reset! ready-timeout (js/setTimeout cleanup-all 30000))
           (j/call main-window :once "ready-to-show"
                   #(do ((fsafe js/clearTimeout) @ready-timeout)
                        (reset! ready-timeout nil)
                        ((fsafe j/call) splash-window :destroy)
                        (j/call main-window :show)))
           (catch js/Error e
             (log/error e)
             (cleanup-all)))
      main-window)))

;;; Private

(defn main
  ([] (main "main/index.html"))
  ([filename]
   (let [path (pathname filename)
         window (BrowserWindow.
                 (clj->js {:width 1220
                           :height 800
                           :show false
                           :webPreferences {:scrollBounce false}}))]
     (when (fs/existsSync path)
       (j/call window :loadURL (file-url path)))
     window)))

(defn splash
  ([] (splash "splash/index.html"))
  ([filename]
   (let [path (pathname filename)]
     (when (fs/existsSync path)
       (let [splash-window (BrowserWindow.
                            (clj->js
                             {:width 600
                              :height 500
                              :frame false
                              :show true
                              :transparent true
                              :alwaysOnTop true
                              :webPreferences {:scrollBounce false}}))]
         (j/call splash-window :loadURL (file-url path))
         splash-window)))))

(defn- register-shortcuts
  ([window]
   (register-shortcuts
    window
    {"Command+D" #(j/call-in window [:webContents :openDevTools])
     "Control+D" #(j/call-in window [:webContents :openDevTools])
     "Command+P" #(j/call-in window [:webContents :print]
                             #js {:silent false
                                  :printBackground true})
     "Control+P" #(j/call-in window [:webContents :print]
                             #js {:silent false
                                  :printBackground true})}))
  ([window shortcuts]
   (doseq [[event f] (sort-by first shortcuts)]
     (j/call globalShortcut :register event f))))
