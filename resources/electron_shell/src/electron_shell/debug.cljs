(ns electron-shell.debug
  (:require [electron :refer [app dialog]]))

(defonce is-debug? (not (j/get app :isPackaged)))

(defn alert
  [msg]
  (j/call dialog :showErrorBox (str msg) (str msg)))
