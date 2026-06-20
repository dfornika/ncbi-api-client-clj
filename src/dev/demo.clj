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
