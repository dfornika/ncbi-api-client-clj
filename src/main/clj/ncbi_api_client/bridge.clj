(ns ncbi-api-client.bridge
  "Bridge between eutils search/link results and the Datasets nav graph.
   Provides datafy/nav on eutils results so they can navigate into
   Datasets entities (genes, assemblies, taxonomy, etc.)."
  (:require [clojure.core.protocols :as p]
            [ncbi-api-client.datafy :as d]
            [ncbi-api-client.eutils :as eu]))

;; Maps eutils database names to Datasets entity types and the
;; operations needed to fetch them. Only databases that have a
;; corresponding Datasets entity are included.
(def ^:private db->datasets
  {"gene"     {:entity-type :ncbi/gene
               :operation   :gene-reports-by-id
               :id-key      :gene_ids
               :parse-id    parse-long}
   "taxonomy" {:entity-type :ncbi/taxonomy
               :operation   :taxonomy-data-report
               :id-key      :taxons
               :parse-id    str}
   "assembly" {:entity-type :ncbi/assembly
               :operation   :genome-dataset-report
               :id-key      :accessions
               :id-from     :assemblyaccession}
   "biosample" {:entity-type :ncbi/biosample
                :operation   :bio-sample-dataset-report
                :id-key      :accessions
                :id-from     :biosampleaccn}})

(defn- tag-search-result
  "Attach nav metadata to a single esummary doc so it can navigate
   into the Datasets graph."
  [datasets-client eutils-client db summary]
  (let [uid (:uid summary)]
    (with-meta summary
      {`p/datafy
       (fn [this]
         (let [links (eu/elink-available eutils-client db uid)
               link-keys (into {}
                               (map (fn [{:keys [linkname]}]
                                      [(keyword "ncbi.elink" linkname) :deferred]))
                               links)]
           (-> (merge this link-keys)
               (cond-> (db->datasets db)
                 (assoc :ncbi.nav/datasets-entity :deferred))
               (with-meta (meta this)))))

       `p/nav
       (fn [this k v]
         (cond
           (= k :ncbi.nav/datasets-entity)
           (when-let [{:keys [entity-type operation id-key id-from parse-id]} (db->datasets db)]
             (let [lookup-id (if id-from (get this id-from) ((or parse-id str) uid))]
               (when lookup-id
                 (d/fetch-one datasets-client operation {id-key [lookup-id]} entity-type))))

           (and (= (namespace k) "ncbi.elink") (= v :deferred))
           (let [linkname (name k)
                 results  (eu/elink eutils-client db uid {:linkname linkname})
                 all-ids  (:ids (first results))
                 dbto     (:dbto (first results))
                 total    (count all-ids)]
             (when (seq all-ids)
               (if-let [{:keys [entity-type operation id-key parse-id] :as spec} (db->datasets dbto)]
                 (if parse-id
                   (d/fetch datasets-client operation
                            {id-key (mapv parse-id (take 200 all-ids))} entity-type)
                   (eu/esummary eutils-client dbto (take 200 all-ids)))
                 (let [page-ids (take 200 all-ids)
                       summaries (eu/esummary eutils-client dbto page-ids)]
                   (with-meta summaries
                     {:ncbi.elink/total-count total
                      :ncbi.elink/linkname   linkname
                      :ncbi.elink/dbto       dbto})))))

           :else v))

       :ncbi/type    :ncbi.eutils/result
       :ncbi.eutils/db db})))

(defn- tag-search-results
  "Tag a vector of esummary results with nav metadata and pagination."
  [datasets-client eutils-client db results search-meta]
  (with-meta
    (mapv #(tag-search-result datasets-client eutils-client db %) results)
    (merge search-meta
           {:ncbi/type      :ncbi.eutils/results
            :ncbi.eutils/db db})))

(defn search
  "Search an Entrez database by keyword. Returns a navigable vector of results.
   Each result supports datafy to discover available links, and nav to follow
   them — either into Datasets entities or as eutils summaries.

   datasets-client: a Datasets API client from ncbi-api-client.core/connect
   eutils-client:   an eutils client from ncbi-api-client.eutils/create-client
   db:              Entrez database name (\"gene\", \"pubmed\", \"taxonomy\", etc.)
   term:            search query string
   opts:            same as eutils/esearch (:retmax, :retstart, :sort, :field)"
  [datasets-client eutils-client db term & [opts]]
  (let [{:keys [ids count retmax retstart]} (eu/esearch eutils-client db term opts)
        summaries (when (seq ids) (eu/esummary eutils-client db ids))]
    (tag-search-results
     datasets-client eutils-client db (or summaries [])
     {:ncbi/total-count count
      :ncbi/retmax      retmax
      :ncbi/retstart    retstart})))
