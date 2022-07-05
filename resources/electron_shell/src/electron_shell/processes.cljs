(ns electron-shell.processes
  (:require [electron-shell.auto-updater :as auto-updater]
            [electron-shell.file-utils :refer [pathname file-url]]
            [electron-shell.config :refer [config]]
            [electron :as e :refer [app BrowserWindow globalShortcut Menu dialog]]
            [electron-log :as log]
            ["child_process" :refer [spawn]]
            [path :as path]
            [url :as url]
            [fs :as fs]
            [clojure.string :refer [includes?] :as st]
            [utilis.js :as j]
            [utilis.fn :refer [fsafe]]))

(def platform js/process.platform)
(defonce children (atom []))

(declare spawn-processes print-resource-checksums)

(defn spawn-all
  [window {:keys [processes resources]}]
  (js/Promise.
   (fn [resolve reject]
     (try (print-resource-checksums resources)
          (->> processes
               (map-indexed (fn [index process-config]
                              (assoc process-config :index index)))
               (spawn-processes window resources resolve))
          (catch js/Error e
            (reject e))))))

(defn kill-all
  []
  (doseq [{:keys [process config]} (reverse @children)]
    (try (let [{:keys [kill-signal name]} config
               process-name (or name (str config))]
           (log/info (str "Killing " process-name
                          (when kill-signal
                            (str " with signal: " kill-signal))))
           (if kill-signal
             (j/call process :kill kill-signal)
             (j/call process :kill)))
         (catch js/Error e
           (log/info "Error killing process" (str config) e)))))

;;; Private

(defn- auto-load-from-url
  [window data]
  (boolean
   (let [data (str data)]
     (when (includes? data "URL:")
       (let [result (last (re-find #".*URL: \s*([^\n\r]*)" data))]
         (j/call window :loadURL result)
         true)))))

(defn replace-resource-refs
  [resources string]
  (reduce (fn [result resource]
            (st/replace result
                        (re-pattern resource)
                        (pathname (str "extraResources/" resource))))
          string
          resources))

(defn- spawn-process?
  [{:keys [cmd platforms]}]
  (boolean
   (and cmd
        (or (not (seq platforms))
            (get (set (map (comp {:windows "win32"
                               :macos "darwin"}
                              keyword)
                           platforms))
                 platform)))))

(defn- spawn-process*
  [cmd args opts]
  (cond
    (and cmd args opts) (spawn cmd (clj->js args) (clj->js opts))
    (and cmd args) (spawn cmd (clj->js args))
    cmd (spawn cmd)
    :else (throw
           (js/Error.
            (str "Unable to spawn process"
                 {:cmd cmd
                  :args args
                  :opts opts})))))

(defn- logger
  [{:keys [name
           cmd
           args
           opts
           load-from-url
           start-delay-ms
           platforms
           index]
    :as process-config}]
  (let [log-prefix (if name
                     (str "[" name "] ")
                     (str "[process_" index "]"))]
    (fn [msg]
      (fn [data]
        (->> data
             (str log-prefix msg)
             log/info)
        data))))

(defn- spawn-process
  [window resources {:keys [name
                            cmd
                            args
                            opts
                            load-from-url
                            start-delay-ms
                            platforms
                            kill-signal]
                     :as process-config}]
  (js/Promise.
   (fn [resolve reject]
     (if (not (spawn-process? process-config))
       (if start-delay-ms
         (js/setTimeout resolve start-delay-ms)
         (resolve))
       (let [args (when (seq args)
                    (map (partial replace-resource-refs resources) args))
             cmd (replace-resource-refs resources cmd)
             log-handler (logger process-config)]
         ((log-handler "spawning: ")
          (str cmd
               (when args (str " " args))
               (when opts (str " " opts))))
         (let [process (spawn-process* cmd args opts)
               spawn-next-process (fn []
                                    (if start-delay-ms
                                      (js/setTimeout #(resolve process) start-delay-ms)
                                      (resolve process)))
               kill-process #(if kill-signal
                               (j/call process :kill kill-signal)
                               (j/call process :kill))]

           (j/call-in process [:stderr :on] "data" (log-handler "stderr: "))
           (j/call-in process [:stdout :on] "data" (log-handler "stdout: "))
           (j/call process :on "close" (log-handler "process exited with code: "))

           (cond
             (and (string? load-from-url)
                  (not= "auto" load-from-url))
             (do (j/call window :loadURL load-from-url)
                 (j/call process :on "spawn" spawn-next-process))

             (= "auto" load-from-url)
             (let [exit-timeout (atom nil)]
               (reset! exit-timeout
                       (js/setTimeout
                        (fn []
                          (log/info "Killing process, no auto-load URL read...")
                          (kill-process)
                          (spawn-next-process))
                        10000))
               (j/call-in process [:stdout :on] "data"
                          (fn [data]
                            (when (auto-load-from-url window data)
                              ((fsafe js/clearTimeout) @exit-timeout)
                              (reset! exit-timeout nil))
                            (spawn-next-process))))

             :else (j/call process :on "spawn" spawn-next-process))))))))

(defn- spawn-processes
  [window resources on-complete process-configs]
  (if-let [process-config (first process-configs)]
    (let [spawn-next #(spawn-processes window resources on-complete (rest process-configs))]
      (-> window
          (spawn-process resources process-config)
          (j/call :then (fn [process]
                          (when process
                            (swap! children conj
                                   {:process process
                                    :config process-config}))
                          (spawn-next)))
          (j/call :catch (fn [error]
                           (log/info "Error occurred spawning process"
                                     (clj->js process-config)
                                     error)
                           (spawn-next)))))
    (on-complete)))

(defn- print-resource-checksums
  [resources]
  (doseq [resource resources]
    (-> (pathname (str "extraResources/" resource))
        auto-updater/file-checksum
        (j/call :then #(log/info (str % " " resource))))))
