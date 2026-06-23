(ns ncbi-api-client.datasets
  "Datasets API client: fetch, pagination, report extraction, and nav-edge definitions."
  (:require [clojure.core.protocols :as p]
            [martian.core :as martian]
            [ncbi-api-client.datafy :as datafy]
            [ncbi-api-client.package :as pkg]
            [ncbi-api-client.throttle :as throttle]))

;; --- Report extraction ---

(def ^:private report-extractors
  {:ncbi/taxonomy     :taxonomy
   :ncbi/assembly     identity
   :ncbi/gene         :gene
   :ncbi/biosample    identity
   :ncbi/sequence     identity
   :ncbi/gene-product :product
   :ncbi/annotation       :annotation
   :ncbi/virus            identity
   :ncbi/virus-annotation identity})

(defn- extract-report [entity-type report]
  (let [extractor (get report-extractors entity-type identity)]
    (if (keyword? extractor)
      (get report extractor)
      (extractor report))))

;; --- Forward declarations ---

(declare fetch fetch-one fetch-all)

;; --- ID extraction helpers ---

(defn- tax-id [coll] (some-> coll :tax_id str))
(defn- gene-id [coll] (some-> coll :gene_id str parse-long))

;; --- Navigation edge table ---

(def nav-edges
  "Data-driven navigation graph for the Datasets API.
   Each entry maps [entity-type nav-key] to an edge spec.

   Standard edges: {:id id-fn, :op operation-kw, :params params-fn, :fetch-fn fetch-fn, :type entity-type}
   Custom edges:   {:nav-fn (fn [client coll] ...)}"

  {;; --- taxonomy ---
   [:ncbi/taxonomy :ncbi.nav/assemblies]
   {:id tax-id, :op :genome-dataset-reports-by-taxon
    :params (fn [id] {:taxons [id]}), :fetch-fn fetch-all, :type :ncbi/assembly}

   [:ncbi/taxonomy :ncbi.nav/genes]
   {:id tax-id, :op :gene-dataset-reports-by-taxon
    :params (fn [id] {:taxon id}), :fetch-fn fetch-all, :type :ncbi/gene}

   [:ncbi/taxonomy :ncbi.nav/viruses]
   {:id tax-id, :op :virus-reports-by-taxon
    :params (fn [id] {:taxon id}), :fetch-fn fetch-all, :type :ncbi/virus}

   [:ncbi/taxonomy :ncbi.nav/children]
   {:nav-fn (fn [client coll]
              (let [ids (mapv str (:children coll))]
                (when (seq ids)
                  (fetch client :taxonomy-data-report {:taxons ids} :ncbi/taxonomy))))}

   [:ncbi/taxonomy :ncbi.nav/lineage]
   {:nav-fn (fn [client coll]
              (let [ids (mapv str (:parents coll))]
                (when (seq ids)
                  (fetch client :taxonomy-data-report {:taxons ids} :ncbi/taxonomy))))}

   [:ncbi/taxonomy :ncbi.nav/image]
   {:nav-fn (fn [client coll]
              (let [id (str (:tax_id coll))]
                (try
                  (:body (throttle/with-retry (:rate-limiter client)
                           #(martian/response-for client :taxonomy-image-metadata {:taxon id})))
                  (catch Exception _ nil))))}

   [:ncbi/taxonomy :ncbi.nav/links]
   {:nav-fn (fn [client coll]
              (let [id (str (:tax_id coll))]
                (dissoc (:body (throttle/with-retry (:rate-limiter client)
                                 #(martian/response-for client :taxonomy-links {:taxon id})))
                        :tax_id)))}

   ;; --- assembly ---
   [:ncbi/assembly :ncbi.nav/organism]
   {:id #(some-> % (get-in [:organism :tax_id]) str), :op :taxonomy-data-report
    :params (fn [id] {:taxons [id]}), :fetch-fn fetch-one, :type :ncbi/taxonomy}

   [:ncbi/assembly :ncbi.nav/annotations]
   {:id :accession, :op :genome-annotation-report
    :params (fn [id] {:accession id}), :fetch-fn fetch-all, :type :ncbi/annotation}

   [:ncbi/assembly :ncbi.nav/sequences]
   {:id :accession, :op :genome-sequence-report
    :params (fn [id] {:accession id}), :fetch-fn fetch-all, :type :ncbi/sequence}

   [:ncbi/assembly :ncbi.nav/biosample]
   {:id #(get-in % [:assembly_info :biosample :accession]), :op :bio-sample-dataset-report
    :params (fn [id] {:accessions [id]}), :fetch-fn fetch-one, :type :ncbi/biosample}

   [:ncbi/assembly :ncbi.nav/genes]
   {:nav-fn (fn [client coll]
              (fetch-all client :gene-dataset-reports-by-taxon
                         {:taxon (str (get-in coll [:organism :tax_id]))
                          :filters.reference_only true
                          :filters.assembly_accession (:accession coll)}
                         :ncbi/gene))}

   [:ncbi/assembly :ncbi.nav/package]
   {:nav-fn (fn [client coll]
              (pkg/download-assembly-package client (:accession coll)
                                             (when-let [a (get (meta coll) :ncbi/include-annotations)]
                                               {:include-annotations a})))}

   [:ncbi/assembly :ncbi.nav/links]
   {:nav-fn (fn [client coll]
              (get-in (throttle/with-retry (:rate-limiter client)
                        #(martian/response-for client :genome-links-by-accession
                                               {:accessions [(:accession coll)]}))
                      [:body :assembly_links]))}

   ;; --- gene ---
   [:ncbi/gene :ncbi.nav/organism]
   {:id tax-id, :op :taxonomy-data-report
    :params (fn [id] {:taxons [id]}), :fetch-fn fetch-one, :type :ncbi/taxonomy}

   [:ncbi/gene :ncbi.nav/orthologs]
   {:id gene-id, :op :gene-orthologs-by-id
    :params (fn [id] {:gene_id id}), :fetch-fn fetch-all, :type :ncbi/gene}

   [:ncbi/gene :ncbi.nav/products]
   {:id gene-id, :op :gene-product-reports-by-id
    :params (fn [id] {:gene-ids [id]}), :fetch-fn fetch-all, :type :ncbi/gene-product}

   [:ncbi/gene :ncbi.nav/assemblies]
   {:nav-fn (fn [client coll]
              (let [accessions (->> (:annotations coll)
                                    (map :assembly_accession)
                                    (remove nil?)
                                    distinct
                                    vec)]
                (when (seq accessions)
                  (fetch client :genome-dataset-report {:accessions accessions} :ncbi/assembly))))}

   [:ncbi/gene :ncbi.nav/package]
   {:nav-fn (fn [client coll]
              (let [id (gene-id coll)]
                (pkg/download-gene-package client id
                                           (when-let [a (get (meta coll) :ncbi/include-annotations)]
                                             {:include-annotations a}))))}

   [:ncbi/gene :ncbi.nav/links]
   {:nav-fn (fn [client coll]
              (get-in (throttle/with-retry (:rate-limiter client)
                        #(martian/response-for client :gene-links-by-id
                                               {:gene-ids [(gene-id coll)]}))
                      [:body :gene_links]))}

   ;; --- gene-product ---
   [:ncbi/gene-product :ncbi.nav/gene]
   {:id gene-id, :op :gene-reports-by-id
    :params (fn [id] {:gene_ids [id]}), :fetch-fn fetch-one, :type :ncbi/gene}

   ;; --- biosample ---
   [:ncbi/biosample :ncbi.nav/organism]
   {:id #(some-> % (get-in [:description :organism :tax_id]) str), :op :taxonomy-data-report
    :params (fn [id] {:taxons [id]}), :fetch-fn fetch-one, :type :ncbi/taxonomy}

   [:ncbi/biosample :ncbi.nav/assemblies]
   {:id :accession, :op :genome-dataset-reports-by-biosample-id
    :params (fn [id] {:biosample-ids [id]}), :fetch-fn fetch-all, :type :ncbi/assembly}

   ;; --- annotation ---
   [:ncbi/annotation :ncbi.nav/assembly]
   {:id #(get-in % [:annotations 0 :assembly_accession]), :op :genome-dataset-report
    :params (fn [id] {:accessions [id]}), :fetch-fn fetch-one, :type :ncbi/assembly}

   [:ncbi/annotation :ncbi.nav/gene]
   {:id #(some-> % :gene_id str parse-long), :op :gene-reports-by-id
    :params (fn [id] {:gene_ids [id]}), :fetch-fn fetch-one, :type :ncbi/gene}

   ;; --- sequence ---
   [:ncbi/sequence :ncbi.nav/assembly]
   {:id :assembly_accession, :op :genome-dataset-report
    :params (fn [id] {:accessions [id]}), :fetch-fn fetch-one, :type :ncbi/assembly}

   ;; --- virus ---
   [:ncbi/virus :ncbi.nav/taxonomy]
   {:id #(some-> % (get-in [:virus :tax_id]) str), :op :taxonomy-data-report
    :params (fn [id] {:taxons [id]}), :fetch-fn fetch-one, :type :ncbi/taxonomy}

   [:ncbi/virus :ncbi.nav/host]
   {:id #(some-> % (get-in [:host :tax_id]) str), :op :taxonomy-data-report
    :params (fn [id] {:taxons [id]}), :fetch-fn fetch-one, :type :ncbi/taxonomy}

   [:ncbi/virus :ncbi.nav/annotations]
   {:id :accession, :op :virus-annotation-reports-by-acessions
    :params (fn [id] {:accessions [id]}), :fetch-fn fetch-all, :type :ncbi/virus-annotation}

   ;; --- virus-annotation ---
   [:ncbi/virus-annotation :ncbi.nav/virus]
   {:id :accession, :op :virus-reports-by-acessions
    :params (fn [id] {:accessions [id]}), :fetch-fn fetch-one, :type :ncbi/virus}})

;; --- Pagination ---

(defn- nav-page [client coll k _v]
  (when (= k :ncbi.nav/next-page)
    (let [m (meta coll)]
      (when-let [token (:ncbi/next-page-token m)]
        (fetch client (:ncbi/operation m)
               (assoc (:ncbi/params m) :page_token token)
               (:ncbi/entity-type m))))))

;; --- Fetch ---

(defn fetch [client operation params entity-type]
  (let [response (throttle/with-retry (:rate-limiter client)
                   #(martian/response-for client operation params))
        body     (:body response)
        reports  (or (:reports body) [])
        tagged   (mapv #(datafy/tag-entity client entity-type (extract-report entity-type %)) reports)
        token    (:next_page_token body)]
    (with-meta tagged
      {:ncbi/total-count     (:total_count body)
       :ncbi/next-page-token token
       :ncbi/operation       operation
       :ncbi/params          params
       :ncbi/entity-type     entity-type
       `p/datafy (fn [this]
                   (with-meta this
                     (cond-> (meta this)
                       token (assoc :ncbi.nav/next-page :deferred))))
       `p/nav   (fn [this k v] (nav-page client this k v))})))

(defn fetch-one [client operation params entity-type]
  (first (fetch client operation params entity-type)))

(defn fetch-all [client operation params entity-type]
  (let [page (fetch client operation params entity-type)]
    (lazy-cat page
              (when-let [token (:ncbi/next-page-token (meta page))]
                (fetch-all client operation (assoc params :page_token token) entity-type)))))
