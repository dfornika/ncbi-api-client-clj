(ns ncbi-api-client.runner
  "Running plans in the browser (and Node): `fetch` sends each request, the
  throttle waits with a timer until it's its turn, and `run` returns a
  promise of the plan's value.

  A client's :fetch is a function like js/fetch: (fn [url options]
  promise-of-a-Response). It's js/fetch by default; a page can pass its
  own (to show that NCBI is busy, say), and tests pass a stub."
  (:require [ncbi-api-client.plan :as plan]
            [ncbi-api-client.request :as req]
            [ncbi-api-client.throttle :as throttle]))

(def api-key
  "`client`'s API key now, or nil (see `request/api-key`)."
  req/api-key)

(defn default-fetch
  "js/fetch, looked up when it's called."
  [url options]
  (js/fetch url options))

(defn fetch-options
  "The js/fetch options for description `request`, with the client's API
  key, tool and email added (see `request/outgoing`), and the URL."
  [client request]
  (let [{:keys [method url headers body json]} (req/outgoing client request)]
    [url (cond-> {:method  (.toUpperCase (name method))
                  :headers headers}
           body (assoc :body body)
           json (assoc :body (js/JSON.stringify (clj->js json))))]))

(defn- sleep [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve (max 0 ms)))))

(defn- wait-turn
  "A promise that resolves when it's this request's turn."
  [client]
  (let [wait (throttle/reserve! (:rate-limiter client) (req/rate client))]
    (if (pos? wait) (sleep wait) (js/Promise.resolve nil))))

(defn- header-map [^js headers]
  (let [m (atom {})]
    (.forEach headers (fn [v k] (swap! m assoc (.toLowerCase k) v)))
    @m))

(defn- decode
  "A promise of `response`'s body, read as `request` says (:json, :text or
  :bytes, a Uint8Array)."
  [request ^js response]
  (case (:as request)
    :bytes (.then (.arrayBuffer response) #(js/Uint8Array. %))
    :text  (.text response)
    (.then (.text response)
           (fn [s] (when-not (re-find #"^\s*$" s)
                     (js->clj (js/JSON.parse s) :keywordize-keys true))))))

(defn- attempt
  "A promise of the decoded body of one try at `request`, rejected with an
  ex-info (see `request/http-error`) when NCBI answers with an error. For a
  request marked :optional?, a 404 means nil."
  [client request]
  (let [[url options] (fetch-options client request)
        fetch         (or (:fetch client) default-fetch)]
    (.then (fetch url (clj->js options))
           (fn [^js response]
             (let [status (.-status response)]
               (cond
                 (<= 200 status 299) (decode request response)
                 (and (= 404 status) (:optional? request)) nil
                 :else (.then (.text response)
                              (fn [text]
                                (throw (req/http-error request status
                                                       (header-map (.-headers response))
                                                       text))))))))))

(defn send!
  "A promise of the decoded body of `request` (a description) for
  `client`, sent when it's its turn and retried on 429s and 5xx. It's
  rejected with an ex-info saying what was asked and what NCBI said."
  [client request]
  (letfn [(try-at [n]
            (-> (wait-turn client)
                (.then #(attempt client request))
                (.catch (fn [e]
                          (let [{:keys [status headers]} (ex-data e)]
                            (if-let [wait (throttle/retry-in n status (get headers "retry-after"))]
                              (.then (sleep wait) #(try-at (inc n)))
                              (throw (if (throttle/retryable-status? status)
                                       (throttle/exhausted status (inc n) e)
                                       e))))))))]
    (try-at 0)))

(defn run
  "A promise of the value of `x`, a plan (or a value), for `client`: its
  requests are made one after another."
  [client x]
  (if (plan/plan? x)
    (.then (send! client (:request x))
           (fn [body] (run client ((:then x) body))))
    (js/Promise.resolve x)))
