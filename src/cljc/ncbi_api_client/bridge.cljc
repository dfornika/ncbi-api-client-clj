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
  "How many linked records a page has. The total is in the page's
  metadata (:ncbi.elink/total-count, and :async.nav/total); for records
  summarized from E-utilities, :async.nav/next leads to the next page."
  200)

(declare tag-result)

(defn- summaries-page-plan
  "A plan for a page of the `db` records `ids` (all of them, in a vector),
  from `start`: `max-link-ids` of them, summarized, with the next page as
  :async.nav/next. `m` is more metadata."
  [client db ids start m]
  (let [end (min (count ids) (+ start max-link-ids))]
    (plan/then (eu/esummary db (subvec ids start end))
               (fn [docs]
                 (nav/page client (map #(tag-result client db %) docs)
                           {:total     (count ids)
                            :next-plan (when (< end (count ids))
                                         (summaries-page-plan client db ids end m))
                            :describe  (str "next page of " (or (:ncbi.elink/linkname m) db))}
                           m)))))

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
         (empty? ids) (nav/page client [] {:total 0} link-meta)
         parse-id     (plan/then (ds/page-plan client operation {id-key (mapv parse-id ids)} entity-type)
                                 with-link)
         :else        (summaries-page-plan client dbto (vec (:ids (first results))) 0 link-meta))))))

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

(declare search-plan history-page-plan)

(defn- results-page
  "A page of search results: `summaries` (from `db`) as records, from
  `start` of `total`, leading to the next page from `next-start`."
  [client db term opts summaries {:keys [total start next-start retmax history]}]
  (nav/page client
            (map #(tag-result client db %) summaries)
            {:total     total
             :next-plan (when (and total (< next-start total))
                          (if history
                            (history-page-plan client db term opts history total next-start)
                            (search-plan client db term (assoc opts :retstart next-start))))
             :describe  (str "next page of " db " search " term)}
            {:ncbi/total-count total
             :ncbi/retmax      retmax
             :ncbi/retstart    start
             :ncbi/type        :ncbi.eutils/results
             :ncbi.eutils/db   db}))

(defn- history-page-plan
  "A plan for the page of a search's results from `start`, summarized from
  NCBI's history server. If the history has expired, the search is made
  again, from `start`."
  [client db term opts history total start]
  (let [retmax (or (:retmax opts) 20)]
    (plan/then (eu/esummary-history db history start retmax)
               (fn [summaries]
                 (if (= :ncbi.eutils/history-gone summaries)
                   (search-plan client db term (assoc opts :retstart start))
                   (results-page client db term opts summaries
                                 {:total total :start start :next-start (+ start retmax)
                                  :retmax retmax :history history}))))))

(defn search-plan
  "A plan for a page of search results from Entrez database `db` for
  `term`: esearch, then esummary of the IDs, as a vector of navigable
  records (`tag-result`) with :async.nav/total and :async.nav/next. The
  search is kept on NCBI's history server, so later pages are each one
  esummary, without searching again (unless the history has expired).
  Options: :retmax, :retstart, :sort, :field (as `eutils/esearch`)."
  [client db term & [opts]]
  (plan/then
   (eu/esearch db term (assoc opts :usehistory true))
   (fn [{:keys [ids count retmax retstart history]}]
     (plan/then
      (if (seq ids) (eu/esummary db ids) [])
      (fn [summaries]
        (let [start (or retstart 0)]
          (results-page client db term opts summaries
                        {:total count :start start
                         :next-start (+ start (clojure.core/count ids))
                         :retmax retmax
                         :history (when (seq ids) history)})))))))

;; --- Links of many records at once ---

(defn- in-chunks
  "A plan that runs `(plan-fn chunk)` for each chunk of `xs` (`n` to a
  chunk), one after another, combining their values with `combine`,
  starting from `init`."
  [n xs plan-fn combine init]
  (letfn [(step [acc chunks]
            (if-let [c (first chunks)]
              (plan/then (plan-fn (vec c)) #(step (combine acc %) (rest chunks)))
              acc))]
    (step init (partition-all n xs))))

(defn links-plan
  "A plan for what each of `records` links to by `linkname` (from the
  records' own database: \"biosample_sra\" for BioSamples): a vector, in
  the order of `records`, of vectors of linked records (`tag-result`),
  each with :ncbi.elink/total-count, :ncbi.elink/linkname and
  :ncbi.elink/dbto in its metadata. At most `max-link-ids` for each
  record; a record without a :uid (one `lookup` didn't find) links to
  none. `records` are E-utilities records of one database, as `search`
  and `core/lookup` give them; 200 go in a request."
  [client records linkname]
  (let [db   (some #(or (:ncbi.eutils/db (meta %)) (:ncbi.eutils/db %)) records)
        uids (vec (distinct (keep :uid records)))]
    (plan/then
     (in-chunks 200 uids #(eu/elink-each db % linkname)
                (fn [a b] {:dbto (or (:dbto a) (:dbto b)) :links (merge (:links a) (:links b))})
                {})
     (fn [{:keys [dbto links]}]
       (let [capped   (into {} (map (fn [[uid ids]] [uid (vec (take max-link-ids ids))])) links)
             linked   (vec (distinct (mapcat val capped)))]
         (plan/then
          (in-chunks 200 linked
                     (fn [ids] (plan/then (eu/esummary dbto ids)
                                          (fn [docs] (into {} (map (juxt :uid identity)) docs))))
                     merge {})
          (fn [docs]
            (mapv (fn [r]
                    (let [uid (:uid r)]
                      (with-meta (vec (keep #(some->> (get docs %) (tag-result client dbto))
                                            (get capped uid)))
                        {:ncbi.elink/total-count (count (get links uid))
                         :ncbi.elink/linkname    linkname
                         :ncbi.elink/dbto        dbto})))
                  records))))))))

;; --- From Datasets entities into Entrez ---

(defn- sra-of-biosample-plan
  "A plan for the SRA records of the BioSample with `accession`: its UID
  (esearch), its links to SRA, and a page of them."
  [client accession]
  (plan/then
   (eu/esearch "biosample" accession {:retmax 1})
   (fn [{:keys [ids]}]
     (if-let [uid (first ids)]
       (link-plan client "biosample" uid "biosample_sra")
       (nav/page client [] {:total 0} {:ncbi.elink/linkname "biosample_sra" :ncbi.elink/dbto "sra"})))))

(def nav-edges
  "Edges from Datasets entities into E-utilities records."
  {[:ncbi/biosample :ncbi.nav/sra]
   {:plan (fn [client e]
            (when-let [acc (:accession e)]
              (sra-of-biosample-plan client acc)))}})

#?(:clj
   (defn search
     "Search an Entrez database by keyword: a page of navigable records (see
     `search-plan`)."
     [client db term & [opts]]
     (nav/run client (search-plan client db term opts))))
