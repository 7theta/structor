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
         rm cp mkdir)

(def electron-shell-resources
  ["package.json"
   "shadow-cljs.edn"
   "src/electron_shell/auto_updater.cljs"
   "src/electron_shell/config.cljs"
   "src/electron_shell/core.cljs"
   "src/electron_shell/debug.cljs"
   "src/electron_shell/download.cljs"
   "src/electron_shell/file_utils.cljs"
   "src/electron_shell/processes.cljs"
   "src/electron_shell/window.cljs"])

(def default-build-directory ".electron-shell")

(defn clean
  ([] (clean default-build-directory))
  ([build-directory]
   (when (.exists (io/file build-directory))
     (rm build-directory :directory true))
   (when (.exists (io/file "electron/dist"))
     (rm "electron/dist" :directory true))))

(defn bundle
  ([] (bundle default-build-directory))
  ([build-directory]
   (copy-resource-files-to-build-directory build-directory)
   (copy-splash-files-to-build-directory build-directory)
   (copy-main-files-to-build-directory build-directory)
   (copy-config-files-to-build-directory build-directory)))

(defn build
  ([] (build default-build-directory))
  ([build-directory]
   (println @(sh/run "npm install" :directory build-directory))
   (println @(sh/run "npx shadow-cljs release main" :directory build-directory))
   (println @(sh/run "npm run release" :directory build-directory))))

(defn copy-out-release-artifacts
  ([] (copy-out-release-artifacts default-build-directory))
  ([build-directory]
   (mkdir "electron/dist")
   (cp (format "%s/dist" build-directory) "electron/" :directory true)))

(defn release
  ([] (release default-build-directory))
  ([build-directory]
   (clean build-directory)
   (bundle build-directory)
   (build build-directory)
   (copy-out-release-artifacts build-directory)))

;;; Implementation

(def pretty-object-mapper (j/object-mapper {:pretty true}))

(defn config-file
  []
  (let [config-file (io/file "electron/config.edn")]
    (when (.exists config-file)
      (read-string (slurp config-file)))))

(defn update-package-field
  ([package-json-string config-kp package-kp]
   (update-package-field package-json-string config-kp package-kp identity))
  ([package-json-string config-kp package-kp tx-value]
   (if-let [value (if (sequential? config-kp)
                    (get-in (config-file) config-kp)
                    (get (config-file) config-kp))]
     (let [value (tx-value value)]
       (cond-> (j/read-value package-json-string)
         (sequential? package-kp) (assoc-in package-kp value)
         (not (sequential? package-kp)) (assoc package-kp value)
         true (j/write-value-as-string pretty-object-mapper)))
     package-json-string)))

(defn maybe-set-package-field
  [package-json-string package-kp value]
  (when value
    (cond-> (j/read-value package-json-string)
      (sequential? package-kp) (assoc-in package-kp value)
      (not (sequential? package-kp)) (assoc package-kp value)
      true (j/write-value-as-string pretty-object-mapper))))

(defn project-version
  []
  (try (-> (slurp "project.clj")
           read-string
           (nth 2))
       (catch Exception e
         nil)))

(defn copy-resource-files-to-build-directory
  [build-directory]
  (mkdir build-directory)
  (doseq [res electron-shell-resources]
    (when-let [directory (->> (-> res
                                  (st/split #"/")
                                  drop-last)
                              (st/join "/")
                              not-empty)]
      (mkdir (format "%s/%s" build-directory directory)))
    (let [contents (cond-> (->> (format "electron_shell/%s" res)
                                io/resource
                                slurp)
                     (= res "package.json") (-> (update-package-field :name "name")
                                                (update-package-field :author "author")
                                                (update-package-field :description "description")
                                                (update-package-field :app-id "appId")
                                                (update-package-field :artifact-name "artifactName")
                                                (update-package-field :app-icon ["build" "win" "icon"]
                                                                      (partial str "build/"))
                                                (update-package-field :app-icon ["build" "mac" "icon"]
                                                                      (partial str "build/"))
                                                (maybe-set-package-field "version" (project-version))))]
      (spit (format "%s/%s" build-directory res) contents)))
  (when-let [app-icon (:app-icon (config-file))]
    (mkdir (format "%s/build" build-directory))
    (cp (format "electron/%s" app-icon)
        (format "%s/build" build-directory))))

(defn copy-config-files-to-build-directory
  [build-directory]
  (when-let [config (config-file)]
    (when (seq (:resources config))
      (mkdir (format "%s/extraResources" build-directory)))
    (doseq [resource (:resources config)]
      (let [resource (format "electron/%s" resource)
            file (io/file resource)]
        (if (.exists file)
          (cp resource (format "%s/extraResources" build-directory)
              :directory (.isDirectory file))
          (throw (ex-info (str "Resource file does not exist (" (str resource) ")")
                          {:resource resource})))))
    (->> pretty-object-mapper
         (j/write-value-as-string config)
         (spit (format "%s/config.json" build-directory)))))

(defn copy-splash-files-to-build-directory
  [build-directory]
  (let [splash-dir (io/file "electron/splash")]
    (when (and (.exists splash-dir)
               (.isDirectory splash-dir))
      (cp "electron/splash" build-directory :directory true))))

(defn copy-main-files-to-build-directory
  [build-directory]
  (let [main-dir (io/file "electron/main")]
    (when (and (.exists main-dir)
               (.isDirectory main-dir))
      (cp "electron/main" build-directory :directory true))))

(defn rm
  [path & {:keys [directory]}]
  (if directory
    @(sh/run (format "rm -rf %s" path))
    @(sh/run (format "rm %s" path))))

(defn mkdir
  [path]
  @(sh/run (format "mkdir -p %s" path)))

(defn cp
  [from to & {:keys [directory]}]
  (if directory
    @(sh/run (format "cp -r %s %s" from to))
    @(sh/run (format "cp %s %s" from to))))
