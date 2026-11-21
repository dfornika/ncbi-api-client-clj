(ns ncbi-api-client.request
  "Describing requests to the Datasets API and E-utilities, as plain data.

  A request description never holds the API key: the runner adds it when it
  sends the request. So a description can be shown, logged or put in an
  error.

    {:api    :datasets | :eutils
     :op     the operation (:taxonomy-data-report, :esearch...)
     :method :get | :post
     :url    the URL, without its query string
     :query  {\"name\" \"value\" | [\"values\" ...]}   (optional)
     :form   {\"name\" \"value\"}                     (optional: a form body)
     :json   a map                                    (optional: a JSON body)
     :as     :json | :text | :bytes                   (how to read the answer)
     :accept the media type to ask for}"
  (:require [clojure.string :as str]
            [ncbi-api-client.datasets.routes :refer [routes]]))

(def base-urls
  "Where each API is, unless the client says otherwise."
  {:datasets "https://api.ncbi.nlm.nih.gov/datasets/v2"
   :eutils   "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"})

;; --- Encoding ---

(defn url-encode
  "`x` as a string, percent-encoded as JavaScript's encodeURIComponent does,
  on both platforms."
  [x]
  #?(:clj  (-> (java.net.URLEncoder/encode (str x) "UTF-8")
               (str/replace "+" "%20")
               (str/replace "%21" "!")
               (str/replace "%27" "'")
               (str/replace "%28" "(")
               (str/replace "%29" ")")
               (str/replace "%7E" "~"))
     :cljs (js/encodeURIComponent (str x))))

(defn query-string
  "`params` ({name value-or-values}) as a query string or form body, in
  name order. A sequential value repeats its name."
  [params]
  (->> (sort-by key params)
       (mapcat (fn [[k v]]
                 (for [x (if (sequential? v) v [v])]
                   (str (url-encode (name k)) "=" (url-encode x)))))
       (str/join "&")))

(defn- param-value [v]
  (cond
    (sequential? v) (mapv str v)
    (keyword? v)    (name v)
    :else           (str v)))

(defn- spec-name
  "A parameter's name as the spec has it: :gene-ids and :gene_ids are both
  \"gene_ids\"."
  [k]
  (str/replace (name k) "-" "_"))

;; --- Datasets ---

(defn datasets
  "A description of Datasets operation `op` (a key of the route table, as
  Martian named them) with `params`. Parameter names can use - or _.
  Sequential path parameters are joined with commas. For a POST route, the
  parameters that aren't in its path or query go in the JSON body."
  [op params]
  (let [{:keys [method path query as accept body]}
        (or (get routes op)
            (throw (ex-info (str "No Datasets operation " op)
                            {:ncbi/error :unknown-operation :op op})))
        params     (into {} (for [[k v] params :when (some? v)] [(spec-name k) v]))
        path-names (map second (re-seq #"\{([^}]+)\}" path))
        missing    (remove #(contains? params %) path-names)
        _          (when (seq missing)
                     (throw (ex-info (str "Datasets operation " op " needs " (str/join ", " missing))
                                     {:ncbi/error :missing-parameters :op op :missing (vec missing)})))
        url-path   (str/replace path #"\{([^}]+)\}"
                                (fn [[_ n]]
                                  (let [v (get params n)]
                                    (str/join "," (map url-encode (if (sequential? v) v [v]))))))
        others     (apply dissoc params path-names)
        in-query   (into {} (for [[k v] others :when (contains? query k)] [k (param-value v)]))
        rest       (apply dissoc others (keys in-query))]
    (when (and (seq rest) (not body))
      (throw (ex-info (str "Datasets operation " op " doesn't take " (str/join ", " (sort (keys rest))))
                      {:ncbi/error :unknown-parameters :op op :unknown (vec (sort (keys rest)))})))
    (cond-> {:api    :datasets
             :op     op
             :method method
             :url    (str (:datasets base-urls) url-path)
             :as     as
             :accept accept}
      (seq in-query)          (assoc :query in-query)
      (and body (seq rest))   (assoc :json rest))))

;; --- E-utilities ---

(defn eutils
  "A description of E-utility `util` (:esearch, :esummary, :elink, :einfo,
  :efetch) with `params`, POSTed as a form so long lists of ids fit.
  JSON is asked for unless `params` has its own retmode."
  [util params]
  (let [form (into {} (for [[k v] params :when (some? v)] [(name k) (param-value v)]))]
    {:api    :eutils
     :op     util
     :method :post
     :url    (str (:eutils base-urls) "/" (name util) ".fcgi")
     :form   (merge {"retmode" "json"} form)
     :as     (if (= "json" (get form "retmode" "json")) :json :text)}))

;; --- Where to send it ---

(defn url-for
  "The URL to send `request` to: its own, or under the client's base URL
  for its API (`base-urls`: {:datasets \"…\"}), with its query string."
  [overrides {:keys [api url query]}]
  (let [default (get base-urls api)
        base    (get overrides api)
        url     (if (and base default (str/starts-with? url default))
                  (str base (subs url (count default)))
                  url)]
    (cond-> url
      (seq query) (str "?" (query-string query)))))

;; --- Errors ---

(defn api-key
  "`client`'s API key now, or nil. The client's :api-key is a function, so
  the key isn't printed with the client."
  [client]
  (let [k (when-let [f (:api-key client)] (f))]
    (when-not (str/blank? k) k)))

(defn rate
  "How many requests a second `client` may make: 10 with an API key, 3
  without."
  [client]
  (if (api-key client) 10 3))

(defn outgoing
  "What to send for description `request`, with `client`'s API key, tool
  and email added: {:method :url :headers :body :json}, where :body is a
  form body (a string) and :json is data, for the runner to encode. The
  Datasets API takes the key in the api-key header; E-utilities takes it,
  the tool and the email (only if given) in the form."
  [client {:keys [api method form json accept] :as request}]
  (let [k    (api-key client)
        form (when form
               (cond-> form
                 (= api :eutils) (merge (cond-> {}
                                          (:tool client)  (assoc "tool" (:tool client))
                                          (:email client) (assoc "email" (:email client))
                                          k               (assoc "api_key" k)))))]
    {:method  method
     :url     (url-for (:base-urls client) request)
     :headers (cond-> {}
                accept                    (assoc "accept" accept)
                (and k (= api :datasets)) (assoc "api-key" k)
                form                      (assoc "content-type" "application/x-www-form-urlencoded")
                json                      (assoc "content-type" "application/json"))
     :body    (when form (query-string form))
     :json    json}))

(defn describe
  "A short description of `request`, for messages: \"datasets taxonomy-data-report\"."
  [{:keys [api op]}]
  (str (name api) " " (name op)))

(defn- error-message
  "NCBI's own message in an error body, if it has one."
  [body]
  (when (string? body)
    (or (some-> (re-find #"\"message\"\s*:\s*\"([^\"]*)\"" body) second)
        (some-> (re-find #"\"error\"\s*:\s*\"([^\"]*)\"" body) second))))

(defn http-error
  "An ex-info for an answer of HTTP `status`, saying what was asked and what
  NCBI said. `body` is the answer's text (or nil)."
  [request status headers body]
  (ex-info (str "NCBI said " status " to " (describe request)
                (when-let [m (error-message body)] (str ": " m)))
           {:ncbi/error   (cond (= 429 status) :rate-limited
                                (<= 500 status 599) :server-error
                                (= 404 status) :not-found
                                :else :http-error)
            :status       status
            :headers      headers
            :body         (when (string? body) (subs body 0 (min 2000 (count body))))
            :ncbi/request request}))
