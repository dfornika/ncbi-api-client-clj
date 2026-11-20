(ns ncbi-api-client.eutils-test
  (:require [clojure.datafy :refer [datafy nav]]
            [clojure.test :refer [deftest is testing]]
            [ncbi-api-client.bridge :as bridge]
            [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.eutils :as eu]
            [ncbi-api-client.request :as req]
            [ncbi-api-client.runner :as runner]
            [ncbi-api-client.test-support :as ts]))

(defn- form-of [http-request]
  (get-in http-request [:ncbi/request :form]))

;; --- Requests ---

(deftest eutils-requests-are-posted-forms
  (testing "a request is a POSTed form asking for JSON"
    (let [r (req/eutils :esearch {:db "gene" :term "BRCA1 human" :retmax 5})]
      (is (= :post (:method r)))
      (is (= "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi" (:url r)))
      (is (= {"db" "gene" "term" "BRCA1 human" "retmax" "5" "retmode" "json"} (:form r)))
      (is (= :json (:as r)))))

  (testing "nil options are left out"
    (is (= {"db" "gene" "term" "x" "retmode" "json"}
           (:form (:request (eu/esearch "gene" "x" {:retmax nil}))))))

  (testing "the runner adds tool, email and the API key to the form, not the description"
    (let [requests (atom [])
          client   (assoc (ts/stub-client {:esearch {:body {:esearchresult {:idlist [] :count "0"}}}} requests)
                          :api-key (constantly "secret") :tool "test" :email "t@example.com")]
      (runner/run client (eu/esearch "gene" "x"))
      (let [http (first @requests)]
        (is (= :post (:method http)))
        (is (= "application/x-www-form-urlencoded" (get-in http [:headers "content-type"])))
        (is (= "api_key=secret&db=gene&email=t%40example.com&retmode=json&term=x&tool=test" (:body http)))
        (is (not (contains? (form-of http) "api_key")))))))

;; --- Reading answers ---

(deftest esearch-parses-response
  (testing "esearch extracts ids, count, retmax, retstart from response"
    (let [client (ts/stub-client {:esearch {:body {:esearchresult {:idlist ["672" "675"]
                                                                   :count "2"
                                                                   :retmax "20"
                                                                   :retstart "0"}}}})
          result (runner/run client (eu/esearch "gene" "BRCA1"))]
      (is (= ["672" "675"] (:ids result)))
      (is (= 2 (:count result)))
      (is (= 20 (:retmax result)))
      (is (= 0 (:retstart result))))))

(deftest esummary-parses-response
  (testing "esummary returns vector of summaries keyed by uid"
    (let [result (eu/read-esummary {:result {:uids ["672"]
                                             :672 {:uid "672" :name "BRCA1"}}})]
      (is (= 1 (count result)))
      (is (= "672" (:uid (first result))))
      (is (= "BRCA1" (:name (first result))))))

  (testing "esummary returns empty vector when no uids in response"
    (is (= [] (eu/read-esummary {:result {}})))))

(deftest elink-parses-linksets
  (testing "elink extracts dbto, linkname, and ids from response"
    (let [result (eu/read-elink {:linksets [{:linksetdbs [{:dbto "pubmed"
                                                           :linkname "gene_pubmed"
                                                           :links ["111" "222"]}]}]})]
      (is (= 1 (count result)))
      (is (= "pubmed" (:dbto (first result))))
      (is (= "gene_pubmed" (:linkname (first result))))
      (is (= ["111" "222"] (:ids (first result))))))

  (testing "elink returns empty vector when no linksets"
    (is (= [] (eu/read-elink {:linksets [{}]})))))

(deftest einfo-list-databases
  (testing "einfo with no db returns list of database names"
    (let [client (ts/stub-client {:einfo {:body {:einforesult {:dblist ["gene" "pubmed" "taxonomy"]}}}})]
      (is (= ["gene" "pubmed" "taxonomy"] (ncbi/einfo client))))))

(deftest einfo-database-details
  (testing "einfo with db returns database info"
    (let [client (ts/stub-client {:einfo {:body {:einforesult {:dbinfo [{:dbname "gene"
                                                                         :count "1000000"
                                                                         :fieldlist [{:name "ALL"}]}]}}}})
          result (ncbi/einfo client "gene")]
      (is (= "gene" (:dbname result)))
      (is (= "1000000" (:count result))))))

(deftest search-plan-chains-esearch-and-esummary
  (testing "eutils/search is one plan of two requests"
    (let [requests (atom [])
          client   (ts/stub-client {:esearch  {:body {:esearchresult {:idlist ["672"] :count "1"}}}
                                    :esummary {:body {:result {:uids ["672"] :672 {:uid "672"}}}}}
                                   requests)]
      (is (= {:count 1 :summaries [{:uid "672"}]} (runner/run client (eu/search "gene" "BRCA1"))))
      (is (= [:esearch :esummary] (map #(get-in % [:ncbi/request :op]) @requests))))))

;; --- bridge ---

(def ^:private brca1-search
  {:esearch  {:body {:esearchresult {:idlist ["672"] :count "1" :retmax "20" :retstart "0"}}}
   :esummary {:body {:result {:uids ["672"] :672 {:uid "672" :name "BRCA1" :description "BRCA1 DNA repair"}}}}})

(defn- elink-stub
  "Answers elink acheck with `available`, and other elinks with `links`."
  [available links]
  {:elink (fn [http-request]
            (if (= "acheck" (get (form-of http-request) "cmd"))
              {:body {:linksets [{:idchecklist {:idlinksets [{:linkinfos available}]}}]}}
              {:body {:linksets [{:linksetdbs links}]}}))})

(deftest bridge-search-returns-tagged-results
  (testing "search returns vector with pagination metadata"
    (let [results (bridge/search (ts/stub-client brca1-search) "gene" "BRCA1")]
      (is (vector? results))
      (is (= 1 (count results)))
      (is (= 1 (:ncbi/total-count (meta results))))
      (is (= 20 (:ncbi/retmax (meta results))))
      (is (= :ncbi.eutils/results (:ncbi/type (meta results))))
      (is (= "gene" (:ncbi.eutils/db (meta results)))))))

(deftest bridge-search-result-has-eutils-metadata
  (testing "individual search results carry eutils type metadata"
    (let [result (first (bridge/search (ts/stub-client brca1-search) "gene" "BRCA1"))]
      (is (= :ncbi.eutils/result (:ncbi/type (meta result))))
      (is (= "gene" (:ncbi.eutils/db (meta result)))))))

(deftest bridge-search-empty-results
  (testing "search with no results returns empty vector with metadata, without esummary"
    (let [client  (ts/stub-client {:esearch {:body {:esearchresult {:idlist [] :count "0"
                                                                    :retmax "20" :retstart "0"}}}})
          results (bridge/search client "gene" "nonexistent_xyz")]
      (is (vector? results))
      (is (empty? results))
      (is (= 0 (:ncbi/total-count (meta results)))))))

(deftest bridge-datafy-makes-no-requests
  (testing "datafy on a gene result shows the link table's links, available, and datasets-entity, without asking NCBI"
    (let [requests (atom [])
          client   (ts/stub-client brca1-search requests)
          result   (first (bridge/search client "gene" "BRCA1"))
          before   (count @requests)
          datafied (datafy result)]
      (is (= before (count @requests)))
      (is (= :deferred (:ncbi.nav/datasets-entity datafied)))
      (is (= :deferred (:ncbi.elink/gene_pubmed datafied)))
      (is (= :deferred (:ncbi.elink/gene_nuccore datafied)))
      (is (= :deferred (:ncbi.elink/available datafied)))))

  (testing "a database without Datasets has no datasets-entity"
    (let [client (ts/stub-client {:esearch  {:body {:esearchresult {:idlist ["1"] :count "1"}}}
                                  :esummary {:body {:result {:uids ["1"] :1 {:uid "1"}}}}})
          d      (datafy (first (bridge/search client "sra" "x")))]
      (is (not (contains? d :ncbi.nav/datasets-entity)))
      (is (= :deferred (:ncbi.elink/sra_bioproject d))))))

;; --- Bridge nav hops ---

(deftest bridge-nav-datasets-entity-test
  (testing "nav :ncbi.nav/datasets-entity resolves into a Datasets entity"
    (let [requests (atom [])
          client   (ts/stub-client (merge brca1-search
                                          {:gene-reports-by-id
                                           {:body {:reports [{:gene {:gene_id "672" :symbol "BRCA1" :tax_id "9606"}}]
                                                   :total_count 1}}})
                                   requests)
          gene     (nav (datafy (first (bridge/search client "gene" "BRCA1")))
                        :ncbi.nav/datasets-entity :deferred)]
      (is (= "BRCA1" (:symbol gene)))
      (is (= :ncbi/gene (:ncbi/type (meta gene))))
      (is (= "https://api.ncbi.nlm.nih.gov/datasets/v2/gene/id/672" (:url (last @requests)))))))

(deftest bridge-nav-elink-follow-test
  (testing "nav :ncbi.elink/* follows a cross-database link via eutils"
    (let [client (ts/stub-client
                  (merge brca1-search
                         (elink-stub [] [{:dbto "pubmed" :linkname "gene_pubmed" :links ["12345" "67890"]}])
                         {:esummary (fn [http-request]
                                      {:body (if (= "pubmed" (get (form-of http-request) "db"))
                                               {:result {:uids ["12345" "67890"]
                                                         :12345 {:uid "12345" :title "A relevant paper"}
                                                         :67890 {:uid "67890" :title "Another paper"}}}
                                               {:result {:uids ["672"] :672 {:uid "672" :name "BRCA1"}}})})}))
          pubmed (nav (datafy (first (bridge/search client "gene" "BRCA1")))
                      :ncbi.elink/gene_pubmed :deferred)]
      (is (= 2 (count pubmed)))
      (is (= "12345" (:uid (first pubmed))))
      (is (= 2 (:ncbi.elink/total-count (meta pubmed))))
      (is (= "gene_pubmed" (:ncbi.elink/linkname (meta pubmed))))
      (testing "and the records it reaches are navigable records of their own database"
        (is (= "pubmed" (:ncbi.eutils/db (meta (first pubmed)))))
        (is (= :deferred (:ncbi.elink/available (datafy (first pubmed)))))))))

(deftest bridge-nav-available-test
  (testing "nav :ncbi.elink/available asks for every link, and its keys follow them"
    (let [client    (ts/stub-client
                     (merge brca1-search
                            (elink-stub [{:linkname "gene_snp" :dbto "snp" :menutag "SNP Links"}]
                                        [{:dbto "snp" :linkname "gene_snp" :links ["1"]}])
                            {:esummary (fn [http-request]
                                         {:body (if (= "snp" (get (form-of http-request) "db"))
                                                  {:result {:uids ["1"] :1 {:uid "1" :snp_id 1}}}
                                                  {:result {:uids ["672"] :672 {:uid "672"}}})})}))
          available (nav (datafy (first (bridge/search client "gene" "BRCA1")))
                         :ncbi.elink/available :deferred)]
      (is (= {:ncbi.elink/gene_snp {:dbto "snp" :menutag "SNP Links"}} available))
      (is (= [{:uid "1" :snp_id 1}]
             (nav available :ncbi.elink/gene_snp (:ncbi.elink/gene_snp available)))))))

(deftest bridge-elink-empty-test
  (testing "a link with no records is an empty vector, with the link in its metadata"
    (let [client (ts/stub-client (merge brca1-search (elink-stub [] [])))
          r      (nav (datafy (first (bridge/search client "gene" "BRCA1"))) :ncbi.elink/gene_pubmed :deferred)]
      (is (= [] r))
      (is (= 0 (:ncbi.elink/total-count (meta r)))))))

(deftest bridge-search-pages-test
  (testing "search results say how many there are, and lead to the next page"
    (let [requests (atom [])
          client   (ts/stub-client
                    {:esearch  (fn [http-request]
                                 {:body {:esearchresult
                                         (if (= "2" (get (form-of http-request) "retstart"))
                                           {:idlist ["3"] :count "3" :retmax "2" :retstart "2"}
                                           {:idlist ["1" "2"] :count "3" :retmax "2" :retstart "0"})}})
                     :esummary (fn [http-request]
                                 (let [ids (clojure.string/split (get (form-of http-request) "id") #",")]
                                   {:body {:result (into {:uids ids} (map (fn [id] [(keyword id) {:uid id}])) ids)}}))}
                    requests)
          page1    (bridge/search client "gene" "x" {:retmax 2})
          page2    (nav (datafy page1) :ncbi.nav/next-page :deferred)]
      (is (= 3 (:async.nav/total (meta page1))))
      (is (= {:ncbi/deferred :ncbi.nav/next-page}
             (select-keys (:async.nav/next (meta page1)) [:ncbi/deferred])))
      (is (= ["3"] (map :uid page2)))
      (is (nil? (:async.nav/next (meta page2))))
      (testing "the next page's deferred value fetches the same page"
        (is (= page2 ((:async.nav/fetch (meta (:async.nav/next (meta page1)))))))))))
