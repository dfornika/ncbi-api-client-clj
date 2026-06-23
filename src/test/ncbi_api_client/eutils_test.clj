(ns ncbi-api-client.eutils-test
  (:require [clojure.datafy :refer [datafy nav]]
            [clojure.test :refer [deftest is testing]]
            [ncbi-api-client.bridge :as bridge]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.eutils :as eu]))

(defn- mock-eutils-client
  "Create a minimal eutils client map for testing (no real HTTP client)."
  []
  {:http-client nil
   :api-key     "fake-key"
   :tool        "test"
   :email       "test@example.com"})

;; --- eutils unit tests ---

(deftest resolve-client-unwraps-eutils-key
  (testing "resolve-client extracts :eutils from a unified client"
    (let [inner {:http-client :mock :api-key "k"}
          unified {:eutils inner :other-stuff true}]
      (is (= inner (#'eu/resolve-client unified)))))

  (testing "resolve-client passes through a plain eutils client"
    (let [plain {:http-client :mock :api-key "k"}]
      (is (= plain (#'eu/resolve-client plain))))))

(deftest esearch-parses-response
  (testing "esearch extracts ids, count, retmax, retstart from response"
    (let [client (mock-eutils-client)
          fake-response {:esearchresult {:idlist ["672" "675"]
                                         :count "2"
                                         :retmax "20"
                                         :retstart "0"}}]
      (with-redefs [eu/request (fn [_ _ _] fake-response)]
        (let [result (eu/esearch client "gene" "BRCA1")]
          (is (= ["672" "675"] (:ids result)))
          (is (= 2 (:count result)))
          (is (= 20 (:retmax result)))
          (is (= 0 (:retstart result))))))))

(deftest esummary-parses-response
  (testing "esummary returns vector of summaries keyed by uid"
    (let [client (mock-eutils-client)
          fake-response {:result {:uids ["672"]
                                  :672 {:uid "672" :name "BRCA1"}}}]
      (with-redefs [eu/request (fn [_ _ _] fake-response)]
        (let [result (eu/esummary client "gene" ["672"])]
          (is (= 1 (count result)))
          (is (= "672" (:uid (first result))))
          (is (= "BRCA1" (:name (first result))))))))

  (testing "esummary returns empty vector when no uids in response"
    (let [client (mock-eutils-client)]
      (with-redefs [eu/request (fn [_ _ _] {:result {}})]
        (is (= [] (eu/esummary client "gene" ["999"])))))))

(deftest elink-parses-linksets
  (testing "elink extracts dbto, linkname, and ids from response"
    (let [client (mock-eutils-client)
          fake-response {:linksets [{:linksetdbs [{:dbto "pubmed"
                                                   :linkname "gene_pubmed"
                                                   :links ["111" "222"]}]}]}]
      (with-redefs [eu/request (fn [_ _ _] fake-response)]
        (let [result (eu/elink client "gene" "672")]
          (is (= 1 (count result)))
          (is (= "pubmed" (:dbto (first result))))
          (is (= "gene_pubmed" (:linkname (first result))))
          (is (= ["111" "222"] (:ids (first result))))))))

  (testing "elink returns empty vector when no linksets"
    (let [client (mock-eutils-client)]
      (with-redefs [eu/request (fn [_ _ _] {:linksets [{}]})]
        (is (= [] (eu/elink client "gene" "672")))))))

(deftest einfo-list-databases
  (testing "einfo with no db returns list of database names"
    (let [client (mock-eutils-client)
          fake-response {:einforesult {:dblist ["gene" "pubmed" "taxonomy"]}}]
      (with-redefs [eu/request (fn [_ _ _] fake-response)]
        (is (= ["gene" "pubmed" "taxonomy"] (eu/einfo client)))))))

(deftest einfo-database-details
  (testing "einfo with db returns database info"
    (let [client (mock-eutils-client)
          fake-response {:einforesult {:dbinfo [{:dbname "gene"
                                                 :count "1000000"
                                                 :fieldlist [{:name "ALL"}]}]}}]
      (with-redefs [eu/request (fn [_ _ _] fake-response)]
        (let [result (eu/einfo client "gene")]
          (is (= "gene" (:dbname result)))
          (is (= "1000000" (:count result))))))))

;; --- bridge unit tests ---

(deftest bridge-search-returns-tagged-results
  (testing "search returns vector with pagination metadata"
    (with-redefs [eu/esearch (fn [_ _ _ _]
                               {:ids ["672"] :count 1 :retmax 20 :retstart 0})
                  eu/esummary (fn [_ _ _]
                                [{:uid "672" :name "BRCA1" :description "BRCA1 DNA repair"}])]
      (let [results (bridge/search (mock-eutils-client) "gene" "BRCA1")]
        (is (vector? results))
        (is (= 1 (count results)))
        (is (= 1 (:ncbi/total-count (meta results))))
        (is (= 20 (:ncbi/retmax (meta results))))
        (is (= :ncbi.eutils/results (:ncbi/type (meta results))))
        (is (= "gene" (:ncbi.eutils/db (meta results))))))))

(deftest bridge-search-result-has-eutils-metadata
  (testing "individual search results carry eutils type metadata"
    (with-redefs [eu/esearch (fn [_ _ _ _]
                               {:ids ["672"] :count 1 :retmax 20 :retstart 0})
                  eu/esummary (fn [_ _ _]
                                [{:uid "672" :name "BRCA1"}])]
      (let [results (bridge/search (mock-eutils-client) "gene" "BRCA1")
            result (first results)]
        (is (= :ncbi.eutils/result (:ncbi/type (meta result))))
        (is (= "gene" (:ncbi.eutils/db (meta result))))))))

(deftest bridge-search-empty-results
  (testing "search with no results returns empty vector with metadata"
    (with-redefs [eu/esearch (fn [_ _ _ _]
                               {:ids [] :count 0 :retmax 20 :retstart 0})
                  eu/esummary (fn [_ _ _] [])]
      (let [results (bridge/search (mock-eutils-client) "gene" "nonexistent_xyz")]
        (is (vector? results))
        (is (empty? results))
        (is (= 0 (:ncbi/total-count (meta results))))))))

(deftest bridge-datafy-exposes-link-keys
  (testing "datafy on a gene result exposes elink keys and datasets-entity"
    (with-redefs [eu/esearch (fn [_ _ _ _]
                               {:ids ["672"] :count 1 :retmax 20 :retstart 0})
                  eu/esummary (fn [_ _ _]
                                [{:uid "672" :name "BRCA1"}])
                  eu/elink-available (fn [_ _ _]
                                       [{:linkname "gene_pubmed" :dbto "pubmed"}
                                        {:linkname "gene_nuccore" :dbto "nuccore"}])]
      (let [results (bridge/search (mock-eutils-client) "gene" "BRCA1")
            datafied (datafy (first results))]
        (is (contains? datafied :ncbi.nav/datasets-entity))
        (is (= :deferred (:ncbi.nav/datasets-entity datafied)))
        (is (contains? datafied :ncbi.elink/gene_pubmed))
        (is (contains? datafied :ncbi.elink/gene_nuccore))))))

;; --- Bridge nav hop tests (R4: verify actual nav resolution, not just key presence) ---

(deftest bridge-nav-datasets-entity-test
  (testing "nav :ncbi.nav/datasets-entity resolves into a Datasets entity"
    (let [fake-gene {:gene_id "672" :symbol "BRCA1" :tax_id "9606"}]
      (with-redefs [eu/esearch (fn [_ _ _ _]
                                 {:ids ["672"] :count 1 :retmax 20 :retstart 0})
                    eu/esummary (fn [_ _ _]
                                  [{:uid "672" :name "BRCA1"}])
                    eu/elink-available (fn [_ _ _]
                                         [{:linkname "gene_pubmed" :dbto "pubmed"}])
                    ds/fetch-one (fn [_ op params entity-type]
                                   (is (= :gene-reports-by-id op))
                                   (is (= {:gene_ids [672]} params))
                                   (is (= :ncbi/gene entity-type))
                                   (with-meta fake-gene {:ncbi/type :ncbi/gene}))]
        (let [results  (bridge/search (mock-eutils-client) "gene" "BRCA1")
              result   (first results)
              datafied (datafy result)
              gene     (nav datafied :ncbi.nav/datasets-entity :deferred)]
          (is (some? gene))
          (is (= "672" (:gene_id gene)))
          (is (= "BRCA1" (:symbol gene)))
          (is (= :ncbi/gene (:ncbi/type (meta gene)))))))))

(deftest bridge-nav-elink-follow-test
  (testing "nav :ncbi.elink/* follows a cross-database link via eutils"
    (with-redefs [eu/esearch (fn [_ _ _ _]
                               {:ids ["672"] :count 1 :retmax 20 :retstart 0})
                  eu/esummary (fn [_ db _]
                                (if (= db "pubmed")
                                  [{:uid "12345" :title "A relevant paper"}
                                   {:uid "67890" :title "Another paper"}]
                                  [{:uid "672" :name "BRCA1"}]))
                  eu/elink-available (fn [_ _ _]
                                       [{:linkname "gene_pubmed" :dbto "pubmed"}])
                  eu/elink (fn [_ _ _ _]
                              [{:dbto "pubmed" :linkname "gene_pubmed"
                                :ids ["12345" "67890"]}])]
      (let [results  (bridge/search (mock-eutils-client) "gene" "BRCA1")
            result   (first results)
            datafied (datafy result)
            pubmed   (nav datafied :ncbi.elink/gene_pubmed :deferred)]
        (is (some? pubmed))
        (is (= 2 (count pubmed)))
        (is (= "12345" (:uid (first pubmed))))
        (is (= 2 (:ncbi.elink/total-count (meta pubmed))))
        (is (= "gene_pubmed" (:ncbi.elink/linkname (meta pubmed))))))))
