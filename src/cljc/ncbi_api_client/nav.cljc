(ns ncbi-api-client.nav
  "Generic datafy/nav: entities and pages that know how to reach what's
  related to them, as plans.

  An entity is tagged with its type and the client. `datafy` on it adds a
  `:deferred` value for each nav key its type has in the client's
  :nav-edges, without making a request. An edge is `{:plan (fn [client
  entity] plan-or-nil)}`; `nav` on its key follows the plan:

  - on the JVM, it runs the plan with the client's :run, and returns the
    value;
  - in the browser, it returns a deferred value (`deferred`), so that `nav`
    stays cheap and has no side effects, and the consumer fetches it when
    someone chooses it.

  A page is a vector of entities whose metadata says how to get more:
  `:async.nav/next`, a deferred value for the next page, and
  `:async.nav/total`. These keys are neutral, so other clients (ENA,
  LAPIS) can use them too."
  (:require [clojure.core.protocols :as p]
            [ncbi-api-client.plan :as plan]))

;; --- Deferred values ---

(defn run
  "Run `plan` with `client`'s runner: its value on the JVM, a promise of it
  in the browser."
  [client plan]
  (if-let [f (:run client)]
    (f client plan)
    (throw (ex-info "This client has no :run" {:ncbi/error :no-runner}))))

(defn deferred
  "A value that says what `plan` would fetch, and fetches it when asked:

  - the map shows `k`, a description and the plan's first request (which
    never holds the API key), for EDN views;
  - its metadata has `:async.nav/fetch`, a function of no arguments that
    runs the plan (a promise in the browser, the value on the JVM), and
    `:async.nav/key`, plain data that is equal for equal requests, for a
    consumer to remember what it has already fetched."
  [client k describe plan]
  (with-meta {:ncbi/deferred k
              :ncbi/describe describe
              :ncbi/request  (:request plan)}
    {:async.nav/fetch (fn [] (run client plan))
     :async.nav/key   [k (:request plan)]}))

(defn follow
  "What `nav` gives for `x`, an edge's plan (or a value, or nil): on the JVM
  the plan's value, in the browser a deferred value for it."
  [client k describe x]
  (if (plan/plan? x)
    #?(:clj  (run client x)
       :cljs (deferred client k describe x))
    x))

;; --- Entities ---

(defn- nav-keys-for [client entity-type]
  (into #{}
        (keep (fn [[[et nk] _]] (when (= et entity-type) nk)))
        (:nav-edges client)))

(defn- describe [k entity-type label]
  (str (name k) " of " (name entity-type) (when label (str " " label))))

(defn- nav-entity [client entity-type label entity k v]
  (if-let [edge (get (:nav-edges client) [entity-type k])]
    (follow client k (describe k entity-type label) ((:plan edge) client entity))
    v))

(defn tag-entity
  "`data` as an entity of `entity-type`: datafy shows its nav keys, and nav
  follows them. `label` (an ID, say) goes into descriptions of where its
  nav keys lead."
  ([client entity-type data] (tag-entity client entity-type nil data))
  ([client entity-type label data]
   (when data
     (let [nav-fn (fn [this k v] (nav-entity client entity-type label this k v))]
       (with-meta data
         {`p/datafy   (fn [this]
                        (-> (reduce #(assoc %1 %2 :deferred) this (nav-keys-for client entity-type))
                            (with-meta (assoc (meta this) `p/nav nav-fn))))
          `p/nav      nav-fn
          :ncbi/type   entity-type
          :ncbi/label  label
          :ncbi/client client})))))

;; --- Pages ---

(defn page
  "`items` as a page: a vector whose metadata has `:async.nav/total` (when
  `total` is known) and, when `next-plan` is given, `:async.nav/next`, a
  deferred value for the next page. datafy on the page also shows
  `:ncbi.nav/next-page :deferred` in its metadata, and nav follows it. `m`
  is more metadata."
  [client items {:keys [total next-plan describe]} m]
  (let [next-page (when next-plan (deferred client :ncbi.nav/next-page describe next-plan))]
    (with-meta (vec items)
      (cond-> (merge m {`p/datafy (fn [this]
                                    (with-meta this
                                      (cond-> (meta this)
                                        next-plan (assoc :ncbi.nav/next-page :deferred))))
                        `p/nav    (fn [_ k v]
                                    (if (= k :ncbi.nav/next-page)
                                      (follow client k describe next-plan)
                                      v))})
        total     (assoc :async.nav/total total)
        next-page (assoc :async.nav/next next-page)))))
