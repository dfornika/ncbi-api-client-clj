(ns user
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [martian.core :as martian]
            [martian.hato :as martian-http]
            [martian.yaml :as yaml]
            [martian.encoders :as encoders]
            [martian.interceptors :as interceptors]
            [martian.openapi :as openapi]))

(def api-token "")

(def add-api-token
  {:name  ::add-api-token
   :enter (fn [ctx]
            (assoc-in ctx [:request :headers "api-token"] api-token))})

(def ncbi-openapi-spec-url "https://www.ncbi.nlm.nih.gov/datasets/docs/v2/openapi3/openapi3.docs.yaml")

(def ncbi-openapi-spec-file (io/resource "openapi3.docs.yaml"))

(def definition (yaml/cleanup (yaml/yaml->edn (slurp ncbi-openapi-spec-file))))

(def base-url (openapi/base-url nil nil definition))

(def opts (-> martian-http/default-opts
              (update :interceptors conj add-api-token)))

(def m (martian/bootstrap-openapi base-url definition opts))



(comment
  (filter #(str/includes? (name (first %)) "taxon") (martian/explore m))
  (
  (martian/explore m :virus-reports-by-taxon)
  (martian/request-for m :virus-reports-by-taxon {:taxon "2697049"})
  (martian/response-for m :virus-reports-by-taxon {:taxon "2697049"})

  (martian/explore m :taxonomy-data-report)
  (martian/request-for m :taxonomy-data-report {:taxons ["2697049"]})
  (martian/response-for m :taxonomy-data-report {:taxons "2697049"})
  )
