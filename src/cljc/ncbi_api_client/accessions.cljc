(ns ncbi-api-client.accessions
  "Recognising accessions, and which Entrez database each kind is in.

  The INSDC kinds are shared by NCBI, ENA and DDBJ; RefSeq sequences and
  assemblies are recognised too."
  (:require [clojure.string :as str]))

(def kinds
  "The INSDC kinds of accession: what each looks like, and the Entrez
  database it's in."
  [{:id :sequence         :re #"^(?:[A-Z]\d{5}|[A-Z]{2}\d{6}|[A-Z]{2}\d{8})(?:\.\d+)?$"
    :db "nuccore"         :label "nucleotide sequences"          :example "MN908947.3"}
   {:id :run              :re #"^[EDS]RR\d{6,}$"
    :db "sra"             :label "sequencing runs"               :example "SRR11140748"}
   {:id :experiment       :re #"^[EDS]RX\d{6,}$"
    :db "sra"             :label "sequencing experiments"        :example "SRX7777164"}
   {:id :sample           :re #"^SAM[EDN][A-Z]?\d+$"
    :db "biosample"       :label "samples (BioSamples)"          :example "SAMN14154201"}
   {:id :sample-secondary :re #"^[EDS]RS\d{6,}$"
    :db "sra"             :label "samples (SRA/ENA sample ids)"  :example "SRS6194281"}
   {:id :study            :re #"^PRJ[EDN][A-Z]\d+$"
    :db "bioproject"      :label "studies (BioProjects)"         :example "PRJNA606159"}
   {:id :study-secondary  :re #"^[EDS]RP\d{6,}$"
    :db "sra"             :label "studies (SRA/ENA study ids)"   :example "SRP251618"}
   {:id :assembly         :re #"^GCA_\d{9}(?:\.\d+)?$"
    :db "assembly"        :label "genome assemblies"             :example "GCA_009858895.3"}])

(defn kind-of
  "The kind of accession `s` is (one of `kinds`), or nil."
  [s]
  (let [s (str/trim (str s))]
    (some #(when (re-matches (:re %) s) %) kinds)))

(defn refseq-nucleotide?
  "Whether `accession` is a RefSeq nucleotide sequence's: NG_048935.1,
  NC_045512.2, NZ_CP012345.1..."
  [accession]
  (boolean (re-matches #"(?:N[CGTWZMR]|X[MR])_[A-Z]{0,4}\d{6,9}(?:\.\d+)?" (str/trim (str accession)))))

(defn refseq-protein?
  "Whether `accession` is a RefSeq protein's: WP_000239590.1, NP_..., YP_..."
  [accession]
  (boolean (re-matches #"(?:WP|NP|YP|XP|AP)_\d{6,9}(?:\.\d+)?" (str/trim (str accession)))))

(defn database-of
  "The Entrez database `accession` is in, or nil."
  [accession]
  (let [a (str/trim (str accession))]
    (or (:db (kind-of a))
        (when (re-matches #"GC[AF]_\d{9}(?:\.\d+)?" a) "assembly")
        ;; RefSeq's sequences (NG_ for AMR genes' reference sequences)
        (when (refseq-nucleotide? a) "nuccore"))))

(defn without-version
  "MN908947.3 -> MN908947."
  [accession]
  (str/replace (str/trim (str accession)) #"\.\d+$" ""))
