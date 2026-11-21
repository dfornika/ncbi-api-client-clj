(ns ncbi-api-client.fixtures
  "Recorded answers (test-resources/fixtures/), for tests on both
  platforms: `inline` reads one when the test is compiled, so the browser
  build needs no file access."
  #?(:cljs (:require-macros [ncbi-api-client.fixtures]))
  (:require #?(:clj [cheshire.core :as json])
            #?(:clj [clojure.java.io :as io])))

#?(:clj
   (defmacro inline
     "The text of test-resources/fixtures/`path`, as a string literal."
     [path]
     (slurp (io/resource (str "fixtures/" path)))))

(defn parse-json
  "JSON text as data with keyword keys, as the runners decode it."
  [s]
  #?(:clj  (json/parse-string s true)
     :cljs (js->clj (js/JSON.parse s) :keywordize-keys true)))
