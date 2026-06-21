(ns ncbi-api-client.core-test
  (:require [clojure.datafy :refer [datafy]]
            [clojure.test :refer [deftest is testing]]
            [martian.core :as martian]
            [martian.interceptors :as interceptors]
            [martian.test :as mt]
            [martian.vcr :as vcr]
            [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datafy :as d]))

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
        result (d/fetch client :taxonomy-data-report {:taxons ["9606"]} :ncbi/taxonomy)]
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
