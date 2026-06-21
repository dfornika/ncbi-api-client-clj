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

(defn assembly [client accessions-or-accession]
  (if (string? accessions-or-accession)
    (d/fetch-one client :genome-dataset-report {:accessions [accessions-or-accession]} :ncbi/assembly)
    (d/fetch client :genome-dataset-report {:accessions accessions-or-accession} :ncbi/assembly)))

(defn gene [client gene-ids-or-id]
  (if (sequential? gene-ids-or-id)
    (d/fetch client :gene-reports-by-id {:gene_ids (mapv #(parse-long (str %)) gene-ids-or-id)} :ncbi/gene)
    (d/fetch-one client :gene-reports-by-id {:gene_ids [(parse-long (str gene-ids-or-id))]} :ncbi/gene)))

(defn biosample [client accessions-or-accession]
  (if (string? accessions-or-accession)
    (d/fetch-one client :bio-sample-dataset-report {:accessions [accessions-or-accession]} :ncbi/biosample)
    (d/fetch client :bio-sample-dataset-report {:accessions accessions-or-accession} :ncbi/biosample)))

(defn sequences [client assembly-accession]
  (d/fetch client :genome-sequence-report {:accession assembly-accession} :ncbi/sequence))

(defn gene-products [client gene-ids-or-id]
  (if (sequential? gene-ids-or-id)
    (d/fetch client :gene-product-reports-by-id {:gene-ids (mapv #(parse-long (str %)) gene-ids-or-id)} :ncbi/gene-product)
    (d/fetch-one client :gene-product-reports-by-id {:gene-ids [(parse-long (str gene-ids-or-id))]} :ncbi/gene-product)))

(defn annotations [client assembly-accession]
  (d/fetch client :genome-annotation-report {:accession assembly-accession} :ncbi/annotation))

(defn virus [client taxon]
  (d/fetch client :virus-reports-by-taxon {:taxon (str taxon)} :ncbi/virus))

(defn virus-by-accession [client accessions-or-accession]
  (if (string? accessions-or-accession)
    (d/fetch-one client :virus-reports-by-acessions {:accessions [accessions-or-accession]} :ncbi/virus)
    (d/fetch client :virus-reports-by-acessions {:accessions accessions-or-accession} :ncbi/virus)))

(defn virus-annotations [client taxon]
  (d/fetch client :virus-annotation-reports-by-taxon {:taxon (str taxon)} :ncbi/virus-annotation))

(defn virus-annotations-by-accession [client accessions-or-accession]
  (if (string? accessions-or-accession)
    (d/fetch-one client :virus-annotation-reports-by-acessions {:accessions [accessions-or-accession]} :ncbi/virus-annotation)
    (d/fetch client :virus-annotation-reports-by-acessions {:accessions accessions-or-accession} :ncbi/virus-annotation)))
