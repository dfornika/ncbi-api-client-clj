(ns ncbi-api-client.throttle
  "Keeping to NCBI's rate limits, and retrying with backoff.

  The policy is pure functions, the same on both platforms:
  - `reserve`: a token bucket. Takes a token (going into debt if there's
    none) and says how long to wait before using it;
  - `retry-in`: whether, and how long after, to retry a failed request.

  Each platform waits its own way. On the JVM, `acquire!` and `with-retry`
  sleep. NCBI allows 3 requests a second without an API key, and 10 with
  one.")

(def max-retries 3)
(def base-backoff-ms 1000)

(defn now-ms
  "A clock in milliseconds, for the token bucket."
  []
  #?(:clj  (/ (double (System/nanoTime)) 1e6)
     :cljs (js/Date.now)))

;; --- Token bucket ---

(defn initial-state
  "A full bucket for `requests-per-second`, at time `now` (ms)."
  [requests-per-second now]
  (let [rate (double requests-per-second)]
    {:tokens rate :max-tokens rate :refill-rate rate :last-refill now}))

(defn reserve
  "Take a token from bucket `state` at time `now` (ms), at `rate` requests a
  second (default: the bucket's own). Returns [wait-ms new-state]: wait-ms
  is how long to wait before sending, 0 if a token was there. Callers that
  have to wait go into debt, so each waits its own turn."
  ([state now] (reserve state now nil))
  ([{:keys [tokens refill-rate last-refill]} now rate]
   (let [rate    (double (or rate refill-rate))
         elapsed (max 0.0 (/ (- now last-refill) 1000.0))
         tokens  (- (min rate (+ tokens (* elapsed rate))) 1.0)]
     [(if (neg? tokens) (* 1000.0 (/ (- tokens) rate)) 0.0)
      {:tokens tokens :max-tokens rate :refill-rate rate :last-refill now}])))

(defn create-rate-limiter
  "A rate limiter (an atom holding a bucket) for `requests-per-second`."
  [requests-per-second]
  {:pre [(number? requests-per-second) (pos? requests-per-second)]}
  (atom (initial-state requests-per-second (now-ms))))

(defn reserve!
  "Take a token from `rate-limiter` (at `rate` a second, or its own), and
  return how many ms to wait before sending. 0 if `rate-limiter` is nil."
  ([rate-limiter] (reserve! rate-limiter nil))
  ([rate-limiter rate]
   (if-not rate-limiter
     0.0
     (let [now (now-ms)]
       (loop []
         (let [old @rate-limiter
               [wait new] (reserve old now rate)]
           (if (compare-and-set! rate-limiter old new)
             wait
             (recur))))))))

;; --- Retry policy ---

(defn retryable-status?
  "Whether a request answered with HTTP `status` is worth trying again:
  429 (too many requests) and 5xx."
  [status]
  (boolean (and status (or (= status 429) (<= 500 status 599)))))

(defn retry-in
  "How many ms to wait before trying again after failure number `attempt`
  (0 for the first) with HTTP `status`, or nil not to. `retry-after` is the
  Retry-After header (seconds), if there was one."
  [attempt status retry-after]
  (when (and (retryable-status? status) (< attempt max-retries))
    (or (some-> retry-after str parse-long (* 1000))
        (* base-backoff-ms (long (Math/pow 2 attempt))))))

(defn exhausted
  "The error to give after `attempts` tries ending in HTTP `status`."
  [status attempts cause]
  (ex-info (str "NCBI API request failed after " attempts " attempts (HTTP " status ")")
           (merge (select-keys (ex-data cause) [:ncbi/request])
                  {:ncbi/error (if (= status 429) :rate-limited :server-error)
                   :status     status
                   :attempts   attempts})
           cause))

;; --- JVM: waiting by sleeping ---

#?(:clj
   (defn acquire!
     "Block until it's this request's turn. No-op if `rate-limiter` is nil."
     ([rate-limiter] (acquire! rate-limiter nil))
     ([rate-limiter rate]
      (let [wait (reserve! rate-limiter rate)]
        (when (pos? wait)
          (Thread/sleep (long (Math/ceil wait))))
        nil))))

#?(:clj
   (defn with-retry
     "Call `f` (no arguments), retrying on 429 and 5xx with exponential
     backoff (or as Retry-After says), and waiting for a token from
     `rate-limiter` before each retry. A failure is an ex-info whose data has
     :status; after the last retry it's wrapped by `exhausted`."
     ([rate-limiter f] (with-retry rate-limiter nil f))
     ([rate-limiter rate f]
      (loop [attempt 0]
        (let [result (try {:ok (f)} (catch Exception e {:error e}))]
          (if (contains? result :ok)
            (:ok result)
            (let [e      (:error result)
                  data   (ex-data e)
                  status (:status data)]
              (if-let [wait (retry-in attempt status (get-in data [:headers "retry-after"]))]
                (do (Thread/sleep (long wait))
                    (acquire! rate-limiter rate)
                    (recur (inc attempt)))
                (if (retryable-status? status)
                  (throw (exhausted status (inc attempt) e))
                  (throw e))))))))))
