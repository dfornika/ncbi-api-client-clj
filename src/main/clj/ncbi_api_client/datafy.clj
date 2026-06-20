(ns ncbi-api-client.datafy
  (:require [clojure.core.protocols :as p]
            [martian.core :as martian]))

;; --- Report extraction ---

(def ^:private report-extractors
  {:ncbi/taxonomy :taxonomy
   :ncbi/assembly identity})

(defn- extract-report [entity-type report]
  (let [extractor (get report-extractors entity-type identity)]
    (if (keyword? extractor)
      (get report extractor)
      (extractor report))))

;; --- Forward declarations ---

(declare datafy-entity nav-entity)

;; --- Entity tagging ---

(defn tag-entity [client entity-type data]
  (when data
    (with-meta data
      {`p/datafy (fn [this] (datafy-entity client entity-type this))
       `p/nav    (fn [this k v] (nav-entity client entity-type this k v))
       :ncbi/type   entity-type
       :ncbi/client client})))

;; --- Fetch helpers ---

(defn fetch [client operation params entity-type]
  (let [response (martian/response-for client operation params)
        body     (:body response)
        reports  (or (:reports body) [])
        tagged   (mapv #(tag-entity client entity-type (extract-report entity-type %)) reports)]
    (with-meta tagged
      {:ncbi/total-count     (:total_count body)
       :ncbi/next-page-token (:next_page_token body)
       :ncbi/operation       operation
       :ncbi/params          params})))

(defn fetch-one [client operation params entity-type]
  (first (fetch client operation params entity-type)))

(defn fetch-all [client operation params entity-type]
  (let [page (fetch client operation params entity-type)]
    (lazy-cat page
              (when-let [token (:ncbi/next-page-token (meta page))]
                (fetch-all client operation (assoc params :page_token token) entity-type)))))

;; --- datafy multimethod ---

(defmulti datafy-entity (fn [_client entity-type _data] entity-type))

(defmethod datafy-entity :ncbi/taxonomy
  [client _ data]
  (-> data
      (assoc :ncbi.nav/assemblies :deferred
             :ncbi.nav/children   :deferred
             :ncbi.nav/lineage    :deferred)
      (with-meta
        {`p/nav    (fn [this k v] (nav-entity client :ncbi/taxonomy this k v))
         :ncbi/type   :ncbi/taxonomy
         :ncbi/client client})))

(defmethod datafy-entity :ncbi/assembly
  [client _ data]
  (-> data
      (assoc :ncbi.nav/organism :deferred)
      (with-meta
        {`p/nav    (fn [this k v] (nav-entity client :ncbi/assembly this k v))
         :ncbi/type   :ncbi/assembly
         :ncbi/client client})))

(defmethod datafy-entity :default
  [_client _entity-type data]
  data)

;; --- nav multimethod ---

(defmulti nav-entity (fn [_client entity-type _coll k _v] [entity-type k]))

(defmethod nav-entity [:ncbi/taxonomy :ncbi.nav/assemblies]
  [client _ coll _ _]
  (let [tax-id (str (:tax_id coll))]
    (fetch-all client :genome-dataset-reports-by-taxon {:taxons [tax-id]} :ncbi/assembly)))

(defmethod nav-entity [:ncbi/taxonomy :ncbi.nav/children]
  [client _ coll _ _]
  (let [child-ids (mapv str (:children coll))]
    (when (seq child-ids)
      (fetch client :taxonomy-data-report {:taxons child-ids} :ncbi/taxonomy))))

(defmethod nav-entity [:ncbi/taxonomy :ncbi.nav/lineage]
  [client _ coll _ _]
  (let [parent-ids (mapv str (:parents coll))]
    (when (seq parent-ids)
      (fetch client :taxonomy-data-report {:taxons parent-ids} :ncbi/taxonomy))))

(defmethod nav-entity [:ncbi/assembly :ncbi.nav/organism]
  [client _ coll _ _]
  (let [tax-id (str (get-in coll [:organism :tax_id]))]
    (fetch-one client :taxonomy-data-report {:taxons [tax-id]} :ncbi/taxonomy)))

(defmethod nav-entity :default
  [_ _ _ _ v]
  v)
