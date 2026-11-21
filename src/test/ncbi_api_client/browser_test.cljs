(ns ncbi-api-client.browser-test
  "The browser runner, with a stub :fetch that answers with real Response
  objects (Node has them), so no request leaves the machine."
  (:require [clojure.datafy :refer [datafy nav]]
            [clojure.string :as str]
            [clojure.test :refer [async deftest is testing]]
            [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.plan :as plan]
            [ncbi-api-client.request :as req]))

(defn- response [{:keys [status headers body] :or {status 200 headers {}}}]
  (js/Response. (cond (string? body) body
                      (nil? body) ""
                      (instance? js/Uint8Array body) body
                      :else (js/JSON.stringify (clj->js body)))
                #js {:status status :headers (clj->js headers)}))

(defn- stub-fetch
  "A :fetch that answers with `(answer url options)` (a map, see
  `response`), and adds [url options] (options as Clojure data) to `asked`."
  [answer asked]
  (fn [url ^js options]
    (let [opts (js->clj options :keywordize-keys true)]
      (swap! asked conj [url opts])
      (js/Promise.resolve (response (answer url opts))))))

(defn- client
  "A client answering from `answer`, with no rate limit."
  ([answer asked] (client answer asked {}))
  ([answer asked opts]
   (-> (ncbi/connect (merge {:fetch (stub-fetch answer asked)} opts))
       (dissoc :rate-limiter))))

(defn- run
  "Run `x` with `c`'s runner. (A function, because inside `async` the
  compiler awaits an expression like ((:run c) c x), which unwraps the
  promise.)"
  [c x]
  ((:run c) c x))

(defn- done-or-fail
  "Finish an async test when `p` settles, failing it if `p` is rejected."
  [p done]
  (-> p
      (.catch (fn [e] (is (nil? e) (str "unexpected error: " (ex-message e)))))
      (.finally done)))

(def ^:private taxon
  {:reports [{:taxonomy {:tax_id 9606 :rank "SPECIES"}}] :total_count 1})

(defn- answers [url _]
  (cond
    (str/includes? url "/taxonomy/taxon/9606/dataset_report") {:body taxon}
    (str/includes? url "/genome/taxon/9606/dataset_report")
    {:body (if (str/includes? url "page_token=t2")
             {:reports [{:accession "GCA_2"}] :total_count 2}
             {:reports [{:accession "GCA_1"}] :total_count 2 :next_page_token "t2"})}
    (str/includes? url "/image/metadata") {:status 404 :body "{\"message\": \"Not Found\"}"}
    :else {:status 500 :body (str "no stub for " url)}))

(deftest datasets-request-test
  (async done
         (let [asked (atom [])
               c     (client answers asked {:api-key (atom "k1")})]
           (done-or-fail
            (.then (ncbi/taxonomy c "9606")
                   (fn [human]
                     (let [[url opts] (first @asked)]
                       (testing "a GET, with the key in the api-key header"
                         (is (= "https://api.ncbi.nlm.nih.gov/datasets/v2/taxonomy/taxon/9606/dataset_report" url))
                         (is (= "GET" (:method opts)))
                         (is (= "k1" (get-in opts [:headers :api-key])))))
                     (testing "the answer is a tagged entity"
                       (is (= 9606 (:tax_id human)))
                       (is (= :ncbi/taxonomy (:ncbi/type (meta human)))))))
            done))))

(deftest nav-gives-deferred-values-test
  (async done
         (let [asked (atom [])
               c     (client answers asked)]
           (done-or-fail
            (-> (ncbi/taxonomy c "9606")
                (.then (fn [human]
                         (let [d (nav (datafy human) :ncbi.nav/assemblies :deferred)]
                           (testing "nav makes no request: it gives a deferred value"
                             (is (= 1 (count @asked)))
                             (is (= :ncbi.nav/assemblies (:ncbi/deferred d)))
                             (is (= "assemblies of taxonomy 9606" (:ncbi/describe d)))
                             (is (= [:ncbi.nav/assemblies (:ncbi/request d)] (:async.nav/key (meta d))))
                             (testing "equal for equal requests, across calls"
                               (is (= (:async.nav/key (meta d))
                                      (:async.nav/key (meta (nav (datafy human) :ncbi.nav/assemblies :deferred)))))))
                           ((:async.nav/fetch (meta d))))))
                (.then (fn [page]
                         (testing "fetching it gives a page, which leads to the next"
                           (is (= ["GCA_1"] (map :accession page)))
                           (is (= 2 (:async.nav/total (meta page))))
                           ((:async.nav/fetch (meta (:async.nav/next (meta page))))))))
                (.then (fn [page2]
                         (is (= ["GCA_2"] (map :accession page2)))
                         (is (nil? (:async.nav/next (meta page2))))
                         (is (= 3 (count @asked))))))
            done))))

(deftest optional-404-test
  (async done
         (let [c (client answers (atom []))]
           (done-or-fail
            (-> (ncbi/taxonomy c "9606")
                (.then #((:async.nav/fetch (meta (nav (datafy %) :ncbi.nav/image :deferred)))))
                (.then #(is (nil? %) "a taxon without an image gives nil")))
            done))))

(deftest eutils-form-test
  (async done
         (let [asked (atom [])
               c     (client (fn [_ _] {:body {:esearchresult {:idlist [] :count "0"}}})
                             asked {:tool "data-explorer" :api-key "k"})]
           (done-or-fail
            (.then (ncbi/search c "gene" "BRCA1 human")
                   (fn [page]
                     (let [[url opts] (first @asked)]
                       (is (= [] page))
                       (is (= "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi" url))
                       (is (= "POST" (:method opts)))
                       (is (= "application/x-www-form-urlencoded" (get-in opts [:headers :content-type])))
                       (testing "tool and key go in the form; no email unless it's given"
                         (is (= "api_key=k&db=gene&retmode=json&term=BRCA1%20human&tool=data-explorer&usehistory=y"
                                (:body opts)))))))
            done))))

(deftest errors-test
  (async done
         (let [c (client (fn [_ _] {:status 400 :body "{\"code\": 3, \"message\": \"Invalid taxon\"}"}) (atom []))]
           (-> (ncbi/taxonomy c "nope")
               (.then (fn [_] (is false "should have failed")))
               (.catch (fn [e]
                         (is (= "NCBI said 400 to datasets taxonomy-data-report: Invalid taxon" (ex-message e)))
                         (is (= :http-error (:ncbi/error (ex-data e))))
                         (is (= :taxonomy-data-report (get-in (ex-data e) [:ncbi/request :op])))))
               (.finally done)))))

(deftest retry-test
  (async done
         (let [n (atom 0)
               c (client (fn [_ _]
                           (if (= 1 (swap! n inc))
                             {:status 429 :headers {"retry-after" "0"} :body ""}
                             {:body {:wikipedia "w"}}))
                         (atom []))]
           (done-or-fail
            (.then (run c (plan/plan (req/datasets :taxonomy-links {:taxon "9606"})))
                   (fn [body]
                     (testing "a 429 is retried, as Retry-After says"
                       (is (= {:wikipedia "w"} body))
                       (is (= 2 @n)))))
            done))))

(deftest bytes-test
  (async done
         (let [c (client (fn [_ _] {:body (js/Uint8Array. #js [0x50 0x4B 3 4])}) (atom []))]
           (done-or-fail
            (.then (run c (plan/plan
                                (req/datasets :download-gene-package {:gene-ids [672]})))
                   (fn [body]
                     (is (instance? js/Uint8Array body))
                     (is (= [0x50 0x4B 3 4] (vec (array-seq (js/Array.from body)))))))
            done))))
