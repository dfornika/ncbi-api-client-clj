(ns ncbi-api-client.datafy
  "Generic datafy/nav framework for entity navigation.
   API-specific code (Datasets, eutils, etc.) plugs in via nav-edges on the client map."
  (:require [clojure.core.protocols :as p]))

;; --- Nav graph interpreter ---

(defn- nav-keys-for
  [client entity-type]
  (into #{}
        (comp (filter (fn [[[et _] _]] (= et entity-type)))
              (map (fn [[[_ nk] _]] nk)))
        (:nav-edges client)))

(defn- resolve-nav [client entity-type coll k v]
  (let [edges (:nav-edges client)]
    (if-let [edge (get edges [entity-type k])]
      (if-let [nav-fn (:nav-fn edge)]
        (nav-fn client coll)
        (when-let [id ((:id edge) coll)]
          ((:fetch-fn edge) client (:op edge) ((:params edge) id) (:type edge))))
      v)))

;; --- Entity tagging ---

(defn tag-entity [client entity-type data]
  (when data
    (with-meta data
      {`p/datafy (fn [this]
                   (let [nav-ks (nav-keys-for client entity-type)]
                     (-> (reduce (fn [d k] (assoc d k :deferred)) this nav-ks)
                         (with-meta
                           (merge (meta this)
                                  {`p/nav    (fn [this' k v] (resolve-nav client entity-type this' k v))
                                   :ncbi/type   entity-type
                                   :ncbi/client client})))))
       `p/nav    (fn [this k v] (resolve-nav client entity-type this k v))
       :ncbi/type   entity-type
       :ncbi/client client})))
