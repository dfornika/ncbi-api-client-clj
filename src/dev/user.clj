(ns user
  (:require [ncbi-api-client.core :as ncbi]
            [clojure.datafy :refer [datafy nav]]
            [martian.core :as martian]))

(def client (ncbi/connect))

(comment
  ;; Load the demo namespace for example functions
  (require '[demo :reload true])

  ;; Then call demo functions with the client, e.g.:
  ;; (demo/taxonomy-summary client "9606")
  ;; (demo/gene-orthologs client 672 :limit 10)
  ;; (demo/lineage-names client "2697049")
  ;; (demo/ortholog-assemblies client 672 "Mus musculus")
  )
