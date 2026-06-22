(ns ncbi-api-client.bridge
  "Bridge between eutils search/link results and the Datasets nav graph.
   Provides datafy/nav on eutils results so they can navigate into
   Datasets entities (genes, assemblies, taxonomy, etc.)."
  (:require [clojure.core.protocols :as p]
            [ncbi-api-client.datafy :as d]
            [ncbi-api-client.eutils :as eu]))

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
  [client db summary]
  (let [uid (:uid summary)]
    (with-meta summary
      {`p/datafy
       (fn [this]
         (let [links (eu/elink-available client db uid)
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
                 (d/fetch-one client operation {id-key [lookup-id]} entity-type))))

           (and (= (namespace k) "ncbi.elink") (= v :deferred))
           (let [linkname (name k)
                 results  (eu/elink client db uid {:linkname linkname})
                 all-ids  (:ids (first results))
                 dbto     (:dbto (first results))
                 total    (count all-ids)]
             (when (seq all-ids)
               (let [link-meta {:ncbi.elink/total-count total
                                :ncbi.elink/linkname   linkname
                                :ncbi.elink/dbto       dbto}]
                 (if-let [{:keys [entity-type operation id-key parse-id]} (db->datasets dbto)]
                   (if parse-id
                     (let [result (d/fetch client operation
                                           {id-key (mapv parse-id (take 200 all-ids))} entity-type)]
                       (with-meta result (merge (meta result) link-meta)))
                     (with-meta (eu/esummary client dbto (take 200 all-ids)) link-meta))
                   (with-meta (eu/esummary client dbto (take 200 all-ids)) link-meta)))))

           :else v))

       :ncbi/type     :ncbi.eutils/result
       :ncbi.eutils/db db})))

(defn search
  "Search an Entrez database by keyword. Returns a navigable vector of results.
   Each result supports datafy to discover available links, and nav to follow
   them — either into Datasets entities or as eutils summaries.

   client: a client from ncbi-api-client.core/connect
   db:     Entrez database name (\"gene\", \"pubmed\", \"taxonomy\", etc.)
   term:   search query string
   opts:   :retmax, :retstart, :sort, :field (same as eutils/esearch)"
  [client db term & [opts]]
  (let [{:keys [ids count retmax retstart]} (eu/esearch client db term opts)
        summaries (when (seq ids) (eu/esummary client db ids))]
    (with-meta
      (mapv #(tag-search-result client db %) (or summaries []))
      {:ncbi/total-count count
       :ncbi/retmax      retmax
       :ncbi/retstart    retstart
       :ncbi/type        :ncbi.eutils/results
       :ncbi.eutils/db   db})))
