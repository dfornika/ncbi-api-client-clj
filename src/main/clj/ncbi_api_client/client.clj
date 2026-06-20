(ns ncbi-api-client.client
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [martian.core :as martian]
            [martian.hato :as martian-http]
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

(defn create-client
  ([] (create-client {}))
  ([{:keys [api-key base-url]}]
   (let [api-key    (or api-key (System/getenv "NCBI_API_KEY") "")
         definition (yaml/cleanup (yaml/yaml->edn (slurp (io/resource "openapi3.docs.yaml"))))
         base-url   (or base-url (openapi/base-url nil nil definition))
         add-token  {:name  ::add-api-token
                     :enter (fn [ctx]
                              (assoc-in ctx [:request :headers "api-token"] api-key))}
         opts       (-> martian-http/default-opts
                        (update :interceptors insert-after
                                :martian.interceptors/url fix-array-path-params)
                        (update :interceptors conj add-token))]
     (martian/bootstrap-openapi base-url definition opts))))
