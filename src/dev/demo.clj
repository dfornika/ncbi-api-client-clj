(ns demo
  "Example functions demonstrating the ncbi-api-client library.
   Load from the REPL with (require '[demo :reload true])."
  (:require [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datafy :as d]
            [clojure.datafy :refer [datafy nav]]
            [clojure.string :as str]
            [martian.core :as martian]))

;; ============================================================
;; Exploring API operations
;; ============================================================

(defn list-operations
  "List all available API operation keywords."
  [client]
  (mapv first (martian/explore client)))

(defn supported-operations
  "List the API operations this library supports, grouped by entity type."
  []
  {:ncbi/taxonomy  {:direct-fn  'ncbi/taxonomy
                    :operations [:taxonomy-data-report]
                    :nav-from   {:ncbi/assembly  :ncbi.nav/organism
                                 :ncbi/gene      :ncbi.nav/organism
                                 :ncbi/biosample :ncbi.nav/organism}
                    :nav-to     {:ncbi.nav/assemblies :genome-dataset-reports-by-taxon
                                 :ncbi.nav/genes      :gene-dataset-reports-by-taxon
                                 :ncbi.nav/viruses    :virus-reports-by-taxon
                                 :ncbi.nav/children   :taxonomy-data-report
                                 :ncbi.nav/lineage    :taxonomy-data-report
                                 :ncbi.nav/image      :taxonomy-image-metadata
                                 :ncbi.nav/links      :taxonomy-links}}
   :ncbi/assembly  {:direct-fn  'ncbi/assembly
                    :operations [:genome-dataset-report]
                    :nav-from   {:ncbi/taxonomy  :ncbi.nav/assemblies
                                 :ncbi/gene      :ncbi.nav/assemblies
                                 :ncbi/biosample :ncbi.nav/assemblies}
                    :nav-to     {:ncbi.nav/organism  :taxonomy-data-report
                                 :ncbi.nav/genes     :gene-dataset-reports-by-taxon
                                 :ncbi.nav/biosample :bio-sample-dataset-report
                                 :ncbi.nav/links     :genome-links-by-accession}}
   :ncbi/gene      {:direct-fn  'ncbi/gene
                    :operations [:gene-reports-by-id]
                    :nav-from   {:ncbi/taxonomy :ncbi.nav/genes
                                 :ncbi/assembly :ncbi.nav/genes}
                    :nav-to     {:ncbi.nav/organism   :taxonomy-data-report
                                 :ncbi.nav/orthologs  :gene-orthologs-by-id
                                 :ncbi.nav/assemblies :genome-dataset-report
                                 :ncbi.nav/links      :gene-links-by-id}}
   :ncbi/biosample {:direct-fn  'ncbi/biosample
                    :operations [:bio-sample-dataset-report]
                    :nav-from   {:ncbi/assembly :ncbi.nav/biosample}
                    :nav-to     {:ncbi.nav/organism   :taxonomy-data-report
                                 :ncbi.nav/assemblies :genome-dataset-reports-by-biosample-id}}
   :ncbi/annotation {:direct-fn  'ncbi/annotations
                     :operations [:genome-annotation-report]
                     :nav-from   {:ncbi/assembly :ncbi.nav/annotations}
                     :nav-to     {:ncbi.nav/assembly :genome-dataset-report
                                  :ncbi.nav/gene     :gene-reports-by-id}}
   :ncbi/gene-product {:direct-fn  'ncbi/gene-products
                       :operations [:gene-product-reports-by-id]
                       :nav-from   {:ncbi/gene :ncbi.nav/products}
                       :nav-to     {:ncbi.nav/gene :gene-reports-by-id}}
   :ncbi/sequence  {:direct-fn  'ncbi/sequences
                    :operations [:genome-sequence-report]
                    :nav-from   {:ncbi/assembly :ncbi.nav/sequences}
                    :nav-to     {:ncbi.nav/assembly :genome-dataset-report}}
   :ncbi/virus     {:direct-fn  'ncbi/virus
                    :operations [:virus-reports-by-taxon :virus-reports-by-acessions]
                    :nav-from   {:ncbi/taxonomy :ncbi.nav/viruses}
                    :nav-to     {:ncbi.nav/taxonomy    :taxonomy-data-report
                                 :ncbi.nav/host        :taxonomy-data-report
                                 :ncbi.nav/annotations :virus-annotation-reports-by-acessions}}
   :ncbi/virus-annotation {:direct-fn  'ncbi/virus-annotations
                           :operations [:virus-annotation-reports-by-taxon
                                        :virus-annotation-reports-by-acessions]
                           :nav-from   {:ncbi/virus :ncbi.nav/annotations}
                           :nav-to     {:ncbi.nav/virus :virus-reports-by-acessions}}})

(defn find-operations
  "Find operations whose name contains the given substring."
  [client substring]
  (->> (martian/explore client)
       (filter #(str/includes? (name (first %)) substring))
       (mapv first)))

(defn describe-operation
  "Show the parameters and return schema for an operation."
  [client operation-id]
  (martian/explore client operation-id))

(defn preview-request
  "Build the HTTP request for an operation without sending it."
  [client operation-id params]
  (martian/request-for client operation-id params))

;; ============================================================
;; Taxonomy
;; ============================================================

(defn taxonomy-summary
  "Fetch a taxon and return a concise summary."
  [client taxon-id]
  (let [t (first (ncbi/taxonomy client [taxon-id]))]
    (when t
      {:tax_id              (:tax_id t)
       :name                (get-in t [:current_scientific_name :name])
       :rank                (:rank t)
       :group               (:group_name t)
       :parent-count        (count (:parents t))
       :child-count         (count (:children t))
       :assembly-count      (->> (:counts t)
                                 (filter #(= "COUNT_TYPE_ASSEMBLY" (:type %)))
                                 first :count)})))

(defn taxonomy-image
  "Fetch image metadata for a taxon (source, license, format, sizes)."
  [client taxon-id]
  (let [t (first (ncbi/taxonomy client [taxon-id]))
        t-d (datafy t)]
    (nav t-d :ncbi.nav/image :deferred)))

(defn lineage-names
  "Return the full lineage as a vector of [tax_id, name] pairs."
  [client taxon-id]
  (let [t (first (ncbi/taxonomy client [taxon-id]))
        t-d (datafy t)
        lineage (nav t-d :ncbi.nav/lineage :deferred)]
    (mapv #(vector (:tax_id %)
                   (get-in % [:current_scientific_name :name]))
          lineage)))

(defn child-taxa
  "Return immediate children of a taxon as summary maps."
  [client taxon-id]
  (let [t (first (ncbi/taxonomy client [taxon-id]))
        t-d (datafy t)
        children (nav t-d :ncbi.nav/children :deferred)]
    (mapv #(hash-map :tax_id (:tax_id %)
                     :name (get-in % [:current_scientific_name :name])
                     :rank (:rank %))
          children)))

;; ============================================================
;; Genomes
;; ============================================================

(defn assembly-summary
  "Fetch an assembly and return a concise summary."
  [client accession]
  (let [a (first (ncbi/assembly client [accession]))]
    (when a
      {:accession   (:accession a)
       :organism    (get-in a [:organism :organism_name])
       :tax_id      (get-in a [:organism :tax_id])
       :level       (get-in a [:assembly_info :assembly_level])
       :name        (get-in a [:assembly_info :assembly_name])
       :source      (get-in a [:assembly_info :assembly_category])})))

(defn assemblies-for-taxon
  "List assembly accessions and names for a taxon (first page)."
  [client taxon-id]
  (let [t (first (ncbi/taxonomy client [taxon-id]))
        t-d (datafy t)
        assemblies (nav t-d :ncbi.nav/assemblies :deferred)]
    (mapv #(hash-map :accession (:accession %)
                     :name (get-in % [:assembly_info :assembly_name]))
          (take 20 assemblies))))

(defn assembly-organism
  "Navigate from an assembly accession to its organism taxonomy summary."
  [client accession]
  (let [a (first (ncbi/assembly client [accession]))
        a-d (datafy a)
        org (nav a-d :ncbi.nav/organism :deferred)]
    (when org
      {:tax_id (:tax_id org)
       :name   (get-in org [:current_scientific_name :name])
       :rank   (:rank org)})))

;; ============================================================
;; Genes
;; ============================================================

(defn gene-summary
  "Fetch a gene by ID and return a concise summary."
  [client gene-id]
  (let [g (first (ncbi/gene client [gene-id]))]
    (when g
      {:gene_id     (:gene_id g)
       :symbol      (:symbol g)
       :description (:description g)
       :taxname     (:taxname g)
       :chromosomes (:chromosomes g)
       :type        (:type g)})))

(defn gene-orthologs
  "Find orthologs of a gene across species."
  [client gene-id & {:keys [limit] :or {limit 20}}]
  (let [g (first (ncbi/gene client [gene-id]))
        g-d (datafy g)
        orthologs (nav g-d :ncbi.nav/orthologs :deferred)]
    (mapv #(hash-map :gene_id (:gene_id %)
                     :symbol (:symbol %)
                     :taxname (:taxname %))
          (take limit orthologs))))

(defn gene-assemblies
  "Find the genome assemblies a gene is annotated on."
  [client gene-id]
  (let [g (first (ncbi/gene client [gene-id]))
        g-d (datafy g)
        assemblies (nav g-d :ncbi.nav/assemblies :deferred)]
    (when assemblies
      (mapv #(hash-map :accession (:accession %)
                       :organism (get-in % [:organism :organism_name]))
            assemblies))))

;; ============================================================
;; BioSamples
;; ============================================================

(defn biosample-summary
  "Fetch a biosample and return a concise summary."
  [client accession]
  (let [b (first (ncbi/biosample client [accession]))]
    (when b
      {:accession       (:accession b)
       :title           (get-in b [:description :title])
       :organism        (get-in b [:description :organism :organism_name])
       :tax_id          (get-in b [:description :organism :tax_id])
       :strain          (:strain b)
       :collection_date (:collection_date b)
       :geo_loc_name    (:geo_loc_name b)
       :tissue          (:tissue b)
       :host            (:host b)})))

(defn biosample-attributes
  "Return the key-value attribute pairs for a biosample."
  [client accession]
  (let [b (first (ncbi/biosample client [accession]))]
    (when b
      (mapv #(vector (:name %) (:value %)) (:attributes b)))))

(defn assembly-biosample
  "Navigate from an assembly accession to its biosample summary."
  [client accession]
  (let [a (first (ncbi/assembly client [accession]))
        a-d (datafy a)
        b (nav a-d :ncbi.nav/biosample :deferred)]
    (when b
      {:accession       (:accession b)
       :title           (get-in b [:description :title])
       :organism        (get-in b [:description :organism :organism_name])
       :strain          (:strain b)
       :collection_date (:collection_date b)})))

(defn biosample-assemblies
  "List assemblies associated with a biosample."
  [client accession]
  (let [b (first (ncbi/biosample client [accession]))
        b-d (datafy b)
        assemblies (nav b-d :ncbi.nav/assemblies :deferred)]
    (mapv #(hash-map :accession (:accession %)
                     :name (get-in % [:assembly_info :assembly_name]))
          (take 20 assemblies))))

;; ============================================================
;; Genome Annotations
;; ============================================================

(defn annotation-report
  "List gene annotations on an assembly (first page)."
  [client accession & {:keys [limit] :or {limit 20}}]
  (let [anns (ncbi/annotations client accession)]
    (mapv #(hash-map :gene_id (:gene_id %)
                     :symbol (:symbol %)
                     :name (:name %)
                     :gene_type (:gene_type %)
                     :chromosomes (:chromosomes %))
          (take limit anns))))

(defn annotation-gene
  "Navigate from an annotation to its full gene record."
  [client assembly-accession gene-symbol]
  (let [anns (ncbi/annotations client assembly-accession)
        ann (->> anns
                 (filter #(= gene-symbol (:symbol %)))
                 first)]
    (when ann
      (let [ann-d (datafy ann)
            g (nav ann-d :ncbi.nav/gene :deferred)]
        (when g
          {:gene_id     (:gene_id g)
           :symbol      (:symbol g)
           :description (:description g)
           :type        (:type g)
           :taxname     (:taxname g)})))))

;; ============================================================
;; Gene Products
;; ============================================================

(defn gene-product-summary
  "Fetch gene products and return a concise summary."
  [client gene-id]
  (let [p (first (ncbi/gene-products client [gene-id]))]
    (when p
      {:gene_id          (:gene_id p)
       :symbol           (:symbol p)
       :description      (:description p)
       :type             (:type p)
       :transcript_count (:transcript_count p)
       :protein_count    (:protein_count p)})))

(defn gene-transcripts
  "List transcripts for a gene, navigating via gene -> products."
  [client gene-id & {:keys [limit] :or {limit 10}}]
  (let [g (first (ncbi/gene client [gene-id]))
        g-d (datafy g)
        products (nav g-d :ncbi.nav/products :deferred)
        product (first products)]
    (when product
      (mapv #(hash-map :accession (:accession_version %)
                       :name (:name %)
                       :type (:type %)
                       :length (:length %)
                       :protein (get-in % [:protein :accession_version]))
            (take limit (:transcripts product))))))

;; ============================================================
;; Sequences
;; ============================================================

(defn sequence-report
  "List sequences for an assembly accession."
  [client accession]
  (let [seqs (ncbi/sequences client accession)]
    (mapv #(hash-map :chr_name (:chr_name %)
                     :refseq (:refseq_accession %)
                     :genbank (:genbank_accession %)
                     :length (:length %)
                     :role (:role %))
          seqs)))

(defn sequence-assembly
  "Navigate from a sequence back to its parent assembly."
  [client assembly-accession]
  (let [seq-entity (first (ncbi/sequences client assembly-accession))
        seq-d (datafy seq-entity)
        asm (nav seq-d :ncbi.nav/assembly :deferred)]
    (when asm
      {:accession (:accession asm)
       :organism  (get-in asm [:organism :organism_name])
       :level     (get-in asm [:assembly_info :assembly_level])})))

;; ============================================================
;; Multi-hop navigation
;; ============================================================

(defn taxon->genes
  "Navigate from a taxon to its genes."
  [client taxon-id & {:keys [limit] :or {limit 20}}]
  (let [t (first (ncbi/taxonomy client [taxon-id]))
        t-d (datafy t)
        genes (nav t-d :ncbi.nav/genes :deferred)]
    (mapv #(hash-map :gene_id (:gene_id %)
                     :symbol (:symbol %)
                     :description (:description %))
          (take limit genes))))

(defn ortholog-assemblies
  "Given a gene ID, find an ortholog in a target species and return
   the assemblies it's annotated on."
  [client gene-id target-taxname]
  (let [g (first (ncbi/gene client [gene-id]))
        g-d (datafy g)
        orthologs (nav g-d :ncbi.nav/orthologs :deferred)
        target (first (filter #(= target-taxname (:taxname %)) orthologs))]
    (when target
      (let [target-d (datafy target)
            assemblies (nav target-d :ncbi.nav/assemblies :deferred)]
        {:ortholog (select-keys target [:gene_id :symbol :taxname])
         :assemblies (mapv #(hash-map :accession (:accession %)
                                      :organism (get-in % [:organism :organism_name]))
                           assemblies)}))))

(defn taxonomy-round-trip
  "Demonstrate that navigating taxon -> assembly -> organism returns
   the same taxon."
  [client taxon-id]
  (let [t (first (ncbi/taxonomy client [taxon-id]))
        t-d (datafy t)
        assemblies (nav t-d :ncbi.nav/assemblies :deferred)
        first-asm-d (datafy (first assemblies))
        back (nav first-asm-d :ncbi.nav/organism :deferred)]
    {:original-tax-id (:tax_id t)
     :round-trip-tax-id (:tax_id back)
     :match? (= (:tax_id t) (:tax_id back))}))

;; ============================================================
;; Viruses
;; ============================================================

(defn virus-summary
  "Fetch virus genomes for a taxon and return concise summaries."
  [client taxon & {:keys [limit] :or {limit 10}}]
  (let [viruses (ncbi/virus client taxon)]
    (mapv #(hash-map :accession (:accession %)
                     :virus (get-in % [:virus :organism_name])
                     :host (get-in % [:host :organism_name])
                     :source (:source_database %)
                     :complete? (:is_complete %)
                     :length (:length %))
          (take limit viruses))))

(defn virus-by-accession-summary
  "Fetch a virus genome by accession."
  [client accession]
  (let [v (first (ncbi/virus-by-accession client [accession]))]
    (when v
      {:accession    (:accession v)
       :virus        (get-in v [:virus :organism_name])
       :virus-tax-id (get-in v [:virus :tax_id])
       :host         (get-in v [:host :organism_name])
       :host-tax-id  (get-in v [:host :tax_id])
       :source       (:source_database v)
       :complete?    (:is_complete v)
       :annotated?   (:is_annotated v)
       :length       (:length v)
       :proteins     (:protein_count v)
       :location     (get-in v [:location :geographic_location])
       :isolate      (get-in v [:isolate :name])
       :collection   (get-in v [:isolate :collection_date])})))

(defn virus-host
  "Navigate from a virus genome to its host organism taxonomy."
  [client accession]
  (let [v (first (ncbi/virus-by-accession client [accession]))
        v-d (datafy v)
        host (nav v-d :ncbi.nav/host :deferred)]
    (when host
      {:tax_id (:tax_id host)
       :name   (get-in host [:current_scientific_name :name])
       :rank   (:rank host)})))

(defn virus-genes
  "List annotated genes on a virus genome."
  [client accession]
  (let [anns (ncbi/virus-annotations-by-accession client [accession])
        a (first anns)]
    (when a
      {:accession (:accession a)
       :isolate   (:isolate_name a)
       :genes     (mapv #(hash-map :name (:name %)
                                   :gene_id (:gene_id %)
                                   :cds-count (count (:cds %)))
                        (:genes a))})))

(defn taxon-viruses
  "Navigate from a taxon to its virus genomes."
  [client taxon-id & {:keys [limit] :or {limit 10}}]
  (let [t (first (ncbi/taxonomy client [taxon-id]))
        t-d (datafy t)
        viruses (nav t-d :ncbi.nav/viruses :deferred)]
    (mapv #(hash-map :accession (:accession %)
                     :source (:source_database %)
                     :length (:length %))
          (take limit viruses))))

;; ============================================================
;; External Links
;; ============================================================

(defn taxonomy-links
  "Get external links for a taxon (Wikipedia, EOL, GBIF, etc.)."
  [client taxon-id]
  (let [t (first (ncbi/taxonomy client [taxon-id]))
        t-d (datafy t)]
    (nav t-d :ncbi.nav/links :deferred)))

(defn assembly-links
  "Get external links for an assembly (FTP, PubMed, GDV, etc.)."
  [client accession]
  (let [a (first (ncbi/assembly client [accession]))
        a-d (datafy a)]
    (nav a-d :ncbi.nav/links :deferred)))

(defn gene-links
  "Get external links for a gene (GDV, orthologs, MCGV, etc.)."
  [client gene-id]
  (let [g (first (ncbi/gene client [gene-id]))
        g-d (datafy g)]
    (nav g-d :ncbi.nav/links :deferred)))

;; ============================================================
;; Lower-level access
;; ============================================================

(defn fetch-with-pagination-info
  "Demonstrate that fetch results carry pagination metadata."
  [client operation params entity-type]
  (let [page (d/fetch client operation params entity-type)]
    {:count (count page)
     :total (:ncbi/total-count (meta page))
     :has-next-page? (some? (:ncbi/next-page-token (meta page)))
     :first-item (first page)}))

(defn entity-type
  "Check the :ncbi/type metadata on any entity."
  [entity]
  (:ncbi/type (meta entity)))
