(ns main.server
  (:require
   [clojure.spec.alpha :as s]
   [main.middlewares :as middlewares]
   [muuntaja.core :as m]
   [reitit.coercion.spec :as rcs]
   [reitit.openapi :as openapi]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as rrc]
   [reitit.ring.middleware.exception :as exception]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.swagger :as swagger]
   [reitit.swagger-ui :as swagger-ui]
   [ring.adapter.jetty :refer [run-jetty]]))

(s/def :sub/name string?)
(s/def :sub/mobile boolean?)
(s/def :sub/cn boolean?)

(s/def :sub/request (s/keys :req-un [:sub/name]
                            :opt-un [:sub/mobile :sub/cn]))
(s/def :sub/response (s/keys :req-un [:sub/name :sub/mobile :sub/cn]))

(defn- subscribe-get [request]
  (let [params (-> request :parameters :query)]
    {:status 200
     :body   {:name   (:name params)
              :mobile (:mobile params false)
              :cn     (:cn params false)}}))

(defn- subscribe-post [request]
  (let [params (-> request :parameters :body)]
    {:status 200
     :body   {:name   (:name params)
              :mobile (:mobile params false)
              :cn     (:cn params false)}}))

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
                        :responses  {200 {:body :sub/response}}
                        :handler    subscribe-get}
                 :post {:summary    "Get subscribe json with spec body parameters"
                        :parameters {:body :sub/request}
                        :responses  {200 {:body :sub/response}}
                        :handler subscribe-post}}])

(defn- create-ring-handler []
  (ring/ring-handler
   (ring/router
    [root-routes
     subsribe-routes]
    ;; router data affecting all routes
    {:data {:coercion   rcs/coercion
            :muuntaja   m/instance
            :middleware [;; swagger feature
                         swagger/swagger-feature
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
