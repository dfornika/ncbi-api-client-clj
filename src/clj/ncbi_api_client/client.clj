(ns ncbi-api-client.client
  "Making a client: one for both the Datasets API and E-utilities, sharing
  one rate limit."
  (:require [ncbi-api-client.runner :as runner]
            [ncbi-api-client.throttle :as throttle]))

(defn- key-fn
  "The API key option as a function, read on each request: a string, a
  function, or anything deref-able (an atom); the NCBI_API_KEY environment
  variable if it's nil."
  [api-key]
  (cond
    (nil? api-key)  (let [k (System/getenv "NCBI_API_KEY")] (fn [] k))
    (string? api-key) (fn [] api-key)
    (fn? api-key)     api-key
    (instance? clojure.lang.IDeref api-key) (fn [] @api-key)
    :else (throw (ex-info "An :api-key is a string, a function or an atom"
                          {:ncbi/error :bad-option}))))

(defn create-client
  "A client map. Options:
    :api-key   - a string, a function or an atom, read on each request
                 (default: the NCBI_API_KEY environment variable)
    :tool      - the tool name E-utilities requests send (default \"ncbi-api-client-clj\")
    :email     - an email address for E-utilities requests (default: none)
    :base-urls - {:datasets \"…\" :eutils \"…\"} to send requests elsewhere
    :http      - the function that sends requests (default: hato)

  Its :run is how plans are run (`ncbi-api-client.runner/run`)."
  ([] (create-client {}))
  ([{:keys [api-key tool email base-urls http]}]
   {:api-key      (key-fn api-key)
    :tool         (or tool "ncbi-api-client-clj")
    :email        email
    :base-urls    base-urls
    :http         (or http (runner/hato-http))
    :run          runner/run
    :rate-limiter (throttle/create-rate-limiter 3)}))
