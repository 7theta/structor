(ns electron-shell.core
  (:require [electron-shell.auto-updater :as auto-updater]
            [electron-shell.file-utils :refer [pathname file-url]]
            [electron-shell.config :refer [config]]
            [electron-shell.processes :as p]
            [electron-shell.window :as w]
            [electron :as e :refer [app BrowserWindow globalShortcut Menu dialog]]
            [electron-log :as log]
            [path :as path]
            [url :as url]
            [fs :as fs]
            [clojure.string :refer [includes?] :as st]
            [utilis.js :as j]
            [utilis.fn :refer [fsafe]]))

;;; Declarations

(defonce main-window (atom nil))

(declare setup-handlers)

(defn ^:export main
  [& args]
  (if (j/call app :requestSingleInstanceLock)
    (setup-handlers)
    (j/call app :quit)))

;;; Private

(defn- handle-second-instance
  "https://www.electronjs.org/docs/latest/api/app#event-second-instance"
  []
  (when-let [window @main-window]
    (when (j/call window :isMinimized)
      (j/call window :restore))
    (j/call window :focus)))

(defn- handle-certificate-error
  "https://www.electronjs.org/docs/latest/api/app#event-certificate-error"
  [event web-contents url error certificate callback]
  (if (and (= (st/lower-case (str error))
              (st/lower-case "net::ERR_CERT_AUTHORITY_INVALID"))
           (re-find #"^https://localhost" url))
    (do (j/call event :preventDefault)
        (callback true))
    (callback false)))

(defn- exit-process
  []
  (j/call js/process :exit))

(defn- quit-app
  []
  (j/call app :quit))

(defn- safely-create-main-window!
  [config]
  (when (not @main-window)
    (when (not (reset! main-window (w/create config)))
      (log/info "Unable to create main window.")
      (quit-app))))

(defn- setup-handlers
  []
  (j/call app :on "certificate-error" handle-certificate-error)
  (j/call app :on "second-instance" handle-second-instance)
  (j/call app :on "will-quit" #(j/call globalShortcut :unregisterAll))
  (j/call app :on "before-quit" #((fsafe j/call) @main-window :removeAllListeners "close"))
  (j/call app :on "window-all-closed" quit-app)
  (j/call js/process :on "exit" #(p/kill-all))
  (j/call js/process :on "SIGINT" exit-process)
  (j/call js/process :on "SIGTERM" exit-process)
  (j/call app :on "ready"
          (fn []
            (let [cfg (config)]
              (js/setTimeout #(safely-create-main-window! cfg))
              (j/call app :on "activate"
                      #(safely-create-main-window! cfg))))))
