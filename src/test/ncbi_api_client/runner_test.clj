(ns ncbi-api-client.runner-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [gen-routes]
            [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.test-support :as ts]))

(deftest routes-match-spec-test
  (testing "the checked-in route table is what the spec generates (clojure -X:gen-routes)"
    (is (= (gen-routes/rendered) (slurp gen-routes/out-path)))))

(deftest api-key-test
  (testing "Datasets requests send the key in the api-key header, read on each request"
    (let [requests (atom [])
          key      (atom "first")
          client   (-> (ncbi/connect {:api-key key
                                      :http (ts/stub-http {:taxonomy-links {:body {}}} requests)})
                       (dissoc :rate-limiter))]
      (ds/call client :taxonomy-links {:taxon "9606"})
      (reset! key "second")
      (ds/call client :taxonomy-links {:taxon "9606"})
      (is (= ["first" "second"] (map #(get-in % [:headers "api-key"]) @requests)))
      (testing "and never in the description, or the client's printed form"
        (is (not-any? #(str/includes? (pr-str (:ncbi/request %)) "first") @requests))
        (is (not (str/includes? (pr-str client) "second"))))))

  (testing "a blank key sends none"
    (let [requests (atom [])
          client   (ts/stub-client {:taxonomy-links {:body {}}} requests)]
      (ds/call client :taxonomy-links {:taxon "9606"})
      (is (nil? (get-in (first @requests) [:headers "api-key"]))))))

(deftest errors-test
  (testing "an error answer is an ex-info saying what was asked and what NCBI said"
    (let [client (ts/stub-client {:taxonomy-data-report
                                  {:status 400 :body "{\"code\": 3, \"message\": \"Invalid taxon\"}"}})
          e      (try (ncbi/taxonomy client "nope") nil (catch Exception e e))]
      (is (= "NCBI said 400 to datasets taxonomy-data-report: Invalid taxon" (ex-message e)))
      (is (= 400 (:status (ex-data e))))
      (is (= :taxonomy-data-report (get-in (ex-data e) [:ncbi/request :op]))))))

(deftest retry-test
  (testing "a 429 is retried, as Retry-After says"
    (let [n      (atom 0)
          client (ts/stub-client {:taxonomy-links
                                  (fn [_]
                                    (if (= 1 (swap! n inc))
                                      {:status 429 :headers {"retry-after" "0"} :body ""}
                                      {:body {:wikipedia "w"}}))})]
      (is (= {:wikipedia "w"} (ds/call client :taxonomy-links {:taxon "9606"})))
      (is (= 2 @n)))))
