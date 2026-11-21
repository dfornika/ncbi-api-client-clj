(ns ncbi-api-client.nav-test
  "Edges, pages and the bridge with a client whose :run answers from a
  map of operation -> body, so they run on both platforms."
  (:require [clojure.datafy :refer [datafy nav]]
            [clojure.test :refer [deftest is testing]]
            [ncbi-api-client.bridge :as bridge]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.plan :as plan]))

(defn- client
  "A client that answers each request with `answers`' body for its op, and
  adds the request to `asked`."
  [answers asked]
  {:nav-edges ds/nav-edges
   :run       (fn [_ x]
                (loop [x x]
                  (if (plan/plan? x)
                    (let [r (:request x)]
                      (swap! asked conj r)
                      (recur ((:then x) (get answers (:op r)))))
                    x)))})

(def ^:private answers
  {:taxonomy-data-report
   {:reports [{:taxonomy {:tax_id 9606 :children [741158]}}] :total_count 1}
   :genome-dataset-reports-by-taxon
   {:reports [{:accession "GCF_000001405.40" :organism {:tax_id 9606}}] :total_count 2
    :next_page_token "t2"}})

(defn- human [c]
  ((:run c) c (ds/one-plan c :taxonomy-data-report {:taxons ["9606"]} :ncbi/taxonomy)))

(defn- value
  "What nav gave: the value on the JVM; in the browser, a deferred value,
  fetched here (the fake :run answers at once, not with a promise)."
  [x]
  #?(:clj  x
     :cljs (do (is (fn? (:async.nav/fetch (meta x))) "nav gives a deferred value")
               ((:async.nav/fetch (meta x))))))

(deftest entity-nav-test
  (let [asked (atom [])
        c     (client answers asked)
        h     (human c)]
    (testing "datafy shows nav keys, and makes no request"
      (is (= :deferred (:ncbi.nav/assemblies (datafy h))))
      (is (= 1 (count @asked))))

    (testing "nav follows an edge: one page, with what leads to the next"
      (let [x     (nav (datafy h) :ncbi.nav/assemblies :deferred)
            _     #?(:clj nil :cljs (is (= 1 (count @asked)) "a deferred value asks nothing until fetched"))
            page  (value x)
            next  (:async.nav/next (meta page))]
        (is (= ["GCF_000001405.40"] (map :accession page)))
        (is (= :ncbi/assembly (:ncbi/type (meta (first page)))))
        (is (= 2 (:async.nav/total (meta page))))
        (is (= "t2" (get-in next [:ncbi/request :query "page_token"])))
        #?(:cljs (is (= "assemblies of taxonomy 9606" (:ncbi/describe x))))))

    (testing "an edge with nothing to follow gives nil"
      (is (nil? (nav (datafy (with-meta {} (meta h))) :ncbi.nav/assemblies :deferred))))))

(deftest bridge-test
  (let [asked (atom [])
        c     (client {:esearch  {:esearchresult {:idlist ["9606"] :count "1"}}
                       :esummary {:result {:uids ["9606"] :9606 {:uid "9606" :scientificname "Homo sapiens"}}}
                       :taxonomy-data-report (:taxonomy-data-report answers)}
                      asked)
        page  ((:run c) c (bridge/search-plan c "taxonomy" "human"))
        r     (first page)]
    (testing "search results are navigable records; datafy asks nothing"
      (is (= 1 (:async.nav/total (meta page))))
      (is (= :deferred (:ncbi.elink/taxonomy_assembly (datafy r))))
      (is (= 2 (count @asked))))
    (testing "and lead into Datasets"
      (is (= 9606 (:tax_id (value (nav (datafy r) :ncbi.nav/datasets-entity :deferred))))))))
