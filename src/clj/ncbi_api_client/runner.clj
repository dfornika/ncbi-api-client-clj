(ns ncbi-api-client.runner
  "Running plans on the JVM: hato sends each request, the throttle sleeps
  until it's its turn, and `run` blocks until the plan's value is known.

  A client's :http is a function from a hato-style request map
  ({:method :url :headers :body :as}) to a response ({:status :headers
  :body}). It's hato by default; tests pass a stub. The request map also
  has the request's description under :ncbi/request, for stubs to match on."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hato.client :as hc]
            [ncbi-api-client.plan :as plan]
            [ncbi-api-client.request :as req]
            [ncbi-api-client.throttle :as throttle]))

(defn api-key
  "The client's API key now, or nil. :api-key is a function, so the key
  isn't printed with the client."
  [client]
  (let [k (when-let [f (:api-key client)] (f))]
    (when-not (str/blank? k) k)))

(defn rate
  "How many requests a second the client may make: 10 with an API key, 3
  without."
  [client]
  (if (api-key client) 10 3))

(defn hato-http
  "An :http function that sends requests with hato."
  ([] (hato-http (hc/build-http-client {:connect-timeout 10000 :redirect-policy :normal})))
  ([http-client]
   (fn [request]
     (hc/request (-> request
                     (dissoc :ncbi/request)
                     (assoc :http-client http-client :throw-exceptions? false))))))

(defn http-request
  "The hato-style request for description `request`, with the client's API
  key, tool and email added."
  [client {:keys [api method form json as accept] :as request}]
  (let [k    (api-key client)
        form (when form
               (cond-> form
                 (= api :eutils) (merge (cond-> {}
                                          (:tool client)  (assoc "tool" (:tool client))
                                          (:email client) (assoc "email" (:email client))
                                          k               (assoc "api_key" k)))))]
    {:method       method
     :url          (req/url-for (:base-urls client) request)
     :headers      (cond-> {}
                     accept                    (assoc "accept" accept)
                     (and k (= api :datasets)) (assoc "api-key" k)
                     form                      (assoc "content-type" "application/x-www-form-urlencoded")
                     json                      (assoc "content-type" "application/json"))
     :body         (cond form (req/query-string form)
                         json (json/generate-string json))
     :as           (if (= as :bytes) :byte-array :string)
     :ncbi/request request}))

(defn- text-of [body]
  (cond (bytes? body) (String. ^bytes body "UTF-8")
        (string? body) body))

(defn- decode [{:keys [as]} body]
  (case as
    :json (let [s (text-of body)] (when-not (str/blank? s) (json/parse-string s true)))
    :text (text-of body)
    body))

(defn send!
  "Send `request` (a description) for `client`, when it's its turn,
  retrying 429s and 5xx. Returns the decoded body, or throws an ex-info
  saying what was asked and what NCBI said. For a request marked
  :optional?, a 404 means nil."
  [client request]
  (let [limiter (:rate-limiter client)
        r       (rate client)
        http    (or (:http client) (throw (ex-info "This client has no :http" {:ncbi/error :no-http})))]
    (throttle/acquire! limiter r)
    (throttle/with-retry limiter r
      (fn []
        (let [{:keys [status headers body]} (http (http-request client request))]
          (cond
            (and status (<= 200 status 299)) (decode request body)
            (and (= 404 status) (:optional? request)) nil
            :else (throw (req/http-error request status headers (text-of body)))))))))

(defn run
  "Run `x`, a plan (or a value), for `client`: make its requests one after
  another and return its value."
  [client x]
  (loop [x x]
    (if (plan/plan? x)
      (recur ((:then x) (send! client (:request x))))
      x)))
