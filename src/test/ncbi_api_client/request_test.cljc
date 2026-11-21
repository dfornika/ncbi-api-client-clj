(ns ncbi-api-client.request-test
  (:require [clojure.test :refer [deftest is testing]]
            [ncbi-api-client.plan :as plan]
            [ncbi-api-client.request :as req]
            [ncbi-api-client.throttle :as throttle]))

(defn- thrown-data [f]
  (try (f) nil (catch #?(:clj Exception :cljs :default) e (ex-data e))))

;; --- Datasets requests ---

(deftest datasets-request-test
  (testing "path parameters fill the template; sequential ones are joined with commas"
    (is (= {:api :datasets :op :taxonomy-data-report :method :get
            :url "https://api.ncbi.nlm.nih.gov/datasets/v2/taxonomy/taxon/9606,10090/dataset_report"
            :as :json :accept "application/json"}
           (req/datasets :taxonomy-data-report {:taxons ["9606" "10090"]}))))

  (testing "path values are percent-encoded"
    (is (= "https://api.ncbi.nlm.nih.gov/datasets/v2/taxonomy/taxon/house%20mouse,Homo%20sapiens/dataset_report"
           (:url (req/datasets :taxonomy-data-report {:taxons ["house mouse" "Homo sapiens"]})))))

  (testing "names can use - or _; query values become strings"
    (let [r (req/datasets :genome-dataset-reports-by-taxon
                          {:taxons ["9606"] :filters.reference_only true :page-size 5 :page_token nil})]
      (is (= {"filters.reference_only" "true" "page_size" "5"} (:query r)))))

  (testing "sequential query values stay lists, to repeat the name"
    (is (= {"include_annotation_type" ["FASTA_GENE" "FASTA_PROTEIN"]}
           (:query (req/datasets :download-gene-package
                                 {:gene-ids [672] :include-annotation-type ["FASTA_GENE" "FASTA_PROTEIN"]})))))

  (testing "downloads read bytes, as application/zip"
    (is (= [:bytes "application/zip"]
           ((juxt :as :accept) (req/datasets :download-gene-package {:gene_ids [672]})))))

  (testing "a POST route takes the other parameters as its JSON body"
    (let [r (req/datasets :taxonomy-data-report-post {:taxons ["9606"]})]
      (is (= :post (:method r)))
      (is (= {"taxons" ["9606"]} (:json r)))))

  (testing "mistakes are errors that say what's wrong"
    (is (= :unknown-operation (:ncbi/error (thrown-data #(req/datasets :no-such-op {})))))
    (is (= ["taxons"] (:missing (thrown-data #(req/datasets :taxonomy-data-report {})))))
    (is (= ["bogus"] (:unknown (thrown-data #(req/datasets :taxonomy-data-report {:taxons ["1"] :bogus 1})))))))

;; --- Encoding and URLs ---

(deftest encoding-test
  (testing "url-encode matches encodeURIComponent"
    (is (= "a%20b%2Cc!'()*~-_.%2F%3F%26%3D%40" (req/url-encode "a b,c!'()*~-_./?&=@"))))

  (testing "query strings are in name order, repeating names for lists"
    (is (= "a=1&b=x&b=y%20z" (req/query-string {"b" ["x" "y z"] "a" "1"}))))

  (testing "url-for adds the query string, and moves the request to the client's base URL"
    (let [r (req/datasets :genome-dataset-reports-by-taxon {:taxons ["9606"] :page_size 2})]
      (is (= "https://api.ncbi.nlm.nih.gov/datasets/v2/genome/taxon/9606/dataset_report?page_size=2"
             (req/url-for nil r)))
      (is (= "https://dev.ncbi.nlm.nih.gov/datasets/v2/genome/taxon/9606/dataset_report?page_size=2"
             (req/url-for {:datasets "https://dev.ncbi.nlm.nih.gov/datasets/v2"} r))))))

(deftest http-error-test
  (testing "an error says what was asked and what NCBI said"
    (let [r (req/datasets :taxonomy-data-report {:taxons ["1"]})
          e (req/http-error r 400 {} "{\"code\": 3, \"message\": \"Invalid taxon\"}")]
      (is (= "NCBI said 400 to datasets taxonomy-data-report: Invalid taxon" (ex-message e)))
      (is (= :http-error (:ncbi/error (ex-data e))))
      (is (= r (:ncbi/request (ex-data e))))))

  (testing "a message with escaped quotes is read whole"
    (is (= "NCBI said 400 to datasets taxonomy-data-report: Invalid argument: 'parsing \"bogus\"'"
           (ex-message (req/http-error (req/datasets :taxonomy-data-report {:taxons ["1"]}) 400 {}
                                       "{\"code\": 3, \"message\": \"Invalid argument: 'parsing \\\"bogus\\\"'\"}")))))

  (testing "errors are typed by status"
    (is (= [:rate-limited :server-error :not-found]
           (map #(:ncbi/error (ex-data (req/http-error {:api :eutils :op :esearch} % {} nil)))
                [429 503 404])))))

;; --- Plans ---

(deftest plan-test
  (let [run (fn [x answers]
              ;; a runner that answers requests from a list
              (loop [x x answers answers]
                (if (plan/plan? x)
                  (recur ((:then x) (first answers)) (rest answers))
                  x)))]
    (testing "a plan's value is its then of the answer"
      (is (= 2 (run (plan/plan {:op :a} inc) [1]))))

    (testing "then chains plans, and plans that return plans"
      (is (= [1 2]
             (run (plan/then (plan/plan {:op :a})
                             (fn [a] (plan/plan {:op :b} (fn [b] [a b]))))
                  [1 2]))))

    (testing "then on a value calls f now"
      (is (= 2 (plan/then 1 inc))))))

;; --- Throttle and retry policy ---

(deftest reserve-test
  (let [s0 (throttle/initial-state 3 0)]
    (testing "a full bucket gives tokens without waiting"
      (let [[w1 s1] (throttle/reserve s0 0)
            [w2 s2] (throttle/reserve s1 0)
            [w3 s3] (throttle/reserve s2 0)]
        (is (= [0.0 0.0 0.0] [w1 w2 w3]))
        (testing "then each caller waits its own turn"
          (let [[w4 s4] (throttle/reserve s3 0)
                [w5 _]  (throttle/reserve s4 0)]
            (is (< 333 w4 334))
            (is (< 666 w5 667))))))

    (testing "tokens come back with time"
      (let [s (nth (iterate #(second (throttle/reserve % 0)) s0) 3)]
        (is (= 0.0 (first (throttle/reserve s 1000))))))

    (testing "the rate can be given on each call (with a key, 10 a second)"
      (let [s (nth (iterate #(second (throttle/reserve % 0 10)) (throttle/initial-state 10 0)) 10)]
        (is (< 99 (first (throttle/reserve s 0 10)) 101))))))

(deftest retry-in-test
  (testing "429 and 5xx are retried with exponential backoff, up to 3 times"
    (is (= [1000 2000 4000 nil] (map #(throttle/retry-in % 429 nil) (range 4))))
    (is (= 1000 (throttle/retry-in 0 503 nil))))
  (testing "Retry-After is honoured"
    (is (= 5000 (throttle/retry-in 0 429 "5"))))
  (testing "other statuses aren't retried"
    (is (nil? (throttle/retry-in 0 404 nil)))
    (is (nil? (throttle/retry-in 0 nil nil)))))
