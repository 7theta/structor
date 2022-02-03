(ns structor.electron
  (:refer-clojure :exclude [read-string])
  (:require [crusta.core :as sh]
            [clojure.edn :refer [read-string]]
            [jsonista.core :as j]
            [clojure.java.io :as io]
            [clojure.string :as st]))

(declare copy-resource-files-to-build-directory
         copy-splash-files-to-build-directory
         copy-main-files-to-build-directory
         copy-config-files-to-build-directory
         install-dependencies
         compile-electron-shell-cljs
         release-electron
         copy-out-release-artifacts)

(defn build
  []
  (copy-resource-files-to-build-directory)
  (copy-splash-files-to-build-directory)
  (copy-main-files-to-build-directory)
  (copy-config-files-to-build-directory)
  (install-dependencies)
  (compile-electron-shell-cljs)
  (release-electron)
  (copy-out-release-artifacts))

(def electron-shell-resources
  ["package.json"
   "project.clj"
   "shadow-cljs.edn"
   "src/electron_shell/core.cljs"])

(def build-directory ".electron-shell")

(defn copy-resource-files-to-build-directory
  []
  @(sh/run (format "rm -rf %s" build-directory))
  @(sh/run (format "mkdir -p %s" build-directory))
  (doseq [res electron-shell-resources]
    (when-let [directory (->> (-> res
                                  (st/split #"/")
                                  drop-last)
                              (st/join "/")
                              not-empty)]
      @(sh/run (format "mkdir -p %s/%s" build-directory directory)))
    (->> (format "electron_shell/%s" res)
         io/resource
         slurp
         (spit (format "%s/%s" build-directory res)))))

(defn copy-config-files-to-build-directory
  []
  (let [config-file (io/file "electron/config.edn")]
    (when (.exists config-file)
      (let [config (read-string (slurp config-file))]
        (when (seq (:resources config))
          @(sh/run (format "mkdir -p %s/extraResources" build-directory)))
        (doseq [resource (:resources config)]
          (let [resource (format "electron/%s" resource)
                file (io/file resource)]
            (if (.exists file)
              (if (.isDirectory file)
                @(sh/run (format "cp -r %s %s/extraResources" resource build-directory))
                @(sh/run (format "cp %s %s/extraResources" resource build-directory)))
              (throw (ex-info (format "Resource file does not exist." {:resource resource}))))))
        (->> config
             j/write-value-as-string
             (spit (format "%s/config.json" build-directory)))))))

(defn copy-splash-files-to-build-directory
  []
  (let [splash-dir (io/file "electron/splash")]
    (when (and (.exists splash-dir)
               (.isDirectory splash-dir))
      @(sh/run (format "cp -r electron/splash %s" build-directory)))))

(defn copy-main-files-to-build-directory
  []
  (let [main-dir (io/file "electron/main")]
    (when (and (.exists main-dir)
               (.isDirectory main-dir))
      @(sh/run (format "cp -r electron/main %s" build-directory)))))

(defn install-dependencies
  []
  (println @(sh/run "npm install" :directory build-directory)))

(defn compile-electron-shell-cljs
  []
  (println @(sh/run "npx shadow-cljs release main" :directory build-directory)))

(defn release-electron
  []
  (println @(sh/run "npm run app:dist" :directory build-directory)))

(defn copy-out-release-artifacts
  []
  @(sh/run "rm -rf electron/dist")

  @(sh/run "mkdir -p electron/dist")
  @(sh/run (format "cp -r %s/dist electron/" build-directory)))

;; TODO
;; - get it working with involo
;; - figure out a reasonable build process for windows
;; - add auto-updater code
