(ns ncbi-api-client.links-test
  "Links as navigation, batched links, and paging search results through
  NCBI's history server, with a client whose :run answers each request
  with a function of it, so they run on both platforms."
  (:require [clojure.datafy :refer [datafy nav]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ncbi-api-client.bridge :as bridge]
            [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.nav :as navi]
            [ncbi-api-client.plan :as plan]))

(defn- client
  "A client that answers each request with `(answer request)`, adding the
  request to `asked`."
  [answer asked]
  {:nav-edges (merge ds/nav-edges bridge/nav-edges)
   :run       (fn [_ x]
                (loop [x x]
                  (if (plan/plan? x)
                    (let [r (:request x)]
                      (swap! asked conj r)
                      (recur ((:then x) (answer r))))
                    x)))})

(defn- value
  "What nav gave: the value on the JVM; in the browser, a deferred value,
  fetched here (the fake :run answers at once)."
  [x]
  #?(:clj  x
     :cljs ((:async.nav/fetch (meta x)))))

(defn- form [r k] (get-in r [:form k]))

(defn- summaries
  "An esummary answer for the ids asked about: {:uid id :title \"title id\"}."
  [r]
  (let [ids (str/split (form r "id") #",")]
    {:result (into {:uids ids} (map (fn [id] [(keyword id) {:uid id :title (str "title " id)}])) ids)}))

(defn- uids [n] (mapv #(str (+ 1000 %)) (range n)))

;; --- Search, paged through the history server ---

(defn- search-answers
  "esearch finds `total` results and keeps them on the history server;
  esummary by history answers from `uids`, or says the history is gone
  when `gone?` is true."
  [total gone?]
  (fn [r]
    (case [(:op r) (some? (form r "WebEnv"))]
      [:esearch false]  (let [start (parse-long (or (form r "retstart") "0"))]
                          {:esearchresult {:count (str total) :retmax "2" :retstart (str start)
                                           :webenv "MCID_1" :querykey "1"
                                           :idlist (subvec (uids total) start (min total (+ start 2)))}})
      [:esummary false] (summaries r)
      [:esummary true]  (if @gone?
                          {:esummaryresult ["Unable to obtain query #1"]}
                          (let [start (parse-long (form r "retstart"))
                                ids   (subvec (uids total) start (min total (+ start 2)))]
                            (summaries (assoc-in r [:form "id"] (str/join "," ids))))))))

(deftest search-history-test
  (let [asked (atom [])
        gone? (atom false)
        c     (client (search-answers 5 gone?) asked)
        page1 ((:run c) c (bridge/search-plan c "sra" "x" {:retmax 2}))]
    (testing "the search is kept on the history server"
      (is (= "y" (form (first @asked) "usehistory")))
      (is (= ["1000" "1001"] (map :uid page1)))
      (is (= 5 (:async.nav/total (meta page1)))))

    (testing "the next page is one esummary from the history, not another search"
      (reset! asked [])
      (let [page2 (value (nav (datafy page1) :ncbi.nav/next-page :deferred))]
        (is (= ["1002" "1003"] (map :uid page2)))
        (is (= [[:esummary "MCID_1" "1" "2" "2"]]
               (map (juxt :op #(form % "WebEnv") #(form % "query_key") #(form % "retstart") #(form % "retmax"))
                    @asked)))
        (testing "and so on, to the last page"
          (let [page3 (value (nav (datafy page2) :ncbi.nav/next-page :deferred))]
            (is (= ["1004"] (map :uid page3)))
            (is (nil? (:async.nav/next (meta page3))))))))

    (testing "if the history has expired, the search is made again from there"
      (reset! asked [])
      (reset! gone? true)
      (let [page2 (value (nav (datafy page1) :ncbi.nav/next-page :deferred))]
        (is (= ["1002" "1003"] (map :uid page2)))
        (is (= [:esummary :esearch :esummary] (map :op @asked)))
        (is (= "2" (form (second @asked) "retstart")))))))

;; --- Following a link, a page at a time ---

(deftest link-pages-test
  (let [asked  (atom [])
        c      (client (fn [r]
                         (case (:op r)
                           :esearch  {:esearchresult {:count "1" :idlist ["1"]}}
                           :esummary (if (= "biosample" (form r "db"))
                                       {:result {:uids ["1"] :1 {:uid "1"}}}
                                       (summaries r))
                           :elink    {:linksets [{:linksetdbs [{:dbto "sra" :linkname "biosample_sra"
                                                                :links (uids 450)}]}]}))
                       asked)
        sample (first ((:run c) c (bridge/search-plan c "biosample" "x")))
        page1  (value (nav (datafy sample) :ncbi.elink/biosample_sra :deferred))]
    (testing "a link with many records gives a page of them"
      (is (= 200 (count page1)))
      (is (= 450 (:async.nav/total (meta page1))))
      (is (= 450 (:ncbi.elink/total-count (meta page1))))
      (is (= "sra" (:ncbi.eutils/db (meta (first page1))))))
    (testing "and leads to the rest, a page at a time"
      (let [page2 (value (nav (datafy page1) :ncbi.nav/next-page :deferred))
            page3 (value (nav (datafy page2) :ncbi.nav/next-page :deferred))]
        (is (= "1200" (:uid (first page2))))
        (is (= 50 (count page3)))
        (is (nil? (:async.nav/next (meta page3))))
        (is (= "biosample_sra" (:ncbi.elink/linkname (meta page3))))))))

;; --- Links of many records at once ---

(deftest links-test
  (let [asked   (atom [])
        c       (client (fn [r]
                          (case (:op r)
                            :elink    {:linksets [{:dbfrom "biosample" :ids ["1"]
                                                   :linksetdbs [{:dbto "sra" :linkname "biosample_sra"
                                                                 :links ["10" "11"]}]}
                                                  {:dbfrom "biosample" :ids ["2"]}
                                                  {:dbfrom "biosample" :ids ["3"]
                                                   :linksetdbs [{:dbto "sra" :linkname "biosample_sra"
                                                                 :links ["11"]}]}]}
                            :esummary (summaries r)))
                        asked)
        records [(with-meta {:uid "1"} {:ncbi.eutils/db "biosample"})
                 {:ncbi/asked "SAMN0" :ncbi/error :not-found :ncbi.eutils/db "biosample"}
                 (with-meta {:uid "2"} {:ncbi.eutils/db "biosample"})
                 (with-meta {:uid "3"} {:ncbi.eutils/db "biosample"})]
        linked  (ncbi/links c records "biosample_sra")]
    (testing "each record's links, in the order of the records"
      (is (= [["10" "11"] [] [] ["11"]] (map #(map :uid %) linked)))
      (is (= [2 0 0 1] (map #(:ncbi.elink/total-count (meta %)) linked)))
      (is (= "sra" (:ncbi.eutils/db (meta (ffirst linked))))))
    (testing "in two requests: one elink for every record, each id on its own; one esummary"
      (is (= [:elink :esummary] (map :op @asked)))
      (is (= ["1" "2" "3"] (form (first @asked) "id")))
      (is (= "10,11" (form (second @asked) "id"))))))

;; --- From a Datasets BioSample into SRA ---

(deftest biosample-sra-edge-test
  (let [asked  (atom [])
        c      (client (fn [r]
                         (case (:op r)
                           :esearch  {:esearchresult {:count "1" :idlist ["14154201"]}}
                           :elink    {:linksets [{:linksetdbs [{:dbto "sra" :linkname "biosample_sra"
                                                                :links ["10155281"]}]}]}
                           :esummary (summaries r)))
                       asked)
        sample (navi/tag-entity c :ncbi/biosample {:accession "SAMN14154201"})
        sra    (value (nav (datafy sample) :ncbi.nav/sra :deferred))]
    (is (= :deferred (:ncbi.nav/sra (datafy sample))))
    (is (= ["10155281"] (map :uid sra)))
    (is (= [[:esearch "SAMN14154201"] [:elink "biosample_sra"] [:esummary "10155281"]]
           (map (juxt :op #(or (form % "term") (form % "linkname") (form % "id"))) @asked)))))
