(ns main.websocket
  (:require [ring.websocket :as ws]))

(defn echo-handler [request]
  {::ws/listener
   {:on-open
    #(ws/send % "I will echo your messages")
    :on-error
    (fn [socket throwable]
      (ws/send socket throwable))
    :on-close
    (fn [socket code reason]
      (ws/send socket {:code code :reason reason}))
    :on-message
    (fn [socket message]
      (if (= message "exit")
        (ws/close socket)
        (ws/send socket message)))}})
