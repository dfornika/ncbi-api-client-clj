(ns ncbi-api-client.core-test
  (:require [clojure.datafy :refer [datafy nav]]
            [clojure.test :refer [deftest is testing]]
            [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.package :as pkg]
            [ncbi-api-client.test-support :as ts])
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip ZipEntry ZipOutputStream]))

(def ^:private recorded
  "Recorded answers (test-resources/fixtures/datasets/), and the URLs they
  were recorded from (through Martian, before it was dropped)."
  {:taxonomy-data-report  "https://api.ncbi.nlm.nih.gov/datasets/v2/taxonomy/taxon/9606/dataset_report"
   :gene-reports-by-id    "https://api.ncbi.nlm.nih.gov/datasets/v2/gene/id/672"
   :genome-dataset-report "https://api.ncbi.nlm.nih.gov/datasets/v2/genome/accession/GCF_000001405.40/dataset_report"})

(defn- playback-client
  ([] (playback-client nil))
  ([requests]
   (ts/stub-client (into {} (for [op (keys recorded)]
                              [op {:body (ts/fixture (str "datasets/" (name op) ".json"))}]))
                   requests)))

(deftest playback-urls-match-recordings-test
  (testing "requests go to the URLs the fixtures were recorded from"
    (let [requests (atom [])
          client   (playback-client requests)]
      (ncbi/taxonomy client "9606")
      (ncbi/gene client 672)
      (ncbi/assembly client "GCF_000001405.40")
      (is (= (vals (select-keys recorded [:taxonomy-data-report :gene-reports-by-id :genome-dataset-report]))
             (map :url @requests))))))

(deftest taxonomy-lookup-test
  (let [client (playback-client)
        human  (ncbi/taxonomy client "9606")]
    (testing "returns taxonomy data for a single taxon"
      (is (= 9606 (:tax_id human)))
      (is (= "Homo sapiens" (get-in human [:current_scientific_name :name])))
      (is (= "SPECIES" (:rank human))))

    (testing "tags entity with datafy/nav metadata"
      (is (some? (meta human)))
      (is (= :ncbi/taxonomy (:ncbi/type (meta human)))))))

(deftest taxonomy-datafy-test
  (let [client   (playback-client)
        human    (ncbi/taxonomy client "9606")
        datafied (datafy human)]
    (testing "datafy exposes navigable relationship keys"
      (is (contains? datafied :ncbi.nav/assemblies))
      (is (contains? datafied :ncbi.nav/children))
      (is (contains? datafied :ncbi.nav/lineage))
      (is (= :deferred (:ncbi.nav/assemblies datafied))))))

(deftest gene-lookup-test
  (let [client (playback-client)
        brca1  (ncbi/gene client 672)]
    (testing "returns gene data for a single gene ID"
      (is (= "672" (:gene_id brca1)))
      (is (= "BRCA1" (:symbol brca1)))
      (is (= :ncbi/gene (:ncbi/type (meta brca1)))))))

(deftest assembly-lookup-test
  (let [client   (playback-client)
        grch38   (ncbi/assembly client "GCF_000001405.40")]
    (testing "returns assembly data for an accession"
      (is (= "GCF_000001405.40" (:accession grch38)))
      (is (= 9606 (get-in grch38 [:organism :tax_id])))
      (is (= :ncbi/assembly (:ncbi/type (meta grch38)))))))

(deftest fetch-returns-tagged-vector-test
  (let [client (playback-client)
        result (ds/fetch client :taxonomy-data-report {:taxons ["9606"]} :ncbi/taxonomy)]
    (testing "fetch returns a vector with pagination metadata"
      (is (vector? result))
      (is (= 1 (count result)))
      (is (= 1 (:ncbi/total-count (meta result))))
      (is (= :taxonomy-data-report (:ncbi/operation (meta result)))))))

(deftest respond-with-constant-test
  (testing "a stub client answers without the network"
    (let [fake (ts/stub-client
                {:taxonomy-data-report
                 {:status 200
                  :body {:reports [{:taxonomy {:tax_id 42
                                               :current_scientific_name {:name "Test organism"}
                                               :rank "SPECIES"}}]
                         :total_count 1}}})
          result (ncbi/taxonomy fake "42")]
      (is (= 42 (:tax_id result)))
      (is (= "Test organism" (get-in result [:current_scientific_name :name]))))))

(deftest taxonomy-collection-test
  (let [client (playback-client)
        result (ncbi/taxonomy client ["9606"])]
    (testing "collection argument returns a tagged vector"
      (is (vector? result))
      (is (= 1 (count result)))
      (is (= 9606 (:tax_id (first result))))
      (is (= :ncbi/taxonomy (:ncbi/type (meta (first result))))))))

(deftest facade-esummary-test
  (testing "core/esummary runs eutils/esummary"
    (let [client (ts/stub-client {:esummary {:body {:result {:uids ["672"]
                                                             :672 {:uid "672" :name "BRCA1"}}}}})
          result (ncbi/esummary client "gene" ["672"])]
      (is (= 1 (count result)))
      (is (= "672" (:uid (first result)))))))

(deftest facade-elink-test
  (testing "core/elink runs eutils/elink"
    (let [client (ts/stub-client {:elink {:body {:linksets [{:linksetdbs [{:dbto "pubmed"
                                                                           :linkname "gene_pubmed"
                                                                           :links ["111" "222"]}]}]}}})
          result (ncbi/elink client "gene" "672")]
      (is (= 1 (count result)))
      (is (= "pubmed" (:dbto (first result)))))))

(deftest facade-elink-available-test
  (testing "core/elink-available runs eutils/elink-available"
    (let [client (ts/stub-client {:elink {:body {:linksets [{:idchecklist
                                                             {:idlinksets [{:linkinfos [{:linkname "gene_pubmed"
                                                                                         :dbto "pubmed"
                                                                                         :menutag "PubMed"}]}]}}]}}})
          result (ncbi/elink-available client "gene" "672")]
      (is (= 1 (count result)))
      (is (= "gene_pubmed" (:linkname (first result)))))))

(deftest download-binary-response-test
  (testing "download endpoints ask for application/zip and read bytes"
    (let [requests (atom [])
          client   (ts/stub-client {:download-gene-package
                                    {:body (byte-array [0x50 0x4B 0x03 0x04])}}
                                   requests)
          body     (ds/call client :download-gene-package {:gene-ids [947170]})
          request  (first @requests)]
      (is (= :byte-array (:as request)))
      (is (= "application/zip" (get-in request [:headers "accept"])))
      (is (bytes? body)))))

;; --- Package tests ---

(defn- make-test-zip
  "Creates a zip byte array with the given entries {path -> content-string}."
  [entries]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [zos (ZipOutputStream. baos)]
      (doseq [[path content] entries]
        (.putNextEntry zos (ZipEntry. path))
        (.write zos (.getBytes ^String content "UTF-8"))
        (.closeEntry zos)))
    (.toByteArray baos)))

(deftest parse-fasta-test
  (testing "parses single-sequence FASTA"
    (let [text ">NC_000913.3 recA [organism=E. coli]\nATGGCTATC\nGACGAAAAC\n"
          result (pkg/parse-fasta text :iupac-nucleic-acids)]
      (is (= 1 (count result)))
      (is (= "NC_000913.3" (:acc (first result))))
      (is (= "recA [organism=E. coli]" (:description (first result))))
      (is (= "ATGGCTATCGACGAAAAC" (:sequence (first result))))
      (is (= :iupac-nucleic-acids (:alphabet (first result))))))

  (testing "parses multi-sequence FASTA"
    (let [text ">seq1 first\nAAAA\n>seq2 second\nCCCC\nGGGG\n"
          result (pkg/parse-fasta text :iupac-nucleic-acids)]
      (is (= 2 (count result)))
      (is (= "seq1" (:acc (first result))))
      (is (= "AAAA" (:sequence (first result))))
      (is (= "seq2" (:acc (second result))))
      (is (= "CCCCGGGG" (:sequence (second result))))))

  (testing "parses protein FASTA"
    (let [text ">NP_417179.1 recA\nMAIDENKQ\n"
          result (pkg/parse-fasta text :iupac-amino-acids)]
      (is (= :iupac-amino-acids (:alphabet (first result)))))))

(deftest make-package-test
  (let [catalog  {"genes" {"files" [{"filePath" "gene.fna"
                                     "fileType" "GENOMIC_NUCLEOTIDE_FASTA"}
                                    {"filePath" "protein.faa"
                                     "fileType" "PROTEIN_FASTA"}
                                    {"filePath" "data_report.jsonl"
                                     "fileType" "DATA_REPORT"}]}
                  "apiVersion" "V2"}
        zip-bytes (make-test-zip
                   {"ncbi_dataset/data/dataset_catalog.json"
                    (cheshire.core/generate-string catalog)
                    "ncbi_dataset/data/gene.fna"
                    ">NC_000913.3 recA\nATGGCTATC\n"
                    "ncbi_dataset/data/protein.faa"
                    ">NP_417179.1 recA\nMAIDENKQ\n"
                    "ncbi_dataset/data/data_report.jsonl"
                    "{\"geneId\":\"947170\"}\n"})
        pkg       (pkg/make-package zip-bytes)]

    (testing "package has catalog and nav keys derived from catalog"
      (is (= catalog (:catalog pkg)))
      (is (= :deferred (:ncbi.nav/gene-fasta pkg)))
      (is (= :deferred (:ncbi.nav/protein-fasta pkg)))
      (is (= :deferred (:ncbi.nav/data-report pkg)))
      (is (= :ncbi/package (:ncbi/type (meta pkg)))))

    (testing "nav into gene FASTA returns parsed sequences"
      (let [seqs (nav (datafy pkg) :ncbi.nav/gene-fasta :deferred)]
        (is (= 1 (count seqs)))
        (is (= "NC_000913.3" (:acc (first seqs))))
        (is (= "ATGGCTATC" (:sequence (first seqs))))
        (is (= :iupac-nucleic-acids (:alphabet (first seqs))))))

    (testing "nav into protein FASTA returns parsed sequences"
      (let [seqs (nav (datafy pkg) :ncbi.nav/protein-fasta :deferred)]
        (is (= "NP_417179.1" (:acc (first seqs))))
        (is (= :iupac-amino-acids (:alphabet (first seqs))))))

    (testing "nav into data report returns raw text"
      (let [report (nav (datafy pkg) :ncbi.nav/data-report :deferred)]
        (is (string? report))
        (is (clojure.string/includes? report "947170"))))))

;; --- Nav hop tests (R4: verify actual navigation, not just metadata presence) ---

(deftest nav-taxonomy-to-assemblies-test
  (testing "nav :ncbi.nav/assemblies fetches assemblies for a taxon"
    (let [fake (ts/stub-client
                {:taxonomy-data-report
                 {:status 200
                  :body {:reports [{:taxonomy {:tax_id 9606
                                               :current_scientific_name {:name "Homo sapiens"}
                                               :rank "SPECIES"
                                               :parents [9605]
                                               :children [741158]}}]
                         :total_count 1}}
                 :genome-dataset-reports-by-taxon
                 {:status 200
                  :body {:reports [{:accession "GCF_000001405.40"
                                    :organism {:tax_id 9606}
                                    :assembly_info {:assembly_level "Chromosome"}}]
                         :total_count 1}}})
          human      (ncbi/taxonomy fake "9606")
          assemblies (nav (datafy human) :ncbi.nav/assemblies :deferred)]
      (is (seq assemblies))
      (is (= "GCF_000001405.40" (:accession (first assemblies))))
      (is (= :ncbi/assembly (:ncbi/type (meta (first assemblies))))))))

(deftest nav-gene-to-organism-test
  (testing "nav :ncbi.nav/organism fetches taxonomy for a gene's organism"
    (let [fake (ts/stub-client
                {:gene-reports-by-id
                 {:status 200
                  :body {:reports [{:gene {:gene_id "672"
                                           :symbol "BRCA1"
                                           :tax_id "9606"}}]
                         :total_count 1}}
                 :taxonomy-data-report
                 {:status 200
                  :body {:reports [{:taxonomy {:tax_id 9606
                                               :current_scientific_name {:name "Homo sapiens"}
                                               :rank "SPECIES"}}]
                         :total_count 1}}})
          brca1    (ncbi/gene fake 672)
          organism (nav (datafy brca1) :ncbi.nav/organism :deferred)]
      (is (some? organism))
      (is (= 9606 (:tax_id organism)))
      (is (= :ncbi/taxonomy (:ncbi/type (meta organism)))))))

(deftest nav-multi-hop-test
  (testing "two-hop navigation: gene → organism → assemblies"
    (let [fake (ts/stub-client
                {:gene-reports-by-id
                 {:status 200
                  :body {:reports [{:gene {:gene_id "672"
                                           :symbol "BRCA1"
                                           :tax_id "9606"}}]
                         :total_count 1}}
                 :taxonomy-data-report
                 {:status 200
                  :body {:reports [{:taxonomy {:tax_id 9606
                                               :current_scientific_name {:name "Homo sapiens"}
                                               :rank "SPECIES"
                                               :parents [9605]
                                               :children [741158]}}]
                         :total_count 1}}
                 :genome-dataset-reports-by-taxon
                 {:status 200
                  :body {:reports [{:accession "GCF_000001405.40"
                                    :organism {:tax_id 9606}}]
                         :total_count 1}}})
          gene       (ncbi/gene fake 672)
          organism   (nav (datafy gene) :ncbi.nav/organism :deferred)
          assemblies (nav (datafy organism) :ncbi.nav/assemblies :deferred)]
      (is (= :ncbi/taxonomy (:ncbi/type (meta organism))))
      (is (seq assemblies))
      (is (= "GCF_000001405.40" (:accession (first assemblies))))
      (is (= :ncbi/assembly (:ncbi/type (meta (first assemblies))))))))

(deftest nav-next-page-test
  (testing "nav :ncbi.nav/next-page fetches the next page of results"
    (let [call-count (atom 0)
          fake       (ts/stub-client
                      {:taxonomy-data-report
                       (fn [_]
                         (let [n (swap! call-count inc)]
                           (if (= n 1)
                             {:status 200
                              :body {:reports [{:taxonomy {:tax_id 9606
                                                           :current_scientific_name {:name "Homo sapiens"}}}]
                                     :total_count 2
                                     :next_page_token "page2token"}}
                             {:status 200
                              :body {:reports [{:taxonomy {:tax_id 10090
                                                           :current_scientific_name {:name "Mus musculus"}}}]
                                     :total_count 2}})))})
          page1 (ds/fetch fake :taxonomy-data-report {:taxons ["9606" "10090"]} :ncbi/taxonomy)]
      (is (= 1 (count page1)))
      (is (= "page2token" (:ncbi/next-page-token (meta page1))))
      (let [datafied (datafy page1)
            page2    (nav datafied :ncbi.nav/next-page :deferred)]
        (is (= :deferred (:ncbi.nav/next-page (meta datafied))))
        (is (vector? page2))
        (is (= 1 (count page2)))
        (is (= 10090 (:tax_id (first page2))))
        (is (nil? (:ncbi/next-page-token (meta page2))))))))

(deftest fetch-all-pagination-test
  (testing "fetch-all lazily paginates through all pages"
    (let [call-count (atom 0)
          fake       (ts/stub-client
                      {:taxonomy-data-report
                       (fn [_]
                         (let [n (swap! call-count inc)]
                           (if (= n 1)
                             {:status 200
                              :body {:reports [{:taxonomy {:tax_id 9606
                                                           :current_scientific_name {:name "Homo sapiens"}}}]
                                     :total_count 2
                                     :next_page_token "page2"}}
                             {:status 200
                              :body {:reports [{:taxonomy {:tax_id 10090
                                                           :current_scientific_name {:name "Mus musculus"}}}]
                                     :total_count 2}})))})
          all-taxa (vec (ds/fetch-all fake :taxonomy-data-report
                                      {:taxons ["9606" "10090"]} :ncbi/taxonomy))]
      (is (= 2 (count all-taxa)))
      (is (= 9606 (:tax_id (first all-taxa))))
      (is (= 10090 (:tax_id (second all-taxa))))
      (is (= :ncbi/taxonomy (:ncbi/type (meta (first all-taxa)))))
      (is (= 2 @call-count)))))

;; --- Edges as plans ---

(def ^:private human-taxon
  {:taxonomy-data-report
   {:body {:reports [{:taxonomy {:tax_id 9606 :rank "SPECIES" :parents [9605] :children [741158]}}]
           :total_count 1}}})

(deftest datafy-makes-no-requests-test
  (testing "datafy on an entity shows its nav keys without asking NCBI"
    (let [requests (atom [])
          human    (ncbi/taxonomy (ts/stub-client human-taxon requests) "9606")]
      (datafy human)
      (is (= 1 (count @requests))))))

(deftest edges-return-one-page-test
  (testing "an edge returns one page, whose metadata leads to the next"
    (let [requests (atom [])
          client   (ts/stub-client
                    (merge human-taxon
                           {:genome-dataset-reports-by-taxon
                            (fn [http-request]
                              {:body (if (re-find #"page_token=t2" (:url http-request))
                                       {:reports [{:accession "GCA_2"}] :total_count 2}
                                       {:reports [{:accession "GCA_1"}] :total_count 2 :next_page_token "t2"})})})
                    requests)
          page1    (nav (datafy (ncbi/taxonomy client "9606")) :ncbi.nav/assemblies :deferred)
          next     (:async.nav/next (meta page1))]
      (is (= ["GCA_1"] (map :accession page1)))
      (is (= 2 (count @requests)) "only the first page was asked for")
      (is (= 2 (:async.nav/total (meta page1))))
      (testing "the next page is a deferred value: plain data, with a fetch function and a stable key"
        (is (= :ncbi.nav/next-page (:ncbi/deferred next)))
        (is (= "t2" (get-in next [:ncbi/request :query "page_token"])))
        (is (= [:ncbi.nav/next-page (:ncbi/request next)] (:async.nav/key (meta next))))
        (is (= ["GCA_2"] (map :accession ((:async.nav/fetch (meta next))))))
        (is (= 3 (count @requests))))
      (testing "nav :ncbi.nav/next-page follows it too"
        (let [page2 (nav (datafy page1) :ncbi.nav/next-page :deferred)]
          (is (= ["GCA_2"] (map :accession page2)))
          (is (nil? (:async.nav/next (meta page2)))))))))

(deftest deferred-value-test
  (testing "a deferred value describes what it will fetch, and never holds the API key"
    (let [client (assoc (ts/stub-client human-taxon) :api-key (constantly "secret"))
          human  (ncbi/taxonomy client "9606")
          plan   ((:plan (get (:nav-edges client) [:ncbi/taxonomy :ncbi.nav/assemblies])) client human)
          d      (ncbi-api-client.nav/deferred client :ncbi.nav/assemblies "assemblies of taxonomy 9606" plan)]
      (is (= {:ncbi/deferred :ncbi.nav/assemblies
              :ncbi/describe "assemblies of taxonomy 9606"
              :ncbi/request  (ncbi-api-client.request/datasets :genome-dataset-reports-by-taxon {:taxons ["9606"]})}
             d))
      (is (fn? (:async.nav/fetch (meta d))))
      (is (not (clojure.string/includes? (pr-str d (:async.nav/key (meta d))) "secret"))))))

(deftest image-edge-test
  (testing "a taxon without an image navigates to nil, not an error"
    (let [client (ts/stub-client (merge human-taxon
                                        {:taxonomy-image-metadata {:status 404 :body "{\"message\": \"Not Found\"}"}}))]
      (is (nil? (nav (datafy (ncbi/taxonomy client "9606")) :ncbi.nav/image :deferred))))))

(deftest package-edge-test
  (testing "nav :ncbi.nav/package downloads the gene's package"
    (let [requests (atom [])
          client   (ts/stub-client {:gene-reports-by-id {:body {:reports [{:gene {:gene_id "672"}}] :total_count 1}}
                                    :download-gene-package {:body (make-test-zip {"ncbi_dataset/data/dataset_catalog.json" "{}"})}}
                                   requests)
          pkg      (nav (datafy (ncbi/gene client 672)) :ncbi.nav/package :deferred)]
      (is (= :ncbi/package (:ncbi/type (meta pkg))))
      (is (= :download-gene-package (get-in (last @requests) [:ncbi/request :op]))))))
