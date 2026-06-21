(ns ncbi-api-client.eutils
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hato.client :as hc]))

(def ^:private base-url "https://eutils.ncbi.nlm.nih.gov/entrez/eutils")

(defn create-client
  "Create an eutils client. Options:
    :api-key  - NCBI API key (or set NCBI_API_KEY env var)
    :tool     - tool name sent with every request
    :email    - developer email sent with every request"
  ([] (create-client {}))
  ([{:keys [api-key tool email]}]
   {:http-client (hc/build-http-client {})
    :api-key     (or api-key (System/getenv "NCBI_API_KEY"))
    :tool        (or tool "ncbi-api-client-clj")
    :email       email}))

(defn- request
  "Make a GET request to an eutils endpoint. Returns parsed JSON body."
  [{:keys [http-client api-key tool email]} endpoint params]
  (let [params (cond-> params
                 api-key (assoc :api_key api-key)
                 tool    (assoc :tool tool)
                 email   (assoc :email email)
                 true    (assoc :retmode "json"))]
    (-> (hc/get (str base-url "/" endpoint)
                {:http-client http-client
                 :query-params params
                 :as :text})
        :body
        (json/parse-string true))))

(defn einfo
  "List available databases, or get field/link info for a specific database."
  ([client]
   (let [resp (request client "einfo.fcgi" {})]
     (get-in resp [:einforesult :dblist])))
  ([client db]
   (let [resp (request client "einfo.fcgi" {:db db})]
     (first (get-in resp [:einforesult :dbinfo])))))

(defn esearch
  "Search a database. Returns a map with :ids, :count, :retmax, :retstart.
   Options:
     :retmax   - max results to return (default 20, max 10000)
     :retstart - offset into result set (default 0)
     :sort     - sort order (database-specific)
     :field    - limit search to a specific field"
  [client db term & [{:keys [retmax retstart sort field]}]]
  (let [params (cond-> {:db db :term term}
                 retmax   (assoc :retmax retmax)
                 retstart (assoc :retstart retstart)
                 sort     (assoc :sort sort)
                 field    (assoc :field field))
        resp   (request client "esearch.fcgi" params)
        result (:esearchresult resp)]
    {:ids      (:idlist result)
     :count    (parse-long (:count result))
     :retmax   (parse-long (:retmax result))
     :retstart (parse-long (:retstart result))}))

(defn esummary
  "Fetch document summaries for a list of UIDs. Returns a map of uid -> summary."
  [client db ids]
  (let [id-str (if (sequential? ids)
                 (str/join "," (map str ids))
                 (str ids))
        resp   (request client "esummary.fcgi" {:db db :id id-str})
        result (:result resp)
        uids   (:uids result)]
    (if uids
      (mapv (fn [uid] (get result (keyword uid))) uids)
      [])))

(defn search
  "Search a database and return document summaries in one call.
   Combines esearch + esummary. Options are the same as esearch."
  [client db term & [opts]]
  (let [{:keys [ids count]} (esearch client db term opts)
        summaries (when (seq ids) (esummary client db ids))]
    {:count     count
     :summaries (or summaries [])}))
