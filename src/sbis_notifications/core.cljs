(ns sbis-notifications.core
  (:require [clojure.string :as string]
            [promesa.core :as p]
            [goog.string :as gstr]
            ["websocket$default" :refer [client]]
            ["node-fetch$default" :as fetch]
            ["crypto" :refer [randomUUID]]
            ["child_process" :refer [spawn]]
            [sbis-notifications.firefox :refer [get-cookie-sid]]))

(def stand "")
(def keepalive 60000)

(defn host
  "Возвращает хост для указанного поддомена в зависимости от заданного в
  глобальное переменной стэнда."
  [subdomain]
  (str (if (empty? stand) ""
         (str stand "-"))
       subdomain ".sbis.ru"))

(defn now
  "Возвращает текущее время."
  []
  (.toISOString (js/Date.)))

(defn record->hashmap
  "Преобразует запись в формате СБИС в хешмап."
  [record]
  (zipmap (map (comp keyword :n) (:s record)) (:d record)))

(defn send-desktop-notification
  "Отправляет уведомление на рабочий стол."
  [text]
  (spawn "notify-send" (clj->js [text])))

(defn stomp-connect
  "Возвращает сообщение для начального подключения к stomp."
  []
  (string/join "\n"
            ["CONNECT"
             "login:stomp_user"
             "passcode:stomp_user"
             "accept-version:1.2,1.1,1.0"
             (str "heart-beat:" keepalive "," keepalive)
             ""
             (char 0)]))

(defn stomp-subscribe
  "Возвращает сообщение для подписки на событие."
  [id device-hash user-hash]
  (string/join "\n"  
            ["SUBSCRIBE"
             (str "id:" id)
             (str "x-queue-name:ver2-" device-hash "-" user-hash)
             "routing_ver:2"
             (str "destination:/exchange/" user-hash)
             ""
             (char 0)]))

(def stomp-keepalive "\n")

(defn handler-failed
  "Обрабатывает ошибку подключения к серверу."
  [error]
  (println (now) "// Could not connect to server.\n" (.toString error)))

(defn handler-error
  "Обрабатывает ошибку подключения."
  [error]
  (println (now) "// Error occured.\n" (.toString error)))

(defn handler-close
  "Обрабатывает закрытие подключения."
  []
  (fn [] (println (now) "// Connection closed.")))

(defn parse-message-header
  "Преобразует строки заголовка в словарь."
  [lines]
  (->> lines
       (map (fn [line]
              ((juxt (comp keyword first)
                     second)
               (string/split line #":"))))
       (into {})))

(defn parse-message
  "Парсит сообщение, полученное через вебсокет. Возвращает список из трех
  элементов: статуса, заголовков (в виде словаря) и тела запроса (тоже в
  виде словаря - если удалось распарсить JSON)."
  [text]
  ((juxt 
     (comp first
           string/split-lines
           first)
     (comp parse-message-header
           rest
           string/split-lines
           first)
     (comp #(js->clj % :keywordize-keys true)
           #(try
              (.parse js/JSON %)
              (catch js/SyntaxError _ {}))
           second))
   (string/split text #"\n\n")))

(defn parse-notice
  [data]
  (-> data record->hashmap :notice record->hashmap :in_page_data))

(defn send-keepalive
  "Запускет цикл отправки keepalive сообщений в подключение `conn`."
  [conn]
  (.sendUTF conn stomp-keepalive)
  (println (now) "// Keepalive sent.")
  (js/setTimeout (partial send-keepalive conn) keepalive))

(defn act-on-message
  [conn user text]
  (let [[status header data] (parse-message text)]
    (case status
      "CONNECTED"
      (do
        (println (now) "// Connected to stomp.")
        (.sendUTF conn (stomp-subscribe (randomUUID) (randomUUID) user))
        (send-keepalive conn))

      "MESSAGE"
      (when (= (:event-type header) "notificationcenter.notice")
        (println (now) "// Notification received.")
        (when-let [notice (parse-notice data)]
          (send-desktop-notification (str (:header notice) "\n" (:body notice))))))))

(defn get-handler-message
  "Возвращает функцию обработки сообщений."
  [conn user]
  (fn [message]
    (if (= (.-type message) "utf8")
      (let [text (-> (.-utf8Data message)
                     (string/replace "\u0000" ""))]
        (if (= text stomp-keepalive)
          (println (now) "// Keepalive received.")
          (act-on-message conn user text))
        (println text))
      (println (now) "// Unknown message type:" (.-type message)))))

(defn get-handler-connect
  "Возвращает функцию обработки успешного подключения к серверу."
  [user]
  (fn [conn]
    (.on conn "error" handler-error)
    (.on conn "close" handler-close)
    (.on conn "message" (get-handler-message conn user))

    (when (.-connected conn)
      (println (now) "// Connected.")
      (.sendUTF conn (stomp-connect)))))

(defn connect-stomp
  "Подключается к серверу stomp."
  [client uri user]
  (.on client "connectFailed" handler-failed)
  (.on client "connect" (get-handler-connect user)) 
  (.connect client uri))

(defn get-stomp-url
  [sid]
  (gstr/format "wss://%s/stomp/%s/s-%s/websocket" (host "stomp") (host "online") sid))

(defn get-user-hash
  [sid]
  (p/-> (fetch (gstr/format "https://%s/!hash/" (host "online"))
               (clj->js {:headers {:cookie (str "sid=" sid)}}))
        .json
        (js->clj :keywordize-keys true)
        :result
        :user))

(defn watch-stomp
  "Следит за сообщениями stomp, выводит их в консоль."
  [sid]
  (p/let [user-hash (get-user-hash sid)]
    (if user-hash
      (connect-stomp (client.) (get-stomp-url sid) user-hash)
      (println (now) "// Could not get user id."))))

(-> (get-cookie-sid (host "online"))
    (p/then watch-stomp)
    (p/catch (fn [e] (and (instance? js/Error e)
                          (= e.code "SQLITE_BUSY")))
      (fn [_] (println "Database locked.")))
    (p/catch (fn [e] (and (instance? js/Error e)
                          (= e.code "ENOENT")))
      (fn [e] (println "File not found:" (.-path e)))))
