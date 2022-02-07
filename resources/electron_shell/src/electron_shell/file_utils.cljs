(ns electron-shell.file-utils
  (:require [electron :refer [app]]
            [utilis.js :as j]
            [path :as path]
            [url :as url]))

(defonce is-debug? (not (j/get app :isPackaged)))

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
