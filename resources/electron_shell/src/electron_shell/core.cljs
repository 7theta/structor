(ns electron-shell.core
  (:require [electron-shell.auto-updater :as auto-updater]
            [electron-shell.file-utils :refer [pathname file-url]]
            [electron :as e :refer [app BrowserWindow globalShortcut Menu dialog]]
            [electron-log :as log]
            ["child_process" :refer [spawn]]
            [path :as path]
            [url :as url]
            [fs :as fs]
            [clojure.string :refer [includes?] :as st]
            [utilis.js :as j]))

(defonce running-processes (atom []))
(defonce main-window (atom nil))
(defonce is-debug? (not (j/get app :isPackaged)))
(defonce kill-when-empty-on-darwin? true)
(def platform js/process.platform)

(def splash-index-pathname (pathname "splash/index.html"))
(def splash-index-url (file-url splash-index-pathname))

(def main-index-pathname (pathname "main/index.html"))
(def main-index-url (file-url main-index-pathname))

(def config-pathname (pathname "config.json"))
(def config-url (file-url config-pathname))
(def config (when (fs/existsSync config-pathname)
              (-> config-pathname
                  fs/readFileSync
                  js/JSON.parse
                  (js->clj :keywordize-keys true)
                  (update :processes vec))))

(defn alert
  [msg]
  (j/call dialog :showErrorBox (str msg) (str msg)))

(defn- auto-load-from-url
  [data]
  (let [data (str data)]
    (when (includes? data "URL:")
      (let [result (last (re-find #".*URL: \s*([^\n\r]*)" data))]
        (j/call @main-window :loadURL result)))))

(defn replace-resource-refs
  [resources string]
  (reduce (fn [result resource]
            (st/replace result
                        (re-pattern resource)
                        (pathname (str "extraResources/" resource))))
          string
          resources))

(defn- spawn-processes
  [{:keys [on-ready]}]
  (when config
    (let [{:keys [resources processes]} config
          spawn-process (fn spawn-process [process-index]
                          (if (< process-index (count processes))
                            (try (let [{:keys [name
                                               cmd
                                               args
                                               opts
                                               load-from-url
                                               platforms]
                                        :as process-config} (nth processes process-index)]
                                   (if (and cmd (or (not (seq platforms))
                                                    (get (set (map (comp {:windows "win32"
                                                                       :macos "darwin"}
                                                                      keyword)
                                                                   platforms))
                                                         platform)))
                                     (let [cmd (replace-resource-refs resources cmd)
                                           args (when (seq args)
                                                  (map (partial replace-resource-refs resources) args))
                                           _ (log/info (str "spawning process: "
                                                            cmd
                                                            (when args (str " " args))
                                                            (when opts (str " " opts))))
                                           process (cond
                                                     (and cmd args opts) (spawn cmd (clj->js args) (clj->js opts))
                                                     (and cmd args) (spawn cmd (clj->js args))
                                                     cmd (spawn cmd)
                                                     :else (throw (js/Error. (str "Unable to spawn process"
                                                                                  (clj->js process-config)))))
                                           log-prefix (if name
                                                        (str "[" name "] ")
                                                        (str "[process_" process-index "]"))
                                           logger (fn [msg]
                                                    (fn [data]
                                                      (->> data
                                                           (str log-prefix msg)
                                                           log/info)
                                                      data))
                                           on-process-spawned (fn [] (spawn-process (inc process-index)))]
                                       (swap! running-processes conj {:process process
                                                                      :config process-config})
                                       (when (and (string? load-from-url)
                                                  (not= "auto" load-from-url))
                                         (j/call @main-window :loadURL load-from-url))
                                       (j/call-in process [:stdout :on] "data"
                                                  (let [logger (logger "stdout: ")]
                                                    (if (= "auto" load-from-url)
                                                      (comp #(on-process-spawned)
                                                         auto-load-from-url
                                                         logger)
                                                      logger)))
                                       (j/call-in process [:stderr :on] "data" (logger "stderr: "))
                                       (j/call process :on "close" (logger "process exited with code: "))
                                       (when (not= "auto" load-from-url)
                                         (j/call process :on "spawn" on-process-spawned)))
                                     (spawn-process (inc process-index))))
                                 (catch js/Error e
                                   (log/info e)))
                            (js/setTimeout on-ready)))]
      (spawn-process 0))))

(defn create-splash-window
  []
  (when (fs/existsSync splash-index-pathname)
    (let [splash-window (BrowserWindow. (clj->js {:width 600
                                                  :height 500
                                                  :frame false
                                                  :show true
                                                  :transparent true
                                                  :alwaysOnTop true
                                                  :webPreferences {:scrollBounce false}}))]
      (j/call splash-window :loadURL splash-index-url)
      splash-window)))

(defn- create-window
  []
  (js/setTimeout
   (fn []
     (let [window (->> (clj->js {:width 1220
                                 :height 800
                                 :show false
                                 :webPreferences {:scrollBounce false}})
                       (BrowserWindow.)
                       (reset! main-window))
           splash-window (create-splash-window)]
       (when (and (:hide-menu-bar config)
                  (= "win32" platform))
         (j/call window :removeMenu))
       (when (fs/existsSync main-index-pathname)
         (j/call window :loadURL main-index-url))
       (if-let [auto-update (:auto-update config)]
         (-> (auto-updater/init auto-update)
             (j/call :then (fn [installed?]
                             (spawn-processes {:on-ready (fn [] )})))))
       (j/call window :once "ready-to-show"
               (fn []
                 (when splash-window
                   (j/call splash-window :destroy))
                 (j/call window :show)))
       (j/call globalShortcut :register "Command+D"
               (fn []
                 (j/call-in window [:webContents :openDevTools])))
       (j/call globalShortcut :register "Control+D"
               (fn []
                 (j/call-in window [:webContents :openDevTools])))
       (j/call globalShortcut :register "Command+P"
               (fn []
                 (j/call-in window [:webContents :print] #js {:silent false
                                                              :printBackground true})))
       (j/call globalShortcut :register "Control+P"
               (fn []
                 (j/call-in window [:webContents :print] #js {:silent false
                                                              :printBackground true})))))))

(defn- maybe-create-window
  []
  (when (nil? @main-window)
    (create-window)))

(defn- kill-if-present
  [process-ref kill?]
  (when-let [process @process-ref]
    (reset! process-ref nil)
    (if kill?
      (j/call process :kill "SIGKILL")
      (j/call process :kill))))

(defn kill-running-processes
  []
  (log/info "Killing spawned processes.")
  (doseq [{:keys [process process-config]} @running-processes]
    (try (when (not (j/get process :killed))
           (log/info (str "Killing "
                          (or (:name process-config)
                              (str process-config))))
           (if-let [sig (:kill-signal process-config)]
             (j/call process :kill sig)
             (j/call process :kill)))
         (catch js/Error e
           (log/info e))))
  (reset! running-processes []))

(defn- maybe-quit
  []
  (kill-running-processes)
  (when (or kill-when-empty-on-darwin? (not= platform "darwin"))
    (j/call app :quit)))

(defn- exit-cleanly
  []
  (j/call js/process :exit))

(defn ^:export main
  [& args]
  (if (j/call app :requestSingleInstanceLock)
    (do (-> app
            (j/call :whenReady)
            (j/call :then (fn []
                            (create-window)
                            (j/call app :on "activate" maybe-create-window))))
        (j/call app :on "window-all-closed" maybe-quit)
        (j/call js/process :on "exit" kill-running-processes)
        (j/call js/process :on "SIGINT" exit-cleanly)
        (j/call js/process :on "SIGTERM" exit-cleanly))
    (j/call app :quit)))
