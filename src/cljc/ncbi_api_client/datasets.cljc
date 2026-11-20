(ns ncbi-api-client.datasets
  "The Datasets API's entities: pages of reports as plans, and the edges
  between entities (`nav-edges`), each a function of an entity that returns
  a plan. Every edge returns one page; `fetch-all` (JVM only) gets the rest."
  (:require [ncbi-api-client.nav :as nav]
            [ncbi-api-client.plan :as plan]
            [ncbi-api-client.request :as req]))

;; --- Reports ---

(def ^:private report-extractors
  "Where each entity type's report sits in an answer's :reports."
  {:ncbi/taxonomy          :taxonomy
   :ncbi/assembly          identity
   :ncbi/gene              :gene
   :ncbi/biosample         identity
   :ncbi/sequence          identity
   :ncbi/gene-product      :product
   :ncbi/annotation        :annotation
   :ncbi/virus             identity
   :ncbi/virus-annotation  identity})

(defn- extract-report [entity-type report]
  ((get report-extractors entity-type identity) report))

(def ^:private labels
  "What identifies an entity of each type, for descriptions."
  {:ncbi/taxonomy          :tax_id
   :ncbi/assembly          :accession
   :ncbi/gene              :gene_id
   :ncbi/biosample         :accession
   :ncbi/sequence          :accession_version
   :ncbi/gene-product      :gene_id
   :ncbi/annotation        :gene_id
   :ncbi/virus             :accession
   :ncbi/virus-annotation  :accession})

(defn- tag [client entity-type report]
  (when-let [data (extract-report entity-type report)]
    (nav/tag-entity client entity-type (some-> ((get labels entity-type (constantly nil)) data) str) data)))

;; --- Pages ---

(defn page-plan
  "A plan for one page of Datasets operation `operation` with `params`: a
  vector of `entity-type` entities (see `nav/page`), whose metadata also
  has :ncbi/total-count, :ncbi/next-page-token, :ncbi/operation,
  :ncbi/params and :ncbi/entity-type."
  [client operation params entity-type]
  (plan/plan
   (req/datasets operation params)
   (fn [body]
     (let [token (:next_page_token body)]
       (nav/page client
                 (keep #(tag client entity-type %) (:reports body))
                 {:total     (:total_count body)
                  :next-plan (when token
                               (page-plan client operation (assoc params :page_token token) entity-type))
                  :describe  (str "next page of " (name operation))}
                 {:ncbi/total-count     (:total_count body)
                  :ncbi/next-page-token token
                  :ncbi/operation       operation
                  :ncbi/params          params
                  :ncbi/entity-type     entity-type})))))

(defn one-plan
  "A plan for the first entity of a page (see `page-plan`), or nil."
  [client operation params entity-type]
  (plan/then (page-plan client operation params entity-type) first))

;; --- Edges ---

(defn- tax-id [e] (some-> e :tax_id str))
(defn- gene-id [e] (some-> e :gene_id str parse-long))

(defn- page-edge
  "An edge to a page of `entity-type`, by `id-fn` of the entity and
  `params-fn` of the ID."
  [id-fn operation params-fn entity-type]
  {:plan (fn [client e]
           (when-let [id (id-fn e)]
             (page-plan client operation (params-fn id) entity-type)))})

(defn- one-edge
  "An edge to one entity of `entity-type` (see `page-edge`)."
  [id-fn operation params-fn entity-type]
  {:plan (fn [client e]
           (when-let [id (id-fn e)]
             (one-plan client operation (params-fn id) entity-type)))})

(defn- taxa-edge
  "An edge to the taxa listed under `k` (children, parents)."
  [k]
  {:plan (fn [client e]
           (when-let [ids (seq (map str (get e k)))]
             (page-plan client :taxonomy-data-report {:taxons (vec ids)} :ncbi/taxonomy)))})

(def nav-edges
  "The Datasets nav graph: [entity-type nav-key] -> {:plan (fn [client
  entity] plan-or-nil)}. Package downloads (JVM only) are added by
  `ncbi-api-client.package/nav-edges`."
  {;; --- taxonomy ---
   [:ncbi/taxonomy :ncbi.nav/assemblies]
   (page-edge tax-id :genome-dataset-reports-by-taxon (fn [id] {:taxons [id]}) :ncbi/assembly)

   [:ncbi/taxonomy :ncbi.nav/genes]
   (page-edge tax-id :gene-dataset-reports-by-taxon (fn [id] {:taxon id}) :ncbi/gene)

   [:ncbi/taxonomy :ncbi.nav/viruses]
   (page-edge tax-id :virus-reports-by-taxon (fn [id] {:taxon id}) :ncbi/virus)

   [:ncbi/taxonomy :ncbi.nav/children] (taxa-edge :children)
   [:ncbi/taxonomy :ncbi.nav/lineage]  (taxa-edge :parents)

   [:ncbi/taxonomy :ncbi.nav/image]
   {:plan (fn [_ e]
            (when-let [id (tax-id e)]
              ;; most taxa have no image: a 404 means none
              (plan/plan (assoc (req/datasets :taxonomy-image-metadata {:taxon id}) :optional? true))))}

   [:ncbi/taxonomy :ncbi.nav/links]
   {:plan (fn [_ e]
            (when-let [id (tax-id e)]
              (plan/plan (req/datasets :taxonomy-links {:taxon id}) #(dissoc % :tax_id))))}

   ;; --- assembly ---
   [:ncbi/assembly :ncbi.nav/organism]
   (one-edge #(some-> % (get-in [:organism :tax_id]) str) :taxonomy-data-report
             (fn [id] {:taxons [id]}) :ncbi/taxonomy)

   [:ncbi/assembly :ncbi.nav/annotations]
   (page-edge :accession :genome-annotation-report (fn [id] {:accession id}) :ncbi/annotation)

   [:ncbi/assembly :ncbi.nav/sequences]
   (page-edge :accession :genome-sequence-report (fn [id] {:accession id}) :ncbi/sequence)

   [:ncbi/assembly :ncbi.nav/biosample]
   (one-edge #(get-in % [:assembly_info :biosample :accession]) :bio-sample-dataset-report
             (fn [id] {:accessions [id]}) :ncbi/biosample)

   [:ncbi/assembly :ncbi.nav/genes]
   {:plan (fn [client e]
            (when-let [taxon (some-> e (get-in [:organism :tax_id]) str)]
              (page-plan client :gene-dataset-reports-by-taxon
                         {:taxon                      taxon
                          :filters.reference_only     true
                          :filters.assembly_accession (:accession e)}
                         :ncbi/gene)))}

   [:ncbi/assembly :ncbi.nav/links]
   {:plan (fn [_ e]
            (when-let [acc (:accession e)]
              (plan/plan (req/datasets :genome-links-by-accession {:accessions [acc]})
                         :assembly_links)))}

   ;; --- gene ---
   [:ncbi/gene :ncbi.nav/organism]
   (one-edge tax-id :taxonomy-data-report (fn [id] {:taxons [id]}) :ncbi/taxonomy)

   [:ncbi/gene :ncbi.nav/orthologs]
   (page-edge gene-id :gene-orthologs-by-id (fn [id] {:gene_id id}) :ncbi/gene)

   [:ncbi/gene :ncbi.nav/products]
   (page-edge gene-id :gene-product-reports-by-id (fn [id] {:gene-ids [id]}) :ncbi/gene-product)

   [:ncbi/gene :ncbi.nav/assemblies]
   {:plan (fn [client e]
            (when-let [accessions (seq (distinct (keep :assembly_accession (:annotations e))))]
              (page-plan client :genome-dataset-report {:accessions (vec accessions)} :ncbi/assembly)))}

   [:ncbi/gene :ncbi.nav/links]
   {:plan (fn [_ e]
            (when-let [id (gene-id e)]
              (plan/plan (req/datasets :gene-links-by-id {:gene-ids [id]}) :gene_links)))}

   ;; --- gene-product ---
   [:ncbi/gene-product :ncbi.nav/gene]
   (one-edge gene-id :gene-reports-by-id (fn [id] {:gene_ids [id]}) :ncbi/gene)

   ;; --- biosample ---
   [:ncbi/biosample :ncbi.nav/organism]
   (one-edge #(some-> % (get-in [:description :organism :tax_id]) str) :taxonomy-data-report
             (fn [id] {:taxons [id]}) :ncbi/taxonomy)

   [:ncbi/biosample :ncbi.nav/assemblies]
   (page-edge :accession :genome-dataset-reports-by-biosample-id
              (fn [id] {:biosample-ids [id]}) :ncbi/assembly)

   ;; --- annotation ---
   [:ncbi/annotation :ncbi.nav/assembly]
   (one-edge #(get-in % [:annotations 0 :assembly_accession]) :genome-dataset-report
             (fn [id] {:accessions [id]}) :ncbi/assembly)

   [:ncbi/annotation :ncbi.nav/gene]
   (one-edge gene-id :gene-reports-by-id (fn [id] {:gene_ids [id]}) :ncbi/gene)

   ;; --- sequence ---
   [:ncbi/sequence :ncbi.nav/assembly]
   (one-edge :assembly_accession :genome-dataset-report (fn [id] {:accessions [id]}) :ncbi/assembly)

   ;; --- virus ---
   [:ncbi/virus :ncbi.nav/taxonomy]
   (one-edge #(some-> % (get-in [:virus :tax_id]) str) :taxonomy-data-report
             (fn [id] {:taxons [id]}) :ncbi/taxonomy)

   [:ncbi/virus :ncbi.nav/host]
   (one-edge #(some-> % (get-in [:host :tax_id]) str) :taxonomy-data-report
             (fn [id] {:taxons [id]}) :ncbi/taxonomy)

   [:ncbi/virus :ncbi.nav/annotations]
   (page-edge :accession :virus-annotation-reports-by-acessions
              (fn [id] {:accessions [id]}) :ncbi/virus-annotation)

   ;; --- virus-annotation ---
   [:ncbi/virus-annotation :ncbi.nav/virus]
   (one-edge :accession :virus-reports-by-acessions (fn [id] {:accessions [id]}) :ncbi/virus)})

;; --- Running (JVM) ---

#?(:clj
   (do
     (defn call
       "The body of Datasets operation `op` with `params`, as it is."
       [client op params]
       (nav/run client (plan/plan (req/datasets op params))))

     (defn fetch
       "One page of Datasets operation `operation` with `params`, as tagged
       `entity-type` entities (see `page-plan`)."
       [client operation params entity-type]
       (nav/run client (page-plan client operation params entity-type)))

     (defn fetch-one
       "The first entity of `fetch`, or nil."
       [client operation params entity-type]
       (first (fetch client operation params entity-type)))

     (defn fetch-all
       "Every page of `fetch`, as a lazy sequence of entities."
       [client operation params entity-type]
       (let [page (fetch client operation params entity-type)]
         (lazy-cat page
                   (when-let [token (:ncbi/next-page-token (meta page))]
                     (fetch-all client operation (assoc params :page_token token) entity-type)))))))
