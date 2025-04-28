(ns sbis-notifications.firefox
  (:require
    [applied-science.js-interop :as j]
    [clojure.string :as string]
    [promesa.core :as p]
    ["fs/promises" :as fs]
    ["ini" :as ini]
    ["process$env" :as env]
    ["sqlite3$default" :as sqlite3]
    ["tmp" :as tmp]))

;; Путь к домашнему каталогу Firefox и файл с профилями.
(def firefox-home (str env.HOME "/.mozilla/firefox"))
(def firefox-profiles (str firefox-home "/profiles.ini"))

;; Запрос для получения негостевой куки.
(def query-sid "
  SELECT * FROM moz_cookies
  WHERE host = ?
  AND name = 'sid'
  AND value NOT LIKE '%-1111111111111111'")

(defn get-cookies-db-path
  "Возвращает путь к базе данных с куками дефолтного профиля Firefox."
  []
  (-> (fs/readFile firefox-profiles "utf-8")
      (p/then (fn [file]
                (ini/parse file)))
      (p/then (fn [config]
                (->> (.keys js/Object config)
                     (filter #(string/starts-with? % "Profile"))
                     (map #(j/get config %))
                     (filter #(= "1" (j/get % "Default")))
                     first)))
      (p/then (fn [profile]
                (str firefox-home "/" (j/get profile "Path") "/cookies.sqlite")))))

(defn db-open
  "Обёртка для Database(), которая возвращает promise."
  [path]
  (p/create (fn [resolve reject]
              (let [db (sqlite3.Database. path)]
                (.on db "open"
                     (fn []
                       (resolve db)))
                (.on db "error"
                     (fn [err]
                       (reject err)))))))

(defn db-run
  "Обёртка для Database().run(), которая возвращает promise."
  [db query]
  (p/create (fn [resolve reject]
              (.run db query (fn [err]
                               (if err
                                 (reject err)
                                 (resolve db)))))))

(defn db-get
  "Обёртка для Database().get(), которая возвращает promise."
  [db query params]
  (p/create (fn [resolve reject]
              (.get db query params (fn [err row]
                                      (if err
                                        (reject err)
                                        (resolve row)))))))

(defn get-value
  "Выполняет запрос к базе данных, путь к которой задан в первом аргументе.
  После выполнения запроса закрывает базу. Возвращает promise."
  [cookie-file query params]
  (let [tmp-file (tmp.tmpNameSync #js {:prefix "tmp" :postfix ".sqlite" :tmpdir "."})]
    (-> (fs/copyFile cookie-file tmp-file)
        (p/then (fn [_]
                    (p/let [db (db-open tmp-file)
                            _ (db-run db "PRAGMA journal_mode = DELETE")
                            r (db-get db query params)]
                      (.close db)
                      (.unlink fs tmp-file)
                      (-> r (js->clj :keywordize-keys true) :value)))))))

(defn get-cookie-sid
  "Получае негостевой sid для указанного хоста."
  [host]
  (p/-> (get-cookies-db-path)
        (get-value query-sid host)))
