(ns ncbi-api-client.client
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [martian.core :as martian]
            [martian.encoders :as encoders]
            [martian.hato :as martian-http]
            [martian.interceptors :as interceptors]
            [martian.yaml :as yaml]
            [martian.openapi :as openapi]))

(defn- fix-url-array-params [url]
  (str/replace url #"\[[^\]]+\]"
               (fn [match]
                 (let [inner (subs match 1 (dec (count match)))]
                   (if (str/includes? inner "\"")
                     (->> (re-seq #"\"([^\"]*)\"" match)
                          (map second)
                          (str/join ","))
                     (str/replace inner " " ","))))))

(def ^:private fix-array-path-params
  {:name  ::fix-array-path-params
   :enter (fn [ctx]
            (update-in ctx [:request :url] fix-url-array-params))})

(defn- insert-after [interceptors target-name new-interceptor]
  (reduce (fn [acc i]
            (if (= (:name i) target-name)
              (conj acc i new-interceptor)
              (conj acc i)))
          []
          interceptors))

(def ^:private extra-encoders
  {"text/plain"       {:encode str :decode identity :as :text}
   "application/zip"  {:encode identity :decode identity :as :byte-array}
   "image/jpeg"       {:encode identity :decode identity :as :byte-array}
   "image/png"        {:encode identity :decode identity :as :byte-array}
   "image/gif"        {:encode identity :decode identity :as :byte-array}
   "image/tiff"       {:encode identity :decode identity :as :byte-array}
   "image/svg+xml"    {:encode identity :decode identity :as :text}})

(defn- replace-interceptor [interceptors target-name replacement]
  (mapv #(if (= (:name %) target-name) replacement %) interceptors))

(defn- all-encoders []
  (merge (encoders/default-encoders) extra-encoders))

(defn create-client
  ([] (create-client {}))
  ([{:keys [api-key base-url]}]
   (let [api-key    (or api-key (System/getenv "NCBI_API_KEY") "")
         definition (yaml/cleanup (yaml/yaml->edn (slurp (io/resource "openapi3.docs.yaml"))))
         base-url   (or base-url (openapi/base-url nil nil definition))
         add-token  {:name  ::add-api-token
                     :enter (fn [ctx]
                              (assoc-in ctx [:request :headers "api-token"] api-key))}
         encoders   (all-encoders)
         opts       (-> martian-http/default-opts
                        (update :interceptors replace-interceptor
                                ::interceptors/encode-body (interceptors/encode-body encoders))
                        (update :interceptors replace-interceptor
                                ::interceptors/coerce-response (interceptors/coerce-response encoders))
                        (update :interceptors insert-after
                                :martian.interceptors/url fix-array-path-params)
                        (update :interceptors conj add-token))]
     (martian/bootstrap-openapi base-url definition opts))))
