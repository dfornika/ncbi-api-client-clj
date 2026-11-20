(ns ncbi-api-client.test-support
  "Clients that answer from stubs and recorded fixtures, with no network."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [ncbi-api-client.core :as ncbi]))

(defn fixture
  "The text of a recorded answer, test-resources/fixtures/<path>."
  [path]
  (slurp (io/resource (str "fixtures/" path))))

(defn- respond [r http-request]
  (let [r (if (fn? r) (r http-request) r)
        r (merge {:status 200 :headers {}} r)]
    (cond-> r
      (coll? (:body r)) (update :body json/generate-string))))

(defn stub-http
  "An :http function that answers each request from `responses`, a map of
  operation (:taxonomy-data-report, :esearch...) to a response
  ({:status :headers :body}, where a map or vector body is sent as JSON)
  or a function of the hato-style request that returns one. Every request
  is added to the `requests` atom, if given."
  ([responses] (stub-http responses nil))
  ([responses requests]
   (fn [http-request]
     (when requests (swap! requests conj http-request))
     (let [op (get-in http-request [:ncbi/request :op])]
       (if-let [r (get responses op)]
         (respond r http-request)
         (throw (ex-info (str "No stub response for " op) {:op op})))))))

(defn stub-client
  "A client whose requests are answered by `stub-http`, with no rate limit."
  ([responses] (stub-client responses nil))
  ([responses requests]
   (-> (ncbi/connect {:api-key "" :http (stub-http responses requests)})
       (dissoc :rate-limiter))))
