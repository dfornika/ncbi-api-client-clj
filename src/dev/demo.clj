(ns demo
  "Demonstrations of the ncbi-api-client library.
   Each section is a standalone rich comment block that can be
   evaluated form-by-form at the REPL."
  (:require [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datafy :as d]
            [clojure.datafy :refer [datafy nav]]
            [martian.core :as martian]))

(def client (ncbi/connect))

;; ============================================================
;; 1. Exploring available API operations
;; ============================================================

(comment
  ;; All ~107 operations from the NCBI Datasets API
  (martian/explore client)

  ;; Filter to find operations by keyword
  (filter #(clojure.string/includes? (name (first %)) "virus")
          (martian/explore client))

  ;; Inspect a specific operation's parameters and return schema
  (martian/explore client :taxonomy-data-report)
  (martian/explore client :gene-reports-by-id)
  (martian/explore client :genome-dataset-report)

  ;; Preview the HTTP request without sending it
  (martian/request-for client :taxonomy-data-report {:taxons ["9606"]}))

;; ============================================================
;; 2. Taxonomy: navigating the tree of life
;; ============================================================

(comment
  ;; Fetch a taxonomy node by NCBI Taxonomy ID
  (def human (first (ncbi/taxonomy client ["9606"])))
  (:current_scientific_name human)
  ;; => {:name "Homo sapiens", :authority "Linnaeus, 1758"}

  ;; You can also look up by name
  (def ecoli (first (ncbi/taxonomy client ["Escherichia coli"])))
  (:tax_id ecoli)

  ;; datafy adds navigation links to the entity
  (def human-d (datafy human))
  (keys human-d)
  ;; Raw data keys plus :ncbi.nav/assemblies, :ncbi.nav/genes,
  ;; :ncbi.nav/children, :ncbi.nav/lineage

  ;; Walk up the lineage (each is a navigable taxonomy node)
  (def lineage (nav human-d :ncbi.nav/lineage :deferred))
  (mapv #(select-keys % [:tax_id :current_scientific_name])
        lineage)

  ;; Walk down to child taxa
  (def children (nav human-d :ncbi.nav/children :deferred))
  (mapv #(select-keys % [:tax_id :current_scientific_name])
        children)

  ;; Fetch multiple taxa at once
  (def primates (ncbi/taxonomy client ["9443"]))
  (def primate-children (nav (datafy (first primates))
                             :ncbi.nav/children :deferred))
  (count primate-children))

;; ============================================================
;; 3. Genomes: assemblies and their metadata
;; ============================================================

(comment
  ;; Fetch an assembly by accession
  (def grch38 (first (ncbi/assembly client ["GCF_000001405.40"])))
  (select-keys grch38 [:accession :assembly_info :organism])

  ;; Fetch the T2T (telomere-to-telomere) human assembly
  (def t2t (first (ncbi/assembly client ["GCF_009914755.1"])))
  (select-keys t2t [:accession :current_accession :organism])

  ;; Navigate from taxonomy to all assemblies for an organism
  (def human (first (ncbi/taxonomy client ["9606"])))
  (def human-assemblies (nav (datafy human) :ncbi.nav/assemblies :deferred))
  (count (take 50 human-assemblies))
  (mapv #(select-keys % [:accession :assembly_info])
        (take 5 human-assemblies))

  ;; Navigate from assembly back to taxonomy
  (def organism (nav (datafy grch38) :ncbi.nav/organism :deferred))
  (:current_scientific_name organism)

  ;; Pagination metadata is available on fetch results
  (def page (ncbi/assembly client ["GCF_000001405.40"]))
  (meta page)
  ;; => {:ncbi/total-count 1, :ncbi/next-page-token nil, ...}
  )

;; ============================================================
;; 4. Genes: lookup, orthologs, and cross-references
;; ============================================================

(comment
  ;; Fetch a gene by NCBI Gene ID
  (def brca1 (first (ncbi/gene client [672])))
  (select-keys brca1 [:gene_id :symbol :description :taxname
                      :chromosomes :type])

  ;; TP53 - a well-known tumor suppressor
  (def tp53 (first (ncbi/gene client [7157])))
  (select-keys tp53 [:gene_id :symbol :description :taxname])

  ;; Fetch multiple genes at once
  (def genes (ncbi/gene client [672 7157 5243]))
  (mapv #(select-keys % [:gene_id :symbol :description])
        genes)

  ;; datafy reveals navigation links
  (def brca1-d (datafy brca1))
  ;; Nav keys: :ncbi.nav/organism, :ncbi.nav/orthologs, :ncbi.nav/assemblies

  ;; Navigate to the gene's organism taxonomy
  (def brca1-org (nav brca1-d :ncbi.nav/organism :deferred))
  (:current_scientific_name brca1-org)

  ;; Find orthologs across species
  (def orthologs (nav brca1-d :ncbi.nav/orthologs :deferred))
  (mapv #(select-keys % [:gene_id :symbol :taxname])
        (take 10 orthologs))
  ;; => human, mouse, rat, dog, chicken, zebrafish...

  ;; Each ortholog is itself navigable
  (def mouse-brca1 (datafy (second orthologs)))
  (:taxname mouse-brca1)
  (nav mouse-brca1 :ncbi.nav/assemblies :deferred)

  ;; Navigate from gene to the assemblies it's annotated on
  (def brca1-assemblies (nav brca1-d :ncbi.nav/assemblies :deferred))
  (mapv :accession brca1-assemblies)
  ;; => ["GCF_000001405.40" "GCF_009914755.1"]
  )

;; ============================================================
;; 5. Navigation chains: linking entities together
;; ============================================================

(comment
  ;; Start from a taxon, navigate to assemblies, then to genes
  ;; This demonstrates multi-hop navigation through the data graph

  ;; SARS-CoV-2 taxonomy -> assemblies -> back to taxonomy
  (def sars2 (first (ncbi/taxonomy client ["2697049"])))
  (def sars2-d (datafy sars2))

  (def sars2-assemblies (nav sars2-d :ncbi.nav/assemblies :deferred))
  (def first-asm (datafy (first sars2-assemblies)))

  ;; Round-trip: assembly -> organism should give us the same taxon
  (def back-to-sars2 (nav first-asm :ncbi.nav/organism :deferred))
  (= (:tax_id sars2) (:tax_id back-to-sars2))
  ;; => true

  ;; Gene -> organism -> assemblies -> organism (multi-hop)
  (def tp53 (first (ncbi/gene client [7157])))
  (def tp53-d (datafy tp53))
  (def tp53-org (nav tp53-d :ncbi.nav/organism :deferred))
  (def tp53-org-d (datafy tp53-org))
  (def org-assemblies (nav tp53-org-d :ncbi.nav/assemblies :deferred))
  (select-keys (first org-assemblies) [:accession :organism])

  ;; Ortholog walk: find BRCA1 in mouse, then get its assemblies
  (def brca1 (first (ncbi/gene client [672])))
  (def orthologs (nav (datafy brca1) :ncbi.nav/orthologs :deferred))
  (def mouse-brca1 (first (filter #(= "Mus musculus" (:taxname %)) orthologs)))
  (def mouse-assemblies (nav (datafy mouse-brca1) :ncbi.nav/assemblies :deferred))
  (mapv #(select-keys % [:accession :organism]) mouse-assemblies))

;; ============================================================
;; 6. Viral genomics: SARS-CoV-2 exploration
;; ============================================================

(comment
  ;; Explore the SARS-CoV-2 taxonomic neighbourhood
  (def sars2 (first (ncbi/taxonomy client ["2697049"])))

  ;; What genus does it belong to?
  (get-in sars2 [:classification :genus])

  ;; What's its lineage?
  (def lineage (nav (datafy sars2) :ncbi.nav/lineage :deferred))
  (mapv #(vector (:tax_id %) (get-in % [:current_scientific_name :name]))
        lineage)
  ;; => [[1 "root"] [2559587 "Riboviria"] ... [2509511 "Sarbecovirus"] ...]

  ;; How many genome assemblies exist for SARS-CoV-2?
  (->> (:counts sars2)
       (filter #(= "COUNT_TYPE_ASSEMBLY" (:type %)))
       first
       :count)

  ;; Get the genes annotated on SARS-CoV-2
  (def sars2-genes (nav (datafy sars2) :ncbi.nav/genes :deferred))
  (mapv #(select-keys % [:gene_id :symbol :description])
        (take 15 sars2-genes)))

;; ============================================================
;; 7. Lower-level access: using fetch and Martian directly
;; ============================================================

(comment
  ;; The convenience functions (ncbi/taxonomy, ncbi/gene, ncbi/assembly)
  ;; are thin wrappers around d/fetch. You can call it directly for
  ;; operations that don't have a convenience wrapper yet.

  ;; Gene counts for a taxon
  (martian/response-for client :gene-counts-for-taxon {:taxon "9606"})

  ;; Gene product reports
  (d/fetch client :gene-product-reports-by-id {:gene_ids [672]} :ncbi/gene)

  ;; Taxonomy names
  (martian/response-for client :taxonomy-names {:taxons ["9606"]})

  ;; Pagination: fetch returns metadata with :ncbi/next-page-token
  (def page1 (d/fetch client :gene-dataset-reports-by-taxon
                      {:taxon "2697049"} :ncbi/gene))
  (meta page1)

  ;; fetch-all handles pagination automatically as a lazy seq
  (def all-genes (d/fetch-all client :gene-dataset-reports-by-taxon
                              {:taxon "2697049"} :ncbi/gene))
  (count all-genes)

  ;; Entity metadata: check the type and client attached to any entity
  (def human (first (ncbi/taxonomy client ["9606"])))
  (:ncbi/type (meta human))
  ;; => :ncbi/taxonomy
  )
