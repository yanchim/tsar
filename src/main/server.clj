(ns main.server
  (:require
   [clojure.edn :as edn]
   [clojure.spec.alpha :as s]
   [main.db :as db]
   [main.middlewares :as middlewares]
   [main.websocket :as ws]
   [muuntaja.core :as m]
   [org.httpkit.server :as hks]
   [reitit.coercion.spec :as rcs]
   [reitit.openapi :as openapi]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as rrc]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.middleware.keyword-params :refer [wrap-keyword-params]]
   [ring.middleware.params :refer [wrap-params]]
   [spec-tools.core :as st]
   [taoensso.sente :refer [sente-version]]
   [taoensso.telemere :as t]))

(defonce data (let [prefix "data/"]
                {:default (str prefix "client.edn")
                 :cn (str prefix "client-cn.edn")
                 :cnmobile (str prefix "client-cnmobile.edn")
                 :mobile (str prefix "client-mobile.edn")
                 :password (str prefix "password.edn")}))

(s/def :sub/name string?)
(s/def :sub/mobile boolean?)
(s/def :sub/cn boolean?)

(s/def :sub/request (s/keys :req-un [:sub/name]
                            :opt-un [:sub/mobile :sub/cn]))

(s/def :chat-history/limit (st/spec {:spec int?
                                     :name "Limit parameter"
                                     :json-schema/default 50}))

(s/def :chat-history/request (s/keys :opt-un [:chat-history/limit]))

(s/def :chat-history/id int?)
(s/def :chat-history/name string?)
(s/def :chat-history/content string?)
(s/def :chat-history/inserted_at inst?)

(s/def :chat-history/response (s/coll-of
                               (s/keys :req-un
                                       [:chat-history/id :chat-history/name
                                        :chat-history/content :chat-history/inserted_at])))

(defn- subscribe-get [request]
  (let [{:keys [name mobile cn]} (-> request :parameters :query)
        config (cond
                 (and mobile cn)
                 (-> (:cnmobile data) slurp edn/read-string)
                 mobile
                 (-> (:mobile data) slurp edn/read-string)
                 cn
                 (-> (:cn data) slurp edn/read-string)
                 :else
                 (-> (:default data) slurp edn/read-string))
        password ((keyword name) (-> (:password data) slurp edn/read-string))]
    (if cn
      {:status 200
       :body config}
      {:status 200
       :body (update-in
              config
              [:outbounds]
              (fn [outbounds]
                (map (fn [outbound]
                       (if (= (:type outbound) "shadowtls")
                         (assoc outbound :password password)
                         outbound))
                     outbounds)))})))

(defn- subscribe-post [request]
  (let [{:keys [name mobile cn]} (-> request :parameters :body)
        config (cond
                 (and mobile cn)
                 (-> (:cnmobile data) slurp edn/read-string)
                 mobile
                 (-> (:mobile data) slurp edn/read-string)
                 cn
                 (-> (:cn data) slurp edn/read-string)
                 :else
                 (-> (:default data) slurp edn/read-string))
        password ((keyword name) (-> (:password data) slurp edn/read-string))]
    (if cn
      {:status 200
       :body config}
      {:status 200
       :body (update-in
              config
              [:outbounds]
              (fn [outbounds]
                (map (fn [outbound]
                       (if (= (:type outbound) "shadowtls")
                         (assoc outbound :password password)
                         outbound))
                     outbounds)))})))

(defn chat-history-post [req]
  (let [{:keys [limit]} (-> req :parameters :body)]
    {:status 200
     :body (-> limit
               (db/db->message limit)
               reverse)}))

(defn- ping [_]
  {:status 200
   :body {:hello "world"}})

(def root-routes
  ["" {:no-doc true}
   ["/" {:get ping}]
   ["/swagger.json" {:get {:swagger {:info {:title "swagger"
                                            :description "swagger with reitit"
                                            :version "0.1.0"}}
                           :handler (swagger/create-swagger-handler)}}]
   ["/openapi.json"
    {:get {:openapi {:info {:title       "openapi"
                            :description "openapi3-docs with reitit"
                            :version     "0.1.0"}}
           :handler (openapi/create-openapi-handler)}}]
   ["/api*" {:get (swagger-ui/create-swagger-ui-handler)}]])

(def subsribe-routes
  ["/subscribe" {:name ::subscribe
                 :get  {:summary    "Get subscribe json with spec query parameters"
                        :parameters {:query :sub/request}
                        :responses  {200 {}}
                        :handler    subscribe-get}
                 :post {:summary    "Get subscribe json with spec body parameters"
                        :parameters {:body :sub/request}
                        :responses  {200 {:body {}}}
                        :handler subscribe-post}}])

(def chat-routes
  [["/chat" {:name ::websocket
             :middleware [#(wrap-cors
                            %
                            :access-control-allow-origin [#".*"]
                            :access-control-allow-headers #{"accept"
                                                            "accept-encoding"
                                                            "accept-language"
                                                            "authorization"
                                                            "content-type"
                                                            "origin"}
                            :access-control-allow-methods [:get :post])]
             :get {:summary "WebSocket for chat"
                   :handler ws/ring-ajax-get-or-ws-handshake}
             :post {:summary "WebSocket for chat"
                    :handler ws/ring-ajax-post}}]
   ["/chat-history" {:name ::chat-history
                     :post {:summary "Chat history"
                            :parameters {:body :chat-history/request}
                            :responses {200 {:body :chat-history/response}}
                            :handler chat-history-post}}]])

(defn- create-ring-handler []
  (ring/ring-handler
   (ring/router
    [root-routes
     subsribe-routes
     chat-routes]
    ;; router data affecting all routes
    {:data {:coercion   rcs/coercion
            :muuntaja   m/instance
            :middleware [;; swagger feature
                         swagger/swagger-feature
                         ;; for websockets (sente looks for the
                         ;; `:param` key in the request map)
                         wrap-params
                         wrap-keyword-params
                         ;; query-params & form-params
                         parameters/parameters-middleware
                         ;; content-negotiation
                         muuntaja/format-negotiate-middleware
                         ;; encoding response body
                         muuntaja/format-response-middleware
                         ;; exception handling
                         exception/exception-middleware
                         ;; decoding request body
                         muuntaja/format-request-middleware
                         ;; coercing response bodys
                         rrc/coerce-response-middleware
                         ;; coercing request parameters
                         rrc/coerce-request-middleware]}})))

;;;; Init stuff

(defonce web-server (atom nil))         ; (fn stop [])

(defn stop-web-server! []
  (when-let [stop-fn @web-server] (stop-fn)))

(defn start-web-server! [& [port dev]]
  (stop-web-server!)
  (let [port (or port 3000)           ; 0 => Choose any available port
        create-handler-fn #(create-ring-handler)
        handler* (if dev
                   (middlewares/reloading-ring-handler create-handler-fn)
                   (create-handler-fn))
        [port stop-fn]
        (let [stop-fn (hks/run-server handler* {:port port :join? false})]
          [(:local-port (meta stop-fn)) (fn stop-fn [] (stop-fn :timeout 100))])
        uri (format "http://localhost:%s/" port)]

    (t/add-handler! ::logfile (t/handler:file {:path "logs/app.log"}))
    (t/log! :info ["HTTP server is running at" uri])

    (reset! web-server stop-fn)))

(defn status! []
  (let [status :not-implemented]
    (println status)
    status))

(defn stop! [] (ws/stop-sente-router!) (stop-web-server!))

(defn start! [& [port dev]]
  (t/log! :report ["Sente version: " sente-version])
  (t/log! :report ["Min log level: " @ws/min-log-level])
  (ws/start-sente-router!)
  (let [stop-fn (start-web-server! port dev)]
    stop-fn))

(comment
  (start! nil true))
