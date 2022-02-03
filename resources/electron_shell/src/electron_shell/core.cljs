(ns electron-shell.core
  (:require [electron :as e :refer [app BrowserWindow globalShortcut Menu dialog]]
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

(defn pathname
  [relative-path]
  (path/join js/__dirname
             (if is-debug?
               relative-path
               (str "../../" relative-path))))

(defn file-url
  [pathname]
  (url/format #js {:pathname pathname
                   :protocol "file:"
                   :slashes true}))

(def splash-index-pathname (pathname "splash/index.html"))
(def splash-index-url (file-url splash-index-pathname))

(def main-index-pathname (pathname "main/index.html"))
(def main-index-url (file-url main-index-pathname))

(def config-pathname (pathname "config.json"))
(def config-url (file-url config-pathname))

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
  []
  (when (fs/existsSync config-pathname)
    (let [{:keys [resources processes]} (-> config-pathname
                                            fs/readFileSync
                                            js/JSON.parse
                                            (js->clj :keywordize-keys true)
                                            (update :processes vec))]
      (doseq [process-index (range (count processes))]
        (try (let [{:keys [name
                           cmd
                           args
                           opts
                           load-from-url] :as process-config} (nth processes process-index)]
               (when cmd
                 (let [cmd (replace-resource-refs resources cmd)
                       args (when (seq args)
                              (map (partial replace-resource-refs resources) args))
                       process (spawn cmd (clj->js args) (clj->js opts))
                       log-prefix (if name
                                    (str "[" name "] ")
                                    (str "[process_" process-index "]"))
                       logger (fn [msg]
                                (fn [data]
                                  (->> data
                                       (str log-prefix msg)
                                       log/info)
                                  data))]
                   (swap! running-processes conj {:process process
                                                  :config process-config})
                   (when (and (string? load-from-url)
                              (not= "auto" load-from-url))
                     (j/call @main-window :loadURL load-from-url))
                   (j/call-in process [:stdout :on] "data"
                              (let [logger (logger "stdout: ")]
                                (if (= "auto" load-from-url)
                                  (comp auto-load-from-url logger)
                                  logger)))
                   (j/call-in process [:stderr :on] "data" (logger "stderr: "))
                   (j/call process :on "close" (logger "process exited with code: ")))))
             (catch js/Error e
               (log/error e)))))))

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
       (when (fs/existsSync main-index-pathname)
         (j/call window :loadURL main-index-url))
       (spawn-processes)
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
  (doseq [{:keys [process process-config]} @running-processes]
    (when (not (j/get process :killed))
      (if-let [sig (:kill-signal process-config)]
        (j/call process :kill sig)
        (j/call process :kill))))
  (reset! running-processes []))

(defn- maybe-quit
  []
  (kill-running-processes)
  (when (or kill-when-empty-on-darwin? (not= js/process.platform "darwin"))
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
