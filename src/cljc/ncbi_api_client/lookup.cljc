(ns ncbi-api-client.lookup
  "Looking up accessions of any kind: which database each is in, 200 to a
  request, and each answer matched back to the accession asked about.
  `core/lookup` runs it; `rows/table` turns what it gives into rows.

  For sra, biosample, assembly and bioproject, the accessions are searched
  for (esearch), then summarized (esummary); nuccore takes accessions as
  IDs, so it's one esummary."
  (:require [clojure.string :as str]
            [ncbi-api-client.accessions :as acc]
            [ncbi-api-client.bridge :as bridge]
            [ncbi-api-client.eutils :as eu]
            [ncbi-api-client.plan :as plan]
            [ncbi-api-client.rows :as rows]))

(def batch-size
  "How many accessions go in one request."
  200)

(defn asked
  "The different accessions in `accessions`, trimmed, without blanks, in
  order."
  [accessions]
  (vec (distinct (remove str/blank? (map #(str/trim (str %)) accessions)))))

(defn- many?
  "Whether `accession` can answer to many records: an SRA study or sample
  (SRP..., SRS...) has many experiments. One of those in a batch could
  crowd out the others' records, so each goes in a batch of its own."
  [accession]
  (contains? #{:study-secondary :sample-secondary} (:id (acc/kind-of accession))))

(defn batches
  "`accessions` (see `asked`) in batches: [[db [accession ...]] ...]. The
  ones no database is known for (`accessions/database-of`) are left out,
  and each that can answer to many records (`many?`) is a batch of its
  own."
  [accessions]
  (vec (concat
        (for [[db accs] (dissoc (group-by acc/database-of (remove many? accessions)) nil)
              batch (partition-all batch-size accs)]
          [db (vec batch)])
        (for [a accessions :when (many? a)]
          [(acc/database-of a) [a]]))))

(defn- records-plan
  "A plan for the records in `db` for `accessions`."
  [db accessions]
  (if (= "nuccore" db)
    (eu/esummary db accessions)
    (plan/then (eu/esearch db (str/join " OR " accessions) {:retmax (* 5 (count accessions))})
               (fn [{:keys [ids]}]
                 (if (seq ids) (eu/esummary db ids) [])))))

(defn match-back
  "For each of `accessions` (from `db`), in order: [accession record], the
  record of `records` that answers to it (with or without its version),
  as an entity (`bridge/tag-result`) with the accession in its metadata
  as :ncbi/asked. Or, for one NCBI hasn't got, {:ncbi/asked
  :ncbi/error :not-found :ncbi.eutils/db}."
  [client db accessions records]
  (let [named (map (fn [r] [(rows/names-of db (rows/row db r)) r]) records)]
    (vec (for [a accessions]
           [a (if-let [r (some (fn [[names r]] (when (contains? names a) r)) named)]
                (vary-meta (bridge/tag-result client db r) assoc :ncbi/asked a)
                {:ncbi/asked a :ncbi/error :not-found :ncbi.eutils/db db})]))))

(defn batch-plan
  "A plan for one batch ([db accessions], see `batches`): what `match-back`
  gives."
  [client [db accessions]]
  (plan/then (records-plan db accessions)
             #(match-back client db accessions %)))

(defn in-order
  "The records from batches' `results` (each what `batch-plan` gives), in
  the order of `asked`."
  [asked results]
  (let [by (into {} (apply concat results))]
    (vec (keep by asked))))
