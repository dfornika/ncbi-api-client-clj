(ns ncbi-api-client.portable
  (:require [ncbi-api-client.nav-keys :as nk]
            [martian.core :as martian]))

;; --- Report extraction ---

(def ^:private report-extractors
  {:ncbi/taxonomy :taxonomy})

(defn- extract-report [entity-type report]
  (let [extractor (get report-extractors entity-type identity)]
    (if (keyword? extractor)
      (get report extractor)
      (extractor report))))

;; --- Forward declarations ---

(declare datafy-entity nav-entity fetch)

;; --- Entity tagging ---

(defn tag-entity [client entity-type data]
  (when data
    (nk/with-datafy-nav data
      (fn [this] (datafy-entity client entity-type this))
      (fn [this k v] (nav-entity client entity-type this k v))
      {:ncbi/type   entity-type
       :ncbi/client client})))

;; --- Fetch ---

(defn fetch [client operation params entity-type]
  (let [response (martian/response-for client operation params)
        body     (:body response)
        reports  (or (:reports body) [])
        tagged   (mapv #(tag-entity client entity-type (extract-report entity-type %)) reports)
        token    (:next_page_token body)]
    (nk/with-datafy-nav tagged
      (fn [this]
        (if token
          (nk/with-datafy-nav (conj this [:ncbi.nav/next-page :deferred])
            (fn [t] t) (fn [_ _ v] v) (meta this))
          this))
      (fn [_this k _v]
        (when (= k :ncbi.nav/next-page)
          (when token
            (fetch client operation (assoc params :page_token token) entity-type))))
      {:ncbi/total-count     (:total_count body)
       :ncbi/next-page-token token
       :ncbi/operation       operation
       :ncbi/params          params
       :ncbi/entity-type     entity-type})))

(defn fetch-one [client operation params entity-type]
  (first (fetch client operation params entity-type)))

;; --- datafy / nav for taxonomy ---

(defn- datafy-entity [client entity-type data]
  (case entity-type
    :ncbi/taxonomy
    (nk/with-datafy-nav
      (assoc data :ncbi.nav/assemblies :deferred
             :ncbi.nav/children   :deferred
             :ncbi.nav/lineage    :deferred)
      (fn [this] this)
      (fn [this k v] (nav-entity client entity-type this k v))
      {:ncbi/type   entity-type
       :ncbi/client client})
    data))

(defn- nav-entity [client entity-type coll k _v]
  (case [entity-type k]
    [:ncbi/taxonomy :ncbi.nav/assemblies]
    (let [tax-id (str (:tax_id coll))]
      (fetch client :genome-dataset-reports-by-taxon {:taxons [tax-id]} :ncbi/assembly))

    [:ncbi/taxonomy :ncbi.nav/children]
    (let [child-ids (mapv str (:children coll))]
      (when (seq child-ids)
        (fetch client :taxonomy-data-report {:taxons child-ids} :ncbi/taxonomy)))

    [:ncbi/taxonomy :ncbi.nav/lineage]
    (let [parent-ids (mapv str (:parents coll))]
      (when (seq parent-ids)
        (fetch client :taxonomy-data-report {:taxons parent-ids} :ncbi/taxonomy)))

    _v))

;; --- Public API ---

(defn taxonomy [client taxon-ids-or-id]
  (if (string? taxon-ids-or-id)
    (fetch-one client :taxonomy-data-report {:taxons [taxon-ids-or-id]} :ncbi/taxonomy)
    (fetch client :taxonomy-data-report {:taxons taxon-ids-or-id} :ncbi/taxonomy)))
