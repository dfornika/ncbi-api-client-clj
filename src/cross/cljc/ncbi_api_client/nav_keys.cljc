(ns ncbi-api-client.nav-keys
  #?(:clj (:require [clojure.core.protocols :as p])))

(defn datafy-meta-key []
  #?(:clj  `p/datafy
     :cljs `cljs.core/-datafy))

(defn nav-meta-key []
  #?(:clj  `p/nav
     :cljs `cljs.core/-nav))

(defn with-datafy-nav [data datafy-fn nav-fn extra-meta]
  (with-meta data
    (merge {(datafy-meta-key) datafy-fn
            (nav-meta-key)    nav-fn}
           extra-meta)))
