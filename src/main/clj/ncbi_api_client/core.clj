(ns ncbi-api-client.core
  (:require [ncbi-api-client.bridge :as bridge]
            [ncbi-api-client.client :as client]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.eutils :as eu]
            [ncbi-api-client.package :as pkg]))

(defn connect
  "Create a client for both the NCBI Datasets API and E-utilities.
   Returns an opaque client handle — pass it as the first argument to all
   other functions in this namespace.

   Options:
     :api-key  - NCBI API key (or set NCBI_API_KEY env var)
     :tool     - tool name sent with eutils requests (default \"ncbi-api-client-clj\")
     :email    - developer email sent with eutils requests

   Without an API key, requests are rate-limited to ~3/sec. With a key, ~10/sec."
  ([] (connect {}))
  ([opts] (assoc (client/create-client opts) :nav-edges ds/nav-edges)))

;; --- Datasets API ---

(defn taxonomy
  "Fetch taxonomy records by taxon ID.
   Pass a single ID string for one record, or a vector of IDs for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/assemblies, :ncbi.nav/genes, :ncbi.nav/viruses,
             :ncbi.nav/children, :ncbi.nav/lineage, :ncbi.nav/image, :ncbi.nav/links"
  [client taxon-ids-or-id]
  (if (sequential? taxon-ids-or-id)
    (ds/fetch client :taxonomy-data-report {:taxons taxon-ids-or-id} :ncbi/taxonomy)
    (ds/fetch-one client :taxonomy-data-report {:taxons [taxon-ids-or-id]} :ncbi/taxonomy)))

(defn assembly
  "Fetch genome assembly records by accession.
   Pass a single accession string for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/organism, :ncbi.nav/genes, :ncbi.nav/biosample,
             :ncbi.nav/sequences, :ncbi.nav/annotations, :ncbi.nav/package,
             :ncbi.nav/links"
  [client accessions-or-accession]
  (if (sequential? accessions-or-accession)
    (ds/fetch client :genome-dataset-report {:accessions accessions-or-accession} :ncbi/assembly)
    (ds/fetch-one client :genome-dataset-report {:accessions [accessions-or-accession]} :ncbi/assembly)))

(defn gene
  "Fetch gene records by gene ID.
   Pass a single integer/string ID for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/organism, :ncbi.nav/orthologs, :ncbi.nav/products,
             :ncbi.nav/assemblies, :ncbi.nav/package, :ncbi.nav/links"
  [client gene-ids-or-id]
  (if (sequential? gene-ids-or-id)
    (ds/fetch client :gene-reports-by-id {:gene_ids (mapv #(parse-long (str %)) gene-ids-or-id)} :ncbi/gene)
    (ds/fetch-one client :gene-reports-by-id {:gene_ids [(parse-long (str gene-ids-or-id))]} :ncbi/gene)))

(defn biosample
  "Fetch biosample records by accession.
   Pass a single accession string for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/organism, :ncbi.nav/assemblies"
  [client accessions-or-accession]
  (if (sequential? accessions-or-accession)
    (ds/fetch client :bio-sample-dataset-report {:accessions accessions-or-accession} :ncbi/biosample)
    (ds/fetch-one client :bio-sample-dataset-report {:accessions [accessions-or-accession]} :ncbi/biosample)))

(defn sequences
  "Fetch sequence records for a genome assembly.

   Returns a tagged vector of sequence entities.
   Nav keys: :ncbi.nav/assembly"
  [client assembly-accession]
  (ds/fetch client :genome-sequence-report {:accession assembly-accession} :ncbi/sequence))

(defn gene-products
  "Fetch gene product (transcript/protein) records by gene ID.
   Pass a single integer/string ID for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/gene"
  [client gene-ids-or-id]
  (if (sequential? gene-ids-or-id)
    (ds/fetch client :gene-product-reports-by-id {:gene-ids (mapv #(parse-long (str %)) gene-ids-or-id)} :ncbi/gene-product)
    (ds/fetch-one client :gene-product-reports-by-id {:gene-ids [(parse-long (str gene-ids-or-id))]} :ncbi/gene-product)))

(defn annotations
  "Fetch genome annotations for an assembly accession.

   Returns a tagged vector of annotation entities.
   Nav keys: :ncbi.nav/assembly, :ncbi.nav/gene"
  [client assembly-accession]
  (ds/fetch client :genome-annotation-report {:accession assembly-accession} :ncbi/annotation))

(defn virus
  "Fetch virus records by taxon ID.

   Returns a tagged vector of virus entities.
   Nav keys: :ncbi.nav/taxonomy, :ncbi.nav/host, :ncbi.nav/annotations"
  [client taxon]
  (ds/fetch client :virus-reports-by-taxon {:taxon (str taxon)} :ncbi/virus))

(defn virus-by-accession
  "Fetch virus records by accession.
   Pass a single accession string for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/taxonomy, :ncbi.nav/host, :ncbi.nav/annotations"
  [client accessions-or-accession]
  (if (sequential? accessions-or-accession)
    (ds/fetch client :virus-reports-by-acessions {:accessions accessions-or-accession} :ncbi/virus)
    (ds/fetch-one client :virus-reports-by-acessions {:accessions [accessions-or-accession]} :ncbi/virus)))

(defn virus-annotations
  "Fetch virus annotation records by taxon ID.

   Returns a tagged vector of virus annotation entities.
   Nav keys: :ncbi.nav/virus"
  [client taxon]
  (ds/fetch client :virus-annotation-reports-by-taxon {:taxon (str taxon)} :ncbi/virus-annotation))

(defn virus-annotations-by-accession
  "Fetch virus annotation records by accession.
   Pass a single accession string for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/virus"
  [client accessions-or-accession]
  (if (sequential? accessions-or-accession)
    (ds/fetch client :virus-annotation-reports-by-acessions {:accessions accessions-or-accession} :ncbi/virus-annotation)
    (ds/fetch-one client :virus-annotation-reports-by-acessions {:accessions [accessions-or-accession]} :ncbi/virus-annotation)))

(defn download-gene-package
  "Download a gene dataset package as a ZIP archive.
   Returns a navigable package map — use datafy/nav to extract FASTA sequences,
   protein data, and data reports."
  [client gene-id & [opts]]
  (pkg/download-gene-package client gene-id opts))

(defn download-assembly-package
  "Download a genome assembly dataset package as a ZIP archive.
   Returns a navigable package map — use datafy/nav to extract FASTA sequences,
   protein data, and data reports."
  [client accession & [opts]]
  (pkg/download-assembly-package client accession opts))

;; --- Pagination ---

(defn fetch-all
  "Auto-paginate through all results of a Datasets API operation.
   Returns a lazy sequence of tagged entity maps across all pages.

   Arguments:
     client      - a client from `connect`
     operation   - a Martian operation keyword (e.g. :genome-dataset-reports-by-taxon)
     params      - parameter map for the operation
     entity-type - entity type keyword (e.g. :ncbi/assembly)"
  [client operation params entity-type]
  (ds/fetch-all client operation params entity-type))

;; --- E-utilities ---

(defn search
  "Search an Entrez database by keyword. Returns a navigable vector of results.
   Results support datafy/nav: nav :ncbi.nav/datasets-entity to bridge into the
   Datasets entity graph, or nav :ncbi.elink/* keys to follow cross-database links.

   Options: :retmax, :retstart, :sort, :field (same as eutils esearch)."
  [client db term & [opts]]
  (bridge/search client db term opts))

(defn einfo
  "List available Entrez databases, or get field/link info for a specific database.
   With no db argument, returns a vector of database name strings.
   With a db name, returns a map of database metadata (fields, links, count)."
  ([client] (eu/einfo client))
  ([client db] (eu/einfo client db)))

(defn esummary
  "Fetch document summaries for UIDs from an Entrez database.
   Returns a vector of summary maps."
  [client db ids]
  (eu/esummary client db ids))

(defn elink
  "Find linked UIDs across Entrez databases.
   Returns a vector of link result maps, each with :dbto, :linkname, :ids.

   Options: :db, :linkname, :cmd (see eutils elink documentation)."
  [client dbfrom ids & [opts]]
  (eu/elink client dbfrom ids opts))

(defn elink-available
  "List available link types from an Entrez database for given UIDs.
   Returns a vector of {:linkname :dbto :menutag} maps."
  [client dbfrom ids]
  (eu/elink-available client dbfrom ids))
