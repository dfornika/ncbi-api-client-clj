(ns ncbi-api-client.datafy
  (:require [clojure.core.protocols :as p]
            [martian.core :as martian]))

;; --- Report extraction ---

(def ^:private report-extractors
  {:ncbi/taxonomy     :taxonomy
   :ncbi/assembly     identity
   :ncbi/gene         :gene
   :ncbi/biosample    identity
   :ncbi/sequence     identity
   :ncbi/gene-product :product
   :ncbi/annotation   :annotation})

(defn- extract-report [entity-type report]
  (let [extractor (get report-extractors entity-type identity)]
    (if (keyword? extractor)
      (get report extractor)
      (extractor report))))

;; --- Forward declarations ---

(declare datafy-entity nav-entity)

;; --- Entity tagging ---

(defn tag-entity [client entity-type data]
  (when data
    (with-meta data
      {`p/datafy (fn [this] (datafy-entity client entity-type this))
       `p/nav    (fn [this k v] (nav-entity client entity-type this k v))
       :ncbi/type   entity-type
       :ncbi/client client})))

;; --- Fetch helpers ---

(defn- nav-page [client _coll k _v]
  (let [m    (meta _coll)
        op   (:ncbi/operation m)
        et   (:ncbi/entity-type m)]
    (when (and (= k :ncbi.nav/next-page) op et)
      (when-let [token (:ncbi/next-page-token m)]
        (fetch client op (assoc (:ncbi/params m) :page_token token) et)))))

(defn fetch [client operation params entity-type]
  (let [response (martian/response-for client operation params)
        body     (:body response)
        reports  (or (:reports body) [])
        tagged   (mapv #(tag-entity client entity-type (extract-report entity-type %)) reports)
        token    (:next_page_token body)]
    (with-meta tagged
      {:ncbi/total-count     (:total_count body)
       :ncbi/next-page-token token
       :ncbi/operation       operation
       :ncbi/params          params
       :ncbi/entity-type     entity-type
       `p/datafy (fn [this]
                   (if token
                     (with-meta (conj this [:ncbi.nav/next-page :deferred])
                       (meta this))
                     this))
       `p/nav   (fn [this k v] (nav-page client this k v))})))

(defn fetch-one [client operation params entity-type]
  (first (fetch client operation params entity-type)))

(defn fetch-all [client operation params entity-type]
  (let [page (fetch client operation params entity-type)]
    (lazy-cat page
              (when-let [token (:ncbi/next-page-token (meta page))]
                (fetch-all client operation (assoc params :page_token token) entity-type)))))

;; --- datafy multimethod ---

(defmulti datafy-entity (fn [_client entity-type _data] entity-type))

(defmethod datafy-entity :ncbi/taxonomy
  [client _ data]
  (-> data
      (assoc :ncbi.nav/assemblies :deferred
             :ncbi.nav/genes      :deferred
             :ncbi.nav/children   :deferred
             :ncbi.nav/lineage    :deferred
             :ncbi.nav/image      :deferred
             :ncbi.nav/links      :deferred)
      (with-meta
        {`p/nav    (fn [this k v] (nav-entity client :ncbi/taxonomy this k v))
         :ncbi/type   :ncbi/taxonomy
         :ncbi/client client})))

(defmethod datafy-entity :ncbi/assembly
  [client _ data]
  (-> data
      (assoc :ncbi.nav/organism     :deferred
             :ncbi.nav/genes        :deferred
             :ncbi.nav/biosample    :deferred
             :ncbi.nav/sequences    :deferred
             :ncbi.nav/annotations  :deferred
             :ncbi.nav/links        :deferred)
      (with-meta
        {`p/nav    (fn [this k v] (nav-entity client :ncbi/assembly this k v))
         :ncbi/type   :ncbi/assembly
         :ncbi/client client})))

(defmethod datafy-entity :ncbi/gene
  [client _ data]
  (-> data
      (assoc :ncbi.nav/organism   :deferred
             :ncbi.nav/orthologs  :deferred
             :ncbi.nav/assemblies :deferred
             :ncbi.nav/products   :deferred
             :ncbi.nav/links      :deferred)
      (with-meta
        {`p/nav    (fn [this k v] (nav-entity client :ncbi/gene this k v))
         :ncbi/type   :ncbi/gene
         :ncbi/client client})))

(defmethod datafy-entity :ncbi/biosample
  [client _ data]
  (-> data
      (assoc :ncbi.nav/organism   :deferred
             :ncbi.nav/assemblies :deferred)
      (with-meta
        {`p/nav    (fn [this k v] (nav-entity client :ncbi/biosample this k v))
         :ncbi/type   :ncbi/biosample
         :ncbi/client client})))

(defmethod datafy-entity :ncbi/annotation
  [client _ data]
  (-> data
      (assoc :ncbi.nav/assembly :deferred
             :ncbi.nav/gene     :deferred)
      (with-meta
        {`p/nav    (fn [this k v] (nav-entity client :ncbi/annotation this k v))
         :ncbi/type   :ncbi/annotation
         :ncbi/client client})))

(defmethod datafy-entity :ncbi/gene-product
  [client _ data]
  (-> data
      (assoc :ncbi.nav/gene :deferred)
      (with-meta
        {`p/nav    (fn [this k v] (nav-entity client :ncbi/gene-product this k v))
         :ncbi/type   :ncbi/gene-product
         :ncbi/client client})))

(defmethod datafy-entity :ncbi/sequence
  [client _ data]
  (-> data
      (assoc :ncbi.nav/assembly :deferred)
      (with-meta
        {`p/nav    (fn [this k v] (nav-entity client :ncbi/sequence this k v))
         :ncbi/type   :ncbi/sequence
         :ncbi/client client})))

(defmethod datafy-entity :default
  [_client _entity-type data]
  data)

;; --- nav multimethod ---

(defmulti nav-entity (fn [_client entity-type _coll k _v] [entity-type k]))

(defmethod nav-entity [:ncbi/taxonomy :ncbi.nav/assemblies]
  [client _ coll _ _]
  (let [tax-id (str (:tax_id coll))]
    (fetch-all client :genome-dataset-reports-by-taxon {:taxons [tax-id]} :ncbi/assembly)))

(defmethod nav-entity [:ncbi/taxonomy :ncbi.nav/image]
  [client _ coll _ _]
  (let [tax-id (str (:tax_id coll))]
    (try
      (let [resp (martian/response-for client :taxonomy-image-metadata {:taxon tax-id})]
        (:body resp))
      (catch Exception _ nil))))

(defmethod nav-entity [:ncbi/taxonomy :ncbi.nav/links]
  [client _ coll _ _]
  (let [tax-id (str (:tax_id coll))
        resp (martian/response-for client :taxonomy-links {:taxon tax-id})]
    (dissoc (:body resp) :tax_id)))

(defmethod nav-entity [:ncbi/assembly :ncbi.nav/links]
  [client _ coll _ _]
  (let [accession (:accession coll)
        resp (martian/response-for client :genome-links-by-accession {:accessions [accession]})]
    (get-in resp [:body :assembly_links])))

(defmethod nav-entity [:ncbi/gene :ncbi.nav/links]
  [client _ coll _ _]
  (let [gene-id (parse-long (str (:gene_id coll)))
        resp (martian/response-for client :gene-links-by-id {:gene-ids [gene-id]})]
    (get-in resp [:body :gene_links])))

(defmethod nav-entity [:ncbi/taxonomy :ncbi.nav/children]
  [client _ coll _ _]
  (let [child-ids (mapv str (:children coll))]
    (when (seq child-ids)
      (fetch client :taxonomy-data-report {:taxons child-ids} :ncbi/taxonomy))))

(defmethod nav-entity [:ncbi/taxonomy :ncbi.nav/lineage]
  [client _ coll _ _]
  (let [parent-ids (mapv str (:parents coll))]
    (when (seq parent-ids)
      (fetch client :taxonomy-data-report {:taxons parent-ids} :ncbi/taxonomy))))

(defmethod nav-entity [:ncbi/taxonomy :ncbi.nav/genes]
  [client _ coll _ _]
  (let [tax-id (str (:tax_id coll))]
    (fetch-all client :gene-dataset-reports-by-taxon {:taxon tax-id} :ncbi/gene)))

(defmethod nav-entity [:ncbi/assembly :ncbi.nav/organism]
  [client _ coll _ _]
  (let [tax-id (str (get-in coll [:organism :tax_id]))]
    (fetch-one client :taxonomy-data-report {:taxons [tax-id]} :ncbi/taxonomy)))

(defmethod nav-entity [:ncbi/assembly :ncbi.nav/annotations]
  [client _ coll _ _]
  (let [accession (:accession coll)]
    (fetch-all client :genome-annotation-report {:accession accession} :ncbi/annotation)))

(defmethod nav-entity [:ncbi/annotation :ncbi.nav/assembly]
  [client _ coll _ _]
  (when-let [accession (get-in coll [:annotations 0 :assembly_accession])]
    (fetch-one client :genome-dataset-report {:accessions [accession]} :ncbi/assembly)))

(defmethod nav-entity [:ncbi/annotation :ncbi.nav/gene]
  [client _ coll _ _]
  (when-let [gene-id (some-> (:gene_id coll) str parse-long)]
    (fetch-one client :gene-reports-by-id {:gene_ids [gene-id]} :ncbi/gene)))

(defmethod nav-entity [:ncbi/assembly :ncbi.nav/sequences]
  [client _ coll _ _]
  (let [accession (:accession coll)]
    (fetch-all client :genome-sequence-report {:accession accession} :ncbi/sequence)))

(defmethod nav-entity [:ncbi/sequence :ncbi.nav/assembly]
  [client _ coll _ _]
  (let [accession (:assembly_accession coll)]
    (fetch-one client :genome-dataset-report {:accessions [accession]} :ncbi/assembly)))

(defmethod nav-entity [:ncbi/assembly :ncbi.nav/biosample]
  [client _ coll _ _]
  (when-let [accession (get-in coll [:assembly_info :biosample :accession])]
    (fetch-one client :bio-sample-dataset-report {:accessions [accession]} :ncbi/biosample)))

(defmethod nav-entity [:ncbi/assembly :ncbi.nav/genes]
  [client _ coll _ _]
  (let [accession (:accession coll)]
    (fetch-all client :gene-dataset-reports-by-taxon
               {:taxon (str (get-in coll [:organism :tax_id]))
                :filters.reference_only true
                :filters.assembly_accession accession}
               :ncbi/gene)))

(defmethod nav-entity [:ncbi/gene :ncbi.nav/organism]
  [client _ coll _ _]
  (let [tax-id (str (:tax_id coll))]
    (fetch-one client :taxonomy-data-report {:taxons [tax-id]} :ncbi/taxonomy)))

(defmethod nav-entity [:ncbi/gene :ncbi.nav/orthologs]
  [client _ coll _ _]
  (let [gene-id (parse-long (str (:gene_id coll)))]
    (fetch-all client :gene-orthologs-by-id {:gene_id gene-id} :ncbi/gene)))

(defmethod nav-entity [:ncbi/biosample :ncbi.nav/organism]
  [client _ coll _ _]
  (let [tax-id (str (get-in coll [:description :organism :tax_id]))]
    (fetch-one client :taxonomy-data-report {:taxons [tax-id]} :ncbi/taxonomy)))

(defmethod nav-entity [:ncbi/biosample :ncbi.nav/assemblies]
  [client _ coll _ _]
  (let [accession (:accession coll)]
    (fetch-all client :genome-dataset-reports-by-biosample-id {:biosample-ids [accession]} :ncbi/assembly)))

(defmethod nav-entity [:ncbi/gene :ncbi.nav/products]
  [client _ coll _ _]
  (let [gene-id (parse-long (str (:gene_id coll)))]
    (fetch-all client :gene-product-reports-by-id {:gene-ids [gene-id]} :ncbi/gene-product)))

(defmethod nav-entity [:ncbi/gene-product :ncbi.nav/gene]
  [client _ coll _ _]
  (let [gene-id (parse-long (str (:gene_id coll)))]
    (fetch-one client :gene-reports-by-id {:gene_ids [gene-id]} :ncbi/gene)))

(defmethod nav-entity [:ncbi/gene :ncbi.nav/assemblies]
  [client _ coll _ _]
  (let [accessions (->> (:annotations coll)
                        (map :assembly_accession)
                        (remove nil?)
                        distinct
                        vec)]
    (when (seq accessions)
      (fetch client :genome-dataset-report {:accessions accessions} :ncbi/assembly))))

(defmethod nav-entity :default
  [_ _ _ _ v]
  v)
