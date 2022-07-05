(ns electron-shell.config
  (:require [electron-shell.file-utils :as fu]
            [fs :as fs]))

(defn config
  ([] (config "config.json"))
  ([filename]
   (let [path (fu/pathname filename)
         url (fu/file-url path)]
     (when (fs/existsSync path)
       (-> path
           fs/readFileSync
           js/JSON.parse
           (js->clj :keywordize-keys true)
           (update :processes vec))))))
