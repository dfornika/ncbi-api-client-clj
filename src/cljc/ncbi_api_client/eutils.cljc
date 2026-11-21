(ns ncbi-api-client.eutils
  "NCBI's E-utilities, as plans (ncbi-api-client.plan): einfo, esearch,
  esummary, elink and efetch, and `search-summaries` (esearch then esummary). Each
  function describes the requests and how to read the answers; a runner
  makes them. `ncbi-api-client.core` has the same functions taking a
  client, which run them.

  Requests are POSTed forms, asking for JSON."
  (:require [clojure.string :as str]
            [ncbi-api-client.plan :as plan]
            [ncbi-api-client.request :as req]))

(defn- id-list [ids]
  (if (sequential? ids)
    (str/join "," (map str ids))
    (str ids)))

;; --- Reading answers ---

(defn read-esearch
  "An esearch answer as {:ids :count :retmax :retstart}, and :history
  ({:webenv :query-key}) when it was asked to keep the results on NCBI's
  history server."
  [body]
  (let [result (:esearchresult body)]
    (cond-> {:ids      (vec (:idlist result))
             :count    (some-> (:count result) str parse-long)
             :retmax   (some-> (:retmax result) str parse-long)
             :retstart (some-> (:retstart result) str parse-long)}
      (:webenv result) (assoc :history {:webenv    (:webenv result)
                                        :query-key (str (:querykey result))}))))

(defn read-esummary
  "An esummary answer's documents, in the order of its :uids."
  [body]
  (let [result (:result body)]
    (into [] (keep #(get result (keyword %))) (:uids result))))

(defn read-elink
  "An elink answer's links: {:dbto :linkname :ids} for each, across all
  linksets."
  [body]
  (mapv (fn [ls] {:dbto (:dbto ls) :linkname (:linkname ls) :ids (vec (:links ls))})
        (mapcat :linksetdbs (:linksets body))))

(defn read-elink-available
  "An elink acheck answer's link types: {:linkname :dbto :menutag} for each,
  without repeats."
  [body]
  (->> (:linksets body)
       (mapcat #(get-in % [:idchecklist :idlinksets]))
       (mapcat :linkinfos)
       distinct
       (mapv #(select-keys % [:linkname :dbto :menutag]))))

;; --- Plans ---

(defn einfo
  "A plan for the list of Entrez databases' names, or (with `db`) that
  database's metadata: its fields, links and count."
  ([] (plan/plan (req/eutils :einfo {}) #(get-in % [:einforesult :dblist])))
  ([db] (plan/plan (req/eutils :einfo {:db db}) #(first (get-in % [:einforesult :dbinfo])))))

(defn esearch
  "A plan to search database `db` for `term`: {:ids :count :retmax :retstart}
  (and :history, with :usehistory).
  Options:
    :retmax     - how many ids (default 20, at most 10000)
    :retstart   - where in the results to start (default 0)
    :sort       - sort order (depends on the database)
    :field      - search only this field
    :usehistory - keep the results on NCBI's history server, for
                  `esummary-history` to page through"
  ([db term] (esearch db term nil))
  ([db term {:keys [retmax retstart sort field usehistory]}]
   (plan/plan (req/eutils :esearch {:db db :term term :retmax retmax
                                    :retstart retstart :sort sort :field field
                                    :usehistory (when usehistory "y")})
              read-esearch)))

(defn esummary
  "A plan for the summaries of `ids` (one, or a list) in `db`, in the order
  NCBI gives them."
  [db ids]
  (plan/plan (req/eutils :esummary {:db db :id (id-list ids)}) read-esummary))

(defn history-gone?
  "Whether an esummary answer says the history it was asked from is gone
  (it expires after a while): {\"esummaryresult\": [\"Unable to obtain
  query #1\"]}, with no result."
  [body]
  (boolean (and (:esummaryresult body) (not (:result body)))))

(defn esummary-history
  "A plan for a page of summaries of search results kept on the history
  server (`history`, from `esearch` with :usehistory): `retmax` of them,
  from `retstart`. Its value is the summaries, or :ncbi.eutils/history-gone
  if the history has expired."
  [db {:keys [webenv query-key]} retstart retmax]
  (plan/plan (req/eutils :esummary {:db db :WebEnv webenv :query_key query-key
                                    :retstart retstart :retmax retmax})
             #(if (history-gone? %) :ncbi.eutils/history-gone (read-esummary %))))

(defn read-elink-each
  "An elink answer for several ids, each in a linkset of its own: {:dbto
  :links {id [linked id ...]}}."
  [body]
  {:dbto  (some :dbto (mapcat :linksetdbs (:linksets body)))
   :links (into {} (for [ls (:linksets body)]
                     [(str (first (:ids ls))) (vec (mapcat :links (:linksetdbs ls)))]))})

(defn elink-each
  "A plan for what each of `ids` (in `dbfrom`) links to by `linkname`,
  separately: {:dbto :links {id [linked id ...]}} (see `read-elink-each`)."
  [dbfrom ids linkname]
  (plan/plan (req/eutils :elink {:dbfrom dbfrom :id (mapv str ids) :linkname linkname})
             read-elink-each))

(defn elink
  "A plan for the ids in other databases that `ids` (in `dbfrom`) link to:
  a vector of {:dbto :linkname :ids}, across all the ids asked about.
  Options:
    :db       - the database to link to
    :linkname - one kind of link (e.g. \"gene_pubmed\"): faster
    :cmd      - elink's command (default \"neighbor\")"
  ([dbfrom ids] (elink dbfrom ids nil))
  ([dbfrom ids {:keys [db linkname cmd]}]
   (plan/plan (req/eutils :elink {:dbfrom dbfrom :id (id-list ids) :db db
                                  :linkname linkname :cmd cmd})
              read-elink)))

(defn elink-available
  "A plan for the kinds of link there are from `ids` in `dbfrom`: a vector
  of {:linkname :dbto :menutag}."
  [dbfrom ids]
  (plan/plan (req/eutils :elink {:dbfrom dbfrom :id (id-list ids) :cmd "acheck"})
             read-elink-available))

(defn efetch
  "A plan for the records `ids` (one, or a list) of `db` as text, in
  format `rettype` (\"fasta\" by default): FASTA is text, read as it is."
  ([db ids] (efetch db ids nil))
  ([db ids {:keys [rettype] :or {rettype "fasta"}}]
   (plan/plan (req/eutils :efetch {:db db :id (id-list ids) :rettype rettype :retmode "text"}))))

(defn search-summaries
  "A plan to search `db` for `term` and summarize what's found, in one:
  {:count :summaries}, plain maps. Options as `esearch`'s. (For navigable
  records, see `ncbi-api-client.core/search`.)"
  ([db term] (search-summaries db term nil))
  ([db term opts]
   (plan/then (esearch db term opts)
              (fn [{:keys [ids count]}]
                (if (seq ids)
                  (plan/then (esummary db ids) (fn [docs] {:count count :summaries docs}))
                  {:count count :summaries []})))))
