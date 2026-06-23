(ns ncbi-api-client.throttle-test
  (:require [clojure.test :refer [deftest is testing]]
            [ncbi-api-client.throttle :as throttle]))

(deftest create-rate-limiter-test
  (testing "creates an atom with token bucket state"
    (let [rl (throttle/create-rate-limiter 3)]
      (is (instance? clojure.lang.Atom rl))
      (is (= 3.0 (:tokens @rl)))
      (is (= 3.0 (:max-tokens @rl)))
      (is (= 3.0 (:refill-rate @rl))))))

(deftest acquire-nil-is-noop-test
  (testing "acquire! with nil rate-limiter does not throw"
    (is (nil? (throttle/acquire! nil)))))

(deftest acquire-consumes-tokens-test
  (testing "acquiring tokens decrements the bucket"
    (let [rl (throttle/create-rate-limiter 3)]
      (throttle/acquire! rl)
      (is (< (:tokens @rl) 3.0))
      (throttle/acquire! rl)
      (throttle/acquire! rl)
      (is (< (:tokens @rl) 1.0)))))

(deftest acquire-blocks-when-empty-test
  (testing "4th acquire at 3/s blocks for ~333ms"
    (let [rl    (throttle/create-rate-limiter 3)
          _     (dotimes [_ 3] (throttle/acquire! rl))
          start (System/nanoTime)
          _     (throttle/acquire! rl)
          elapsed-ms (/ (double (- (System/nanoTime) start)) 1e6)]
      (is (>= elapsed-ms 200) "should block at least 200ms")
      (is (<= elapsed-ms 1000) "should not block more than 1s"))))

(deftest token-refill-test
  (testing "tokens refill over time"
    (let [rl (throttle/create-rate-limiter 10)]
      (dotimes [_ 10] (throttle/acquire! rl))
      (is (< (:tokens @rl) 1.0))
      (Thread/sleep 500)
      (throttle/acquire! rl)
      (is (>= (:tokens @rl) 0.0) "should have refilled enough for the acquire"))))

(defn- make-hato-exception [status]
  (ex-info (str "HTTP " status) {:status status}))

(deftest retry-on-429-test
  (testing "retries once on 429 then succeeds"
    (let [call-count (atom 0)]
      (is (= :ok
             (throttle/with-retry nil
               (fn []
                 (swap! call-count inc)
                 (if (= 1 @call-count)
                   (throw (make-hato-exception 429))
                   :ok)))))
      (is (= 2 @call-count)))))

(deftest retry-on-500-test
  (testing "retries on 500 then succeeds"
    (let [call-count (atom 0)]
      (is (= :ok
             (throttle/with-retry nil
               (fn []
                 (swap! call-count inc)
                 (if (= 1 @call-count)
                   (throw (make-hato-exception 500))
                   :ok)))))
      (is (= 2 @call-count)))))

(deftest retry-exhaustion-429-test
  (testing "throws typed error after max retries on 429"
    (try
      (throttle/with-retry nil
        (fn [] (throw (make-hato-exception 429))))
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rate-limited (:ncbi/error (ex-data e))))
        (is (= 429 (:status (ex-data e))))
        (is (= 4 (:attempts (ex-data e))))))))

(deftest retry-exhaustion-503-test
  (testing "throws typed error after max retries on 503"
    (try
      (throttle/with-retry nil
        (fn [] (throw (make-hato-exception 503))))
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :server-error (:ncbi/error (ex-data e))))
        (is (= 503 (:status (ex-data e))))))))

(deftest non-retryable-errors-pass-through-test
  (testing "400 is not retried"
    (let [call-count (atom 0)]
      (try
        (throttle/with-retry nil
          (fn []
            (swap! call-count inc)
            (throw (make-hato-exception 400))))
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= 400 (:status (ex-data e))))
          (is (= 1 @call-count) "should not have retried")))))

  (testing "404 is not retried"
    (let [call-count (atom 0)]
      (try
        (throttle/with-retry nil
          (fn []
            (swap! call-count inc)
            (throw (make-hato-exception 404))))
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= 404 (:status (ex-data e))))
          (is (= 1 @call-count)))))))

(deftest with-retry-nil-limiter-test
  (testing "with-retry works with nil rate-limiter"
    (is (= :ok (throttle/with-retry nil (fn [] :ok))))))

(deftest with-retry-falsy-return-test
  (testing "with-retry returns nil when f returns nil"
    (is (nil? (throttle/with-retry nil (fn [] nil)))))
  (testing "with-retry returns false when f returns false"
    (is (false? (throttle/with-retry nil (fn [] false))))))

(deftest with-retry-preserves-cause-test
  (testing "wrapped error preserves original as cause"
    (try
      (throttle/with-retry nil
        (fn [] (throw (make-hato-exception 429))))
      (catch clojure.lang.ExceptionInfo e
        (is (some? (.getCause e)))
        (is (= 429 (:status (ex-data (.getCause e)))))))))
