(ns ncbi-api-client.throttle
  "Token-bucket rate limiter and retry-with-backoff for NCBI API requests.
   Shared across the Datasets (Martian) and E-utilities (hato) transport paths.")

(defn create-rate-limiter
  "Create a token-bucket rate limiter for `requests-per-second`.
   Returns an atom; pass it to `acquire!` before each request."
  [requests-per-second]
  (atom {:tokens      (double requests-per-second)
         :max-tokens  (double requests-per-second)
         :refill-rate (double requests-per-second)
         :last-refill (System/nanoTime)}))

(defn- refill [{:keys [max-tokens refill-rate last-refill] :as state}]
  (let [now     (System/nanoTime)
        elapsed (/ (double (- now last-refill)) 1e9)
        added   (* elapsed refill-rate)
        tokens  (min max-tokens (+ (:tokens state) added))]
    (assoc state :tokens tokens :last-refill now)))

(defn- try-acquire
  "Attempt to take one token. Returns [success? updated-state]."
  [state]
  (let [state (refill state)]
    (if (>= (:tokens state) 1.0)
      [true (update state :tokens - 1.0)]
      [false state])))

(defn acquire!
  "Block until a token is available, then consume it. No-op if `rate-limiter` is nil."
  [rate-limiter]
  (when rate-limiter
    (loop []
      (let [old @rate-limiter
            [success? new-state] (try-acquire old)]
        (if success?
          (when-not (compare-and-set! rate-limiter old new-state)
            (recur))
          (let [deficit  (- 1.0 (:tokens (refill old)))
                wait-ms  (max 1 (long (Math/ceil (* 1000.0 (/ deficit (:refill-rate old))))))]
            (Thread/sleep wait-ms)
            (recur)))))))

;; --- Retry with backoff ---

(defn- retryable-status? [status]
  (when status
    (or (= status 429) (and (>= status 500) (< status 600)))))

(defn- extract-status
  "Extract HTTP status from a hato ExceptionInfo, or nil."
  [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (:status (ex-data e))))

(defn- retry-after-ms
  "Parse Retry-After header (seconds) from hato ex-data, or nil."
  [e]
  (when (instance? clojure.lang.ExceptionInfo e)
    (when-let [ra (get-in (ex-data e) [:headers "retry-after"])]
      (try (* 1000 (Long/parseLong ra)) (catch Exception _ nil)))))

(def ^:private max-retries 3)
(def ^:private base-backoff-ms 1000)

(defn with-retry
  "Call `f` (a no-arg fn), retrying on 429/5xx with exponential backoff.
   Re-acquires a rate-limiter token before each retry. No-op wrapper if
   `rate-limiter` is nil (still calls `f` once)."
  [rate-limiter f]
  (loop [attempt 0]
    (let [result (try
                   {:ok (f)}
                   (catch Exception e
                     {:error e}))]
      (if (:ok result)
        (:ok result)
        (let [e      (:error result)
              status (extract-status e)]
          (if (and (retryable-status? status) (< attempt max-retries))
            (let [wait (or (retry-after-ms e)
                           (* base-backoff-ms (long (Math/pow 2 attempt))))]
              (Thread/sleep wait)
              (acquire! rate-limiter)
              (recur (inc attempt)))
            (if (retryable-status? status)
              (throw (ex-info (str "NCBI API request failed after " (inc attempt) " attempts (HTTP " status ")")
                              {:ncbi/error (if (= status 429) :rate-limited :server-error)
                               :status     status
                               :attempts   (inc attempt)}
                              e))
              (throw e))))))))
