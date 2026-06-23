(ns ncbi-api-client.core-test
  (:require [clojure.datafy :refer [datafy nav]]
            [clojure.test :refer [deftest is testing]]
            [martian.core :as martian]
            [martian.interceptors :as interceptors]
            [martian.test :as mt]
            [martian.vcr :as vcr]
            [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.package :as pkg])
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip ZipEntry ZipOutputStream]))

(defn- playback-client []
  (let [c (ncbi/connect)]
    (update c :interceptors
            #(interceptors/inject %
                                  (vcr/playback {:store {:kind :file
                                                         :root-dir "test-resources/vcr"}
                                                 :on-missing-response :throw-error})
                                  :before :martian.hato/perform-request))))

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
  (testing "martian-test respond-with for unit testing without VCR"
    (let [client (ncbi/connect)
          fake   (mt/respond-with client
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
  (testing "core/esummary delegates to eutils/esummary"
    (let [client {:eutils {:http-client nil :api-key "fake" :tool "test" :email "t@t.com"}}
          fake-response {:result {:uids ["672"]
                                  :672 {:uid "672" :name "BRCA1"}}}]
      (with-redefs [ncbi-api-client.eutils/request (fn [_ _ _] fake-response)]
        (let [result (ncbi/esummary client "gene" ["672"])]
          (is (= 1 (count result)))
          (is (= "672" (:uid (first result)))))))))

(deftest facade-elink-test
  (testing "core/elink delegates to eutils/elink"
    (let [client {:eutils {:http-client nil :api-key "fake" :tool "test" :email "t@t.com"}}
          fake-response {:linksets [{:linksetdbs [{:dbto "pubmed"
                                                   :linkname "gene_pubmed"
                                                   :links ["111" "222"]}]}]}]
      (with-redefs [ncbi-api-client.eutils/request (fn [_ _ _] fake-response)]
        (let [result (ncbi/elink client "gene" "672")]
          (is (= 1 (count result)))
          (is (= "pubmed" (:dbto (first result)))))))))

(deftest facade-elink-available-test
  (testing "core/elink-available delegates to eutils/elink-available"
    (let [client {:eutils {:http-client nil :api-key "fake" :tool "test" :email "t@t.com"}}
          fake-response {:linksets [{:idchecklist
                                     {:idlinksets [{:linkinfos [{:linkname "gene_pubmed"
                                                                 :dbto "pubmed"
                                                                 :menutag "PubMed"}]}]}}]}]
      (with-redefs [ncbi-api-client.eutils/request (fn [_ _ _] fake-response)]
        (let [result (ncbi/elink-available client "gene" "672")]
          (is (= 1 (count result)))
          (is (= "gene_pubmed" (:linkname (first result)))))))))

(deftest download-binary-response-test
  (testing "download endpoints set :as :byte-array and Accept: application/zip"
    (let [captured-request (atom nil)
          client (ncbi/connect)
          fake   (mt/respond-with client
                                  (fn [ctx]
                                    (reset! captured-request (:request ctx))
                                    {:status 200
                                     :body (byte-array [0x50 0x4B 0x03 0x04])}))
          response (martian/response-for fake :download-gene-package
                                         {:gene-ids [947170]})]
      (is (= :byte-array (:as @captured-request)))
      (is (= "application/zip" (get-in @captured-request [:headers "Accept"])))
      (is (bytes? (:body response))))))

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
