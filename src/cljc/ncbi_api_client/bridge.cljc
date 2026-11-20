(ns ncbi-api-client.bridge
  "E-utilities records as entities: search results (esummary records)
  that navigate along links between Entrez databases, and into the
  Datasets entity graph.

  `datafy` on a record makes no request. It shows:
  - `:ncbi.nav/datasets-entity`, for databases Datasets has (gene,
    taxonomy, assembly, biosample);
  - a `:ncbi.elink/<linkname>` key for each link in `links` for its
    database;
  - `:ncbi.elink/available`, which asks NCBI for every link the record
    has."
  (:require [clojure.core.protocols :as p]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.eutils :as eu]
            [ncbi-api-client.nav :as nav]
            [ncbi-api-client.plan :as plan]))

(def ^:private db->datasets
  {"gene"      {:entity-type :ncbi/gene
                :operation   :gene-reports-by-id
                :id-key      :gene_ids
                :parse-id    parse-long}
   "taxonomy"  {:entity-type :ncbi/taxonomy
                :operation   :taxonomy-data-report
                :id-key      :taxons
                :parse-id    str}
   "assembly"  {:entity-type :ncbi/assembly
                :operation   :genome-dataset-report
                :id-key      :accessions
                :id-from     :assemblyaccession}
   "biosample" {:entity-type :ncbi/biosample
                :operation   :bio-sample-dataset-report
                :id-key      :accessions
                :id-from     :biosampleaccn}})

(def links
  "The links between Entrez databases each record shows as a nav key,
  without asking NCBI: database -> linknames. `:ncbi.elink/available`
  finds the rest."
  {"biosample"  ["biosample_sra"]
   "sra"        ["sra_bioproject"]
   "bioproject" ["bioproject_assembly"]
   "assembly"   ["assembly_biosample"]
   "taxonomy"   ["taxonomy_assembly"]
   "gene"       ["gene_pubmed" "gene_nuccore"]})

(def max-link-ids
  "How many linked IDs one hop fetches. The total is in the result's
  metadata (:ncbi.elink/total-count)."
  200)

(declare tag-result)

(defn- link-plan
  "A plan for the records linked to `uid` in `db` by `linkname`: Datasets
  entities when the other database is one Datasets has by UID (gene,
  taxonomy), otherwise esummary records."
  [client db uid linkname]
  (plan/then
   (eu/elink db uid {:linkname linkname})
   (fn [results]
     (let [{:keys [ids dbto]} (first results)
           ids       (take max-link-ids ids)
           link-meta {:ncbi.elink/total-count (count (:ids (first results)))
                      :ncbi.elink/linkname    linkname
                      :ncbi.elink/dbto        dbto}
           with-link (fn [v] (vary-meta v merge link-meta))
           {:keys [entity-type operation id-key parse-id]} (db->datasets dbto)]
       (cond
         (empty? ids) (with-meta [] link-meta)
         parse-id     (plan/then (ds/page-plan client operation {id-key (mapv parse-id ids)} entity-type)
                                 with-link)
         :else        (plan/then (eu/esummary dbto ids)
                                 #(with-meta (mapv (partial tag-result client dbto) %) link-meta)))))))

(defn- datasets-entity-plan [client db uid record]
  (when-let [{:keys [entity-type operation id-key id-from parse-id]} (db->datasets db)]
    (when-let [id (if id-from (get record id-from) ((or parse-id str) uid))]
      (ds/one-plan client operation {id-key [id]} entity-type))))

(defn- link-key [linkname] (keyword "ncbi.elink" linkname))

(defn- nav-link [client db uid k]
  (nav/follow client k (str (name k) " of " db " " uid) (link-plan client db uid (name k))))

(defn- available-plan
  "A plan for a map of every link `uid` in `db` has: :ncbi.elink/<linkname>
  -> {:dbto :menutag}, where nav on a key follows the link."
  [client db uid]
  (plan/then
   (eu/elink-available db uid)
   (fn [infos]
     (with-meta (into (sorted-map)
                      (map (fn [{:keys [linkname] :as info}]
                             [(link-key linkname) (select-keys info [:dbto :menutag])]))
                      infos)
       {`p/nav (fn [_ k v]
                 (if (= "ncbi.elink" (namespace k))
                   (nav-link client db uid k)
                   v))}))))

(defn- nav-result [client db this k v]
  (let [uid (:uid this)
        d   (str (name k) " of " db " " uid)]
    (cond
      (= k :ncbi.nav/datasets-entity) (nav/follow client k d (datasets-entity-plan client db uid this))
      (= k :ncbi.elink/available)     (nav/follow client k d (available-plan client db uid))
      (and (= "ncbi.elink" (namespace k)) (= v :deferred)) (nav-link client db uid k)
      :else v)))

(defn tag-result
  "`summary`, an esummary record from `db`, as a navigable entity."
  [client db summary]
  (let [nav-fn (fn [this k v] (nav-result client db this k v))]
    (with-meta summary
      {`p/datafy
       (fn [this]
         (-> (into this (map (fn [l] [(link-key l) :deferred])) (get links db))
             (assoc :ncbi.elink/available :deferred)
             (cond-> (db->datasets db) (assoc :ncbi.nav/datasets-entity :deferred))
             (with-meta (assoc (meta this) `p/nav nav-fn))))
       `p/nav          nav-fn
       :ncbi/type      :ncbi.eutils/result
       :ncbi/label     (:uid summary)
       :ncbi/client    client
       :ncbi.eutils/db db})))

(defn search-plan
  "A plan for a page of search results from Entrez database `db` for
  `term`: esearch, then esummary of the IDs, as a vector of navigable
  records (`tag-result`) with :async.nav/total and :async.nav/next.
  Options: :retmax, :retstart, :sort, :field (as `eutils/esearch`)."
  [client db term & [opts]]
  (plan/then
   (eu/esearch db term opts)
   (fn [{:keys [ids count retmax retstart]}]
     (plan/then
      (if (seq ids) (eu/esummary db ids) [])
      (fn [summaries]
        (let [next-start (+ (or retstart 0) (clojure.core/count ids))]
          (nav/page client
                    (map #(tag-result client db %) summaries)
                    {:total     count
                     :next-plan (when (and count (seq ids) (< next-start count))
                                  (search-plan client db term (assoc opts :retstart next-start)))
                     :describe  (str "next page of " db " search " term)}
                    {:ncbi/total-count count
                     :ncbi/retmax      retmax
                     :ncbi/retstart    retstart
                     :ncbi/type        :ncbi.eutils/results
                     :ncbi.eutils/db   db})))))))

#?(:clj
   (defn search
     "Search an Entrez database by keyword: a page of navigable records (see
     `search-plan`)."
     [client db term & [opts]]
     (nav/run client (search-plan client db term opts))))
