(ns main.websocket
  (:require [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
            [taoensso.timbre :as timbre]))

;;;; Logging config

(defonce min-log-level (atom nil))
(defn- set-min-log-level! [level]
  (sente/set-min-log-level! level) ; Min log level for internal Sente namespaces
  (timbre/set-ns-min-level! level) ; Min log level for this namespace
  (reset! min-log-level level))

(set-min-log-level! #_:trace :debug #_:info #_:warn)

;;;; Define our Sente channel socket (chsk) server

;; Serialization format, must use same val for client + server:
(let [packer :edn ; Default packer, a good choice in most cases
      ;; (sente-transit/get-transit-packer) ; Needs Transit dep
      ]

  (defonce chsk-server
    (sente/make-channel-socket-server!
     (get-sch-adapter)
     {:packer packer
      :csrf-token-fn
      (fn [ring-req]
        (let [csrf-token "for-chat"]
          (prn "client" (-> ring-req :params :client-id))
          (prn "TOKEN (server)" csrf-token)
          csrf-token))})))

(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      chsk-server]

  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

;;;; Some server>user async push

(defn broadcast!
  "Pushes given event to all connected users."
  [event]
  (let [all-uids (:any connected-uids)]
    (doseq [uid all-uids]
      (timbre/debugf "Broadcasting server>user to %s uids" (count all-uids))
      (chsk-send! uid event))))

;;;; Sente event handlers

(defmulti -event-msg-handler
  "Multimethod to handle Sente `event-msg`s."
  :id ; Dispatch on event-id
  )

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (-event-msg-handler ev-msg) ; Handle event-msgs on a single thread
  ;; (future (-event-msg-handler ev-msg)) ; Handle event-msgs on a thread pool
  )

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (timbre/debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:unmatched-event-as-echoed-from-server event}))))

(defmethod -event-msg-handler :chsk/uidport-open
  [{:as ev-msg :keys [ring-req]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (if uid
      (timbre/infof "User connected: user-id `%s`" uid)
      (timbre/infof "User connected: no user-id (user didn't have login session)"))))

(defmethod -event-msg-handler :chsk/uidport-close
  [{:as ev-msg :keys [ring-req]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (if uid
      (timbre/infof "User disconnected: user-id `%s`" uid)
      (timbre/infof "User disconnected: no user-id (user didn't have login session)"))))

(defmethod -event-msg-handler :example/toggle-min-log-level
  [{:as ev-msg :keys [?reply-fn]}]
  (let [new-val
        (case @min-log-level
          :trace :debug
          :debug :info
          :info  :warn
          :warn  :error
          :error :trace
          :trace)]

    (set-min-log-level! new-val)
    (?reply-fn          new-val)))

(defmethod -event-msg-handler :example/toggle-bad-conn-rate
  [{:as ev-msg :keys [?reply-fn]}]
  (let [new-val
        (case sente/*simulated-bad-conn-rate*
          nil  0.25
          0.25 0.5
          0.5  0.75
          0.75 1.0
          1.0  nil)]

    (alter-var-root #'sente/*simulated-bad-conn-rate* (constantly new-val))
    (?reply-fn new-val)))

(defmethod -event-msg-handler :example/connected-uids
  [{:as ev-msg :keys [?reply-fn]}]
  (let [uids @connected-uids]
    (timbre/infof "Connected uids: %s" uids)
    (?reply-fn                         uids)))

;;;; Sente event router (our `event-msg-handler` loop)

(defonce sente-router (atom nil))

(defn stop-sente-router! []
  (when-let [stop-fn @sente-router] (stop-fn)))

(defn start-sente-router! []
  (stop-sente-router!)
  (reset! sente-router
          (sente/start-server-chsk-router!
           ch-chsk event-msg-handler)))
