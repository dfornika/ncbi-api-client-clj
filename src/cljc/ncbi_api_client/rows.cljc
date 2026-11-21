(ns ncbi-api-client.rows
  "E-utilities records as flat rows, for tables: one map of column ->
  value per record, so they join onto other tables.

  Records are esummary documents with keyword keys, as `eutils/esummary`
  and `core/lookup` give them, on both platforms. The XML that SRA and
  BioSample summaries embed (expxml, sampledata) is read with small regex
  extractors, not an XML parser."
  (:require [clojure.string :as str]
            [ncbi-api-client.accessions :as acc]))

;; --- Reading embedded XML ---

(defn- unescape [s]
  (-> (str s)
      (str/replace "&lt;" "<") (str/replace "&gt;" ">") (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'") (str/replace "&amp;" "&")))

(defn- attr
  "The value of `attribute` on the first `tag` element in `xml`."
  [xml tag attribute]
  (some-> (re-find (re-pattern (str "<" tag "\\b[^>]*\\b" attribute "=\"([^\"]*)\"")) (str xml))
          second unescape))

(defn- element
  "The text of the first `tag` element in `xml`."
  [xml tag]
  (some-> (re-find (re-pattern (str "<" tag "\\b[^>]*>([^<]*)</" tag ">")) (str xml))
          second unescape))

(defn- field-name
  "\"Collection Date\" -> :collection_date"
  [s]
  (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9]+" "_") (str/replace #"^_|_$" "") keyword))

(defn- ->int
  "The integer at the start of `s` (as JavaScript's parseInt reads it), or nil."
  [s]
  (some-> (re-find #"^\s*-?\d+" (str s)) str/trim parse-long))

;; --- Rows ---

(defn nuccore-row
  "A sequence's summary: what it is, and its source's details (country,
  host, collection date... - nuccore's subtype|subname pairs) as columns."
  [d]
  (merge {:accession    (:accessionversion d)
          :title        (:title d)
          :organism     (:organism d)
          :taxid        (:taxid d)
          :length       (:slen d)
          :moltype      (:moltype d)
          :completeness (not-empty (:completeness d))
          :created      (:createdate d)
          :updated      (:updatedate d)}
         (let [ks (str/split (str (:subtype d)) #"\|")
               vs (str/split (str (:subname d)) #"\|")]
           (into {} (for [[k v] (map vector ks vs) :when (not (str/blank? k))]
                      [(field-name k) v])))))

(defn biosample-row
  "A BioSample's summary, with its attributes (geo_loc_name,
  collection_date, host...) as columns."
  [d]
  (merge {:accession (:accession d)
          :title     (:title d)
          :organism  (:organism d)
          :taxid     (:taxonomy d)
          :package   (:package d)
          :published (:publicationdate d)}
         (into {} (for [[_ k v] (re-seq #"<Attribute\b[^>]*\battribute_name=\"([^\"]+)\"[^>]*>([^<]*)</Attribute>"
                                        (str (:sampledata d)))]
                    [(field-name k) (unescape v)]))))

(defn sra-row
  "An SRA experiment's summary: the experiment, its runs (joined with
  commas), study, sample, platform and library."
  [d]
  (let [x (:expxml d)]
    {:experiment       (attr x "Experiment" "acc")
     :runs             (str/join "," (map second (re-seq #"<Run\b[^>]*\bacc=\"([^\"]+)\"" (str (:runs d)))))
     :title            (element x "Title")
     :study            (attr x "Study" "acc")
     :sample           (attr x "Sample" "acc")
     :biosample        (element x "Biosample")
     :organism         (attr x "Organism" "ScientificName")
     :platform         (element x "Platform")
     :instrument_model (attr x "Platform" "instrument_model")
     :library_strategy (element x "LIBRARY_STRATEGY")
     :library_source   (element x "LIBRARY_SOURCE")
     :library_layout   (some-> (re-find #"<LIBRARY_LAYOUT>\s*<(\w+)" (str x)) second)
     :total_spots      (->int (attr x "Statistics" "total_spots"))
     :total_bases      (->int (attr x "Statistics" "total_bases"))
     :created          (:createdate d)}))

(defn assembly-row
  "An assembly's summary: its accessions (GenBank and RefSeq), names,
  organism, BioSample, BioProject and statistics."
  [d]
  {:accession    (:assemblyaccession d)
   :genbank      (get-in d [:synonym :genbank])
   :refseq       (not-empty (get-in d [:synonym :refseq]))
   :name         (:assemblyname d)
   :organism     (:organism d)
   :species      (:speciesname d)
   :taxid        (->int (:taxid d))
   :status       (:assemblystatus d)
   :biosample    (:biosampleaccn d)
   :bioproject   (some-> (:gb_bioprojects d) first :bioprojectaccn)
   :contig_n50   (:contign50 d)
   :scaffold_n50 (:scaffoldn50 d)
   :coverage     (not-empty (:coverage d))
   :submitter    (:submitterorganization d)
   :submitted    (:submissiondate d)})

(defn bioproject-row
  "A BioProject's summary."
  [d]
  {:accession   (:project_acc d)
   :title       (:project_title d)
   :organism    (not-empty (:organism_name d))
   :data_type   (:project_data_type d)
   :status      (:sequencing_status d)
   :registered  (:registration_date d)
   :description (:project_description d)})

(def readers
  "The row function for each database's records."
  {"nuccore"    nuccore-row
   "biosample"  biosample-row
   "sra"        sra-row
   "assembly"   assembly-row
   "bioproject" bioproject-row})

(defn row
  "Record `d` from database `db` as a row: one of `readers`, or the record
  as it is for other databases."
  [db d]
  ((get readers db identity) d))

(defn names-of
  "The accessions a row from `db` answers to (for matching it to the ones
  asked about), with and without their versions."
  [db r]
  (let [names (case db
                "sra"      (concat [(:experiment r) (:study r) (:sample r)] (str/split (str (:runs r)) #","))
                "assembly" [(:accession r) (:genbank r) (:refseq r)]
                [(:accession r)])]
    (set (mapcat (fn [n] (when-not (str/blank? n) [n (acc/without-version n)])) names))))

(defn table
  "`records` (as `core/lookup` gives them) as rows: each with the accession
  it was asked about under `:as` (default :accession_asked; a column's
  name, to join on) and its database under :database. A record NCBI
  hasn't got is a row with :error."
  ([records] (table records {}))
  ([records {:keys [as] :or {as :accession_asked}}]
   (vec (for [r records
              :let [{:ncbi/keys [asked error]} (merge (meta r) (select-keys r [:ncbi/asked :ncbi/error]))
                    db (or (:ncbi.eutils/db r) (:ncbi.eutils/db (meta r)))]]
          (if error
            {as asked :database db :error "NCBI hasn't got it"}
            (assoc (row db r) as asked :database db))))))
