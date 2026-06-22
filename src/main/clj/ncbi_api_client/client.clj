(ns ncbi-api-client.client
  (:require [clojure.string :as str]
            [hato.client :as hc]
            [martian.encoders :as encoders]
            [martian.hato :as martian-http]
            [martian.interceptors :as interceptors]))

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

(def ^:private extra-encoders
  {"text/plain"       {:encode str :decode identity :as :text}
   "application/zip"  {:encode identity :decode identity :as :byte-array}
   "image/jpeg"       {:encode identity :decode identity :as :byte-array}
   "image/png"        {:encode identity :decode identity :as :byte-array}
   "image/gif"        {:encode identity :decode identity :as :byte-array}
   "image/tiff"       {:encode identity :decode identity :as :byte-array}
   "image/svg+xml"    {:encode identity :decode identity :as :text}})

(def ^:private binary-content-types
  #{"application/zip" "application/octet-stream"})

;; Download endpoints declare both text/plain (error) and application/zip (success)
;; in :produces. Martian's choose-media-type picks whichever encoder key iterates
;; first, which may be text/plain — causing hato to read binary data as a string.
(def ^:private fix-binary-response
  {:name  ::fix-binary-response
   :enter (fn [{:keys [handler] :as ctx}]
            (if-let [binary-type (some binary-content-types (:produces handler))]
              (-> ctx
                  (assoc-in [:request :as] :byte-array)
                  (assoc-in [:request :headers "Accept"] binary-type))
              ctx))})

(defn- all-encoders []
  (merge (encoders/default-encoders) extra-encoders))

(defn create-client
  ([] (create-client {}))
  ([{:keys [api-key base-url tool email]}]
   (let [api-key   (or api-key (System/getenv "NCBI_API_KEY") "")
         add-token {:name  ::add-api-token
                    :enter (fn [ctx]
                             (assoc-in ctx [:request :headers "api-token"] api-key))}
         encoders  (all-encoders)
         opts      {:interceptors
                    (-> martian-http/hato-interceptors
                        (interceptors/inject (interceptors/encode-request encoders)
                                             :replace ::interceptors/encode-request)
                        (interceptors/inject (interceptors/coerce-response
                                              encoders
                                              (martian-http/get-response-coerce-opts false))
                                             :replace ::interceptors/coerce-response)
                        (interceptors/inject fix-binary-response
                                             :after ::interceptors/coerce-response)
                        (interceptors/inject fix-array-path-params
                                             :after ::interceptors/url)
                        (conj add-token)
                        (conj martian-http/perform-request))}
         datasets  (martian-http/bootstrap-openapi
                    (or base-url "openapi3.docs.yaml")
                    opts)
         eutils    {:http-client (hc/build-http-client {})
                    :api-key     (when (seq api-key) api-key)
                    :tool        (or tool "ncbi-api-client-clj")
                    :email       email}]
     (assoc datasets :eutils eutils))))
