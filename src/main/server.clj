(ns main.server
  (:require
   [clojure.spec.alpha :as s]
   [main.middlewares :as middlewares]
   [muuntaja.core :as m]
   [reitit.coercion.spec :as rcs]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as rrc]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :as rur]))

(s/def :sub/name string?)
(s/def :sub/mobile boolean?)
(s/def :sub/cn boolean?)

(s/def :sub/info (s/keys :req-un [:sub/name]
                         :opt-un [:sub/mobile :sub/cn]))

(defn- subscribe [request]
  (let [params (-> request :parameters :query)]
    (rur/response {:name    (:name params)
                   :mobile? (:mobile params)
                   :cn?     (:cn params)})))

(defn- hello [_]
  (rur/response {:hello "world"}))

(def root-routes
  ["/" {:get hello}])

(def subsribe-routes
  ["/subscribe" {:name ::subscribe
                 :get  {:parameters {:query :sub/info}
                        :handler    subscribe}}])

(defn- create-ring-handler []
  (ring/ring-handler
   (ring/router
    [root-routes
     subsribe-routes]
    ;; router data affecting all routes
    {:data {:coercion   rcs/coercion
            :muuntaja   m/instance
            :middleware [parameters/parameters-middleware
                         rrc/coerce-request-middleware
                         muuntaja/format-response-middleware
                         rrc/coerce-response-middleware]}})))

(defn start [options]
  (let [create-handler-fn #(create-ring-handler)
        handler* (if (:dev options)
                   (middlewares/reloading-ring-handler create-handler-fn)
                   (create-handler-fn))]
    (println "Server running on port" (:port options))
    (run-jetty handler* {:port (:port options) :join? false})))

(defn stop [_options]
  (println :stop)
  :stop)

(defn status [_options]
  (println :status)
  :status)
