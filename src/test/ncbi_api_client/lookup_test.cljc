(ns ncbi-api-client.lookup-test
  "Lookups by accession, and rows, against real answers from NCBI
  (test-resources/fixtures/eutils/), on both platforms."
  (:require [clojure.datafy :refer [datafy]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [ncbi-api-client.accessions :as acc]
            [ncbi-api-client.core :as ncbi]
            [ncbi-api-client.fixtures :as fx :refer [inline]]
            [ncbi-api-client.lookup :as lk]
            [ncbi-api-client.plan :as plan]
            [ncbi-api-client.rows :as rows]))

(def ^:private answers
  "Real answers from NCBI, by operation and database."
  {[:esummary "nuccore"]    (inline "eutils/nuccore-esummary.json")
   [:esearch "biosample"]   (inline "eutils/biosample-esearch.json")
   [:esummary "biosample"]  (inline "eutils/biosample-esummary.json")
   [:esearch "sra"]         (inline "eutils/sra-esearch.json")
   [:esummary "sra"]        (inline "eutils/sra-esummary.json")
   [:esearch "assembly"]    (inline "eutils/assembly-esearch.json")
   [:esummary "assembly"]   (inline "eutils/assembly-esummary.json")
   [:esearch "bioproject"]  (inline "eutils/bioproject-esearch.json")
   [:esummary "bioproject"] (inline "eutils/bioproject-esummary.json")})

(defn- client
  "A client that answers as NCBI did, adding each request to `asked`."
  [asked]
  {:run (fn [_ x]
          (loop [x x]
            (if (plan/plan? x)
              (let [r (:request x)]
                (swap! asked conj r)
                (recur ((:then x) (some-> (get answers [(:op r) (get-in r [:form "db"])]) fx/parse-json))))
              x)))})

(defn- settle
  "Call `check` with `x`'s value: `x` on the JVM, a promise of it in the
  browser (where the test waits for it)."
  [x check]
  #?(:clj  (check x)
     :cljs (async done
                  (-> (js/Promise.resolve x)
                      (.then check)
                      (.catch (fn [e] (is (nil? e) (str "unexpected error: " (ex-message e)))))
                      (.finally done)))))

(deftest which-database-test
  (is (= "nuccore" (acc/database-of "MN908947.3")))
  (is (= "nuccore" (acc/database-of "NC_045512.2")))
  (is (= "sra" (acc/database-of "SRR11140748")))
  (is (= "biosample" (acc/database-of "SAMN14154201")))
  (is (= "bioproject" (acc/database-of "PRJNA607948")))
  (is (= "assembly" (acc/database-of "GCF_000196035.1")))
  (is (= "assembly" (acc/database-of " GCA_000196035.1 ")))
  (is (nil? (acc/database-of "not an accession")))
  (is (acc/refseq-protein? "WP_000239590.1"))
  (is (not (acc/refseq-protein? "MN908947.3"))))

(deftest batches-test
  (testing "grouped by database, 200 to a batch, unknown ones left out"
    (let [seqs (map #(str "MN" (+ 100000 %) ".1") (range 250))
          bs   (lk/batches (lk/asked (concat seqs ["SAMN14154201" "nope" " " "SAMN14154201"])))]
      (is (= [["nuccore" 200] ["nuccore" 50] ["biosample" 1]]
             (map (fn [[db accs]] [db (count accs)]) bs)))))
  (testing "an SRA study or sample, which has many experiments, is a batch of its own"
    (is (= [["sra" ["SRR11140748" "SRX7777164"]] ["sra" ["SRP251618"]] ["sra" ["SRS6194281"]]]
           (lk/batches ["SRR11140748" "SRP251618" "SRX7777164" "SRS6194281"])))))

(def ^:private asked-for
  ["MN908947.3" "OQ282284.1" "ZZ999999.1" "SAMN14154201" "SRR11140748"
   "GCA_000196035.1" "PRJNA607948" "MN908947.3" "" "not an accession"])

(deftest lookup-test
  (let [asked    (atom [])
        progress (atom [])]
    (settle
     (ncbi/lookup (client asked) asked-for {:on-progress #(swap! progress conj %)})
     (fn [records]
       (let [by (into {} (map (juxt #(or (:ncbi/asked (meta %)) (:ncbi/asked %)) identity)) records)]
         (testing "one for each, once, in the order asked (what isn't an accession left out)"
           (is (= ["MN908947.3" "OQ282284.1" "ZZ999999.1" "SAMN14154201" "SRR11140748"
                   "GCA_000196035.1" "PRJNA607948"]
                  (map #(or (:ncbi/asked (meta %)) (:ncbi/asked %)) records))))
         (testing "one NCBI hasn't got is marked"
           (is (= {:ncbi/asked "ZZ999999.1" :ncbi/error :not-found :ncbi.eutils/db "nuccore"}
                  (by "ZZ999999.1"))))
         (testing "the others are records, navigable like search results"
           (is (= "MN908947.3" (:accessionversion (by "MN908947.3"))))
           (is (= "nuccore" (:ncbi.eutils/db (meta (by "MN908947.3")))))
           (is (= :deferred (:ncbi.elink/biosample_sra (datafy (by "SAMN14154201"))))))
         (testing "the requests: nuccore takes accessions; the others are searched for first"
           (is (= #{[:esummary "nuccore"] [:esearch "biosample"] [:esummary "biosample"]
                    [:esearch "sra"] [:esummary "sra"] [:esearch "assembly"] [:esummary "assembly"]
                    [:esearch "bioproject"] [:esummary "bioproject"]}
                  (set (map (juxt :op #(get-in % [:form "db"])) @asked))))
           (is (= "MN908947.3,OQ282284.1,ZZ999999.1"
                  (some #(when (= [:esummary "nuccore"] [(:op %) (get-in % [:form "db"])])
                           (get-in % [:form "id"]))
                        @asked))))
         (testing "progress is how many have been asked about"
           (is (= 7 (last @progress))))

         (testing "as rows"
           (let [rs  (rows/table records)
                 row (into {} (map (juxt :accession_asked identity)) rs)
                 s1  (row "MN908947.3")]
             (testing "a sequence: its source's details as columns"
               (is (= 29903 (:length s1)))
               (is (= "China" (:country s1)))
               (is (= "Homo sapiens" (:host s1)))
               (is (= "Dec-2019" (:collection_date s1)))
               (is (= "nuccore" (:database s1))))
             (is (= "NCBI hasn't got it" (:error (row "ZZ999999.1"))))
             (testing "a BioSample: its attributes as columns"
               (is (= "SAMN14154201" (:accession (row "SAMN14154201"))))
               (is (some? (:collection_date (row "SAMN14154201")))))
             (testing "an SRA run: its experiment's details"
               (is (str/includes? (:runs (row "SRR11140748")) "SRR11140748"))
               (is (= "ILLUMINA" (:platform (row "SRR11140748"))))
               (is (pos? (:total_bases (row "SRR11140748")))))
             (testing "an assembly, asked for by its GenBank accession, found as its RefSeq twin"
               (is (= "GCF_000196035.1" (:accession (row "GCA_000196035.1"))))
               (is (= "Listeria monocytogenes" (:species (row "GCA_000196035.1"))))
               (is (int? (:taxid (row "GCA_000196035.1")))))
             (is (= "PRJNA607948" (:accession (row "PRJNA607948"))))
             (testing "the column to join on can be named"
               (is (= "MN908947.3" (:sample_id (first (rows/table records {:as :sample_id})))))))))))))

(deftest versions-test
  (testing "an accession asked about without its version still matches"
    (settle
     (ncbi/lookup (client (atom [])) ["MN908947"])
     (fn [[r]]
       (is (= "MN908947" (:ncbi/asked (meta r))))
       (is (= "MN908947.3" (:accessionversion r)))))))
