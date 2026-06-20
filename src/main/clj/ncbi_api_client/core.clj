(ns ncbi-api-client.core
  (:require [ncbi-api-client.client :as client]
            [ncbi-api-client.datafy :as d]))

(defn connect
  ([] (client/create-client))
  ([opts] (client/create-client opts)))

(defn taxonomy [client taxon-ids-or-id]
  (if (string? taxon-ids-or-id)
    (d/fetch-one client :taxonomy-data-report {:taxons [taxon-ids-or-id]} :ncbi/taxonomy)
    (d/fetch client :taxonomy-data-report {:taxons taxon-ids-or-id} :ncbi/taxonomy)))

(defn assembly [client accessions]
  (d/fetch client :genome-dataset-report {:accessions accessions} :ncbi/assembly))

(defn gene [client gene-ids]
  (d/fetch client :gene-reports-by-id {:gene_ids (mapv #(parse-long (str %)) gene-ids)} :ncbi/gene))

(defn biosample [client accessions]
  (d/fetch client :bio-sample-dataset-report {:accessions accessions} :ncbi/biosample))

(defn sequences [client assembly-accession]
  (d/fetch client :genome-sequence-report {:accession assembly-accession} :ncbi/sequence))

(defn gene-products [client gene-ids]
  (d/fetch client :gene-product-reports-by-id {:gene-ids (mapv #(parse-long (str %)) gene-ids)} :ncbi/gene-product))

(defn annotations [client assembly-accession]
  (d/fetch client :genome-annotation-report {:accession assembly-accession} :ncbi/annotation))

(defn virus [client taxon]
  (d/fetch client :virus-reports-by-taxon {:taxon (str taxon)} :ncbi/virus))

(defn virus-by-accession [client accessions]
  (d/fetch client :virus-reports-by-acessions {:accessions accessions} :ncbi/virus))

(defn virus-annotations [client taxon]
  (d/fetch client :virus-annotation-reports-by-taxon {:taxon (str taxon)} :ncbi/virus-annotation))

(defn virus-annotations-by-accession [client accessions]
  (d/fetch client :virus-annotation-reports-by-acessions {:accessions accessions} :ncbi/virus-annotation))
