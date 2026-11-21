(ns ncbi-api-client.eutils
  "NCBI's E-utilities, as plans (ncbi-api-client.plan): einfo, esearch,
  esummary and elink, and `search-summaries` (esearch then esummary). Each
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
  "An esearch answer as {:ids :count :retmax :retstart}."
  [body]
  (let [result (:esearchresult body)]
    {:ids      (vec (:idlist result))
     :count    (some-> (:count result) str parse-long)
     :retmax   (some-> (:retmax result) str parse-long)
     :retstart (some-> (:retstart result) str parse-long)}))

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
  "A plan to search database `db` for `term`: {:ids :count :retmax :retstart}.
  Options:
    :retmax   - how many ids (default 20, at most 10000)
    :retstart - where in the results to start (default 0)
    :sort     - sort order (depends on the database)
    :field    - search only this field"
  ([db term] (esearch db term nil))
  ([db term {:keys [retmax retstart sort field]}]
   (plan/plan (req/eutils :esearch {:db db :term term :retmax retmax
                                    :retstart retstart :sort sort :field field})
              read-esearch)))

(defn esummary
  "A plan for the summaries of `ids` (one, or a list) in `db`, in the order
  NCBI gives them."
  [db ids]
  (plan/plan (req/eutils :esummary {:db db :id (id-list ids)}) read-esummary))

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
