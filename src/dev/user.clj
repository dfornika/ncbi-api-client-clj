(ns user
  (:require [ncbi-api-client.core :as ncbi]
            [clojure.datafy :refer [datafy nav]]
            [martian.core :as martian]))

(def client (ncbi/connect))

(comment
  ;; List all available API operations
  (martian/explore client)

  ;; Filter operations by name
  (filter #(clojure.string/includes? (name (first %)) "taxon")
          (martian/explore client))

  ;; Fetch taxonomy for SARS-CoV-2
  (def sars2 (first (ncbi/taxonomy client ["2697049"])))
  sars2

  ;; datafy reveals navigation links
  (def sars2-d (datafy sars2))
  (keys sars2-d)

  ;; Navigate from taxonomy to genome assemblies (triggers API call)
  (def assemblies (nav sars2-d :ncbi.nav/assemblies :deferred))
  (first assemblies)

  ;; Each assembly is itself navigable
  (def asm (datafy (first assemblies)))
  (keys asm)

  ;; Navigate from assembly back to its organism taxonomy
  (def organism (nav asm :ncbi.nav/organism :deferred))
  organism

  ;; Navigate to child taxa
  (def children (nav sars2-d :ncbi.nav/children :deferred))
  children

  ;; Navigate to parent lineage
  (def lineage (nav sars2-d :ncbi.nav/lineage :deferred))
  lineage

  ;; Fetch assemblies directly by accession
  (def grch38 (first (ncbi/assembly client ["GCF_000001405.40"])))
  (datafy grch38))
