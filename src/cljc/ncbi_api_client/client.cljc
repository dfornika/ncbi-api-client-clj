(ns ncbi-api-client.client
  "Making a client: one for both the Datasets API and E-utilities, sharing
  one rate limit. `ncbi-api-client.runner` is the platform's runner:
  runner.clj on the JVM (hato, blocking), runner.cljs in the browser
  (fetch, promises)."
  (:require [ncbi-api-client.runner :as runner]
            [ncbi-api-client.throttle :as throttle]))

(defn- key-fn
  "The API key option as a function, read on each request: a string, a
  function, or anything deref-able (an atom). If it's nil: on the JVM, the
  NCBI_API_KEY environment variable; in the browser, no key."
  [api-key]
  (cond
    (nil? api-key)    #?(:clj  (let [k (System/getenv "NCBI_API_KEY")] (fn [] k))
                         :cljs (fn [] nil))
    (string? api-key) (fn [] api-key)
    (fn? api-key)     api-key
    #?(:clj  (instance? clojure.lang.IDeref api-key)
       :cljs (satisfies? IDeref api-key))
    (fn [] @api-key)
    :else (throw (ex-info "An :api-key is a string, a function or an atom"
                          {:ncbi/error :bad-option}))))

(defn create-client
  "A client map. Options:
    :api-key   - a string, a function or an atom, read on each request
                 (default: on the JVM, the NCBI_API_KEY environment
                 variable; in the browser, none)
    :tool      - the tool name E-utilities requests send (default
                 \"ncbi-api-client-clj\", or \"ncbi-api-client-cljs\")
    :email     - an email address for E-utilities requests (default: none)
    :base-urls - {:datasets \"…\" :eutils \"…\"} to send requests elsewhere
    :http      - JVM: the function that sends requests (default: hato)
    :fetch     - browser: a function like js/fetch, (fn [url options]
                 promise-of-a-Response) (default: js/fetch)

  Its :run is how plans are run (`ncbi-api-client.runner/run`): on the JVM
  it returns the value, in the browser a promise of it."
  ([] (create-client {}))
  ([{:keys [api-key tool email base-urls http fetch]}]
   (merge {:api-key      (key-fn api-key)
           :tool         (or tool #?(:clj "ncbi-api-client-clj" :cljs "ncbi-api-client-cljs"))
           :email        email
           :base-urls    base-urls
           :run          runner/run
           :rate-limiter (throttle/create-rate-limiter 3)}
          #?(:clj  {:http (or http (runner/hato-http))}
             :cljs {:fetch (or fetch runner/default-fetch)}))))
