(ns ncbi-api-client.package
  (:require [cheshire.core :as json]
            [clojure.core.protocols :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ncbi-api-client.plan :as plan]
            [ncbi-api-client.request :as req]
            [ncbi-api-client.runner :as runner])
  (:import [java.io File]
           [java.nio.file Files]
           [java.util.zip ZipFile]))

;; --- Zip extraction ---

(defn- read-zip-entries
  [^bytes zip-bytes]
  (let [tmp (Files/createTempFile "ncbi-pkg" ".zip" (into-array java.nio.file.attribute.FileAttribute []))]
    (try
      (Files/write tmp zip-bytes (into-array java.nio.file.OpenOption []))
      (with-open [zf (ZipFile. (.toFile tmp))]
        (into {}
              (map (fn [entry]
                     [(.getName entry)
                      (with-open [is (.getInputStream zf entry)]
                        (.readAllBytes is))]))
              (enumeration-seq (.entries zf))))
      (finally
        (Files/deleteIfExists tmp)))))

;; --- FASTA parsing ---

(defn- parse-fasta-header [header-line]
  (let [line (subs header-line 1)
        idx  (str/index-of line " ")]
    (if idx
      {:acc         (subs line 0 idx)
       :description (subs line (inc idx))}
      {:acc         line
       :description ""})))

(defn parse-fasta
  [^String text alphabet]
  (let [lines (str/split-lines text)]
    (loop [[line & rest] lines
           current       nil
           seqs          []]
      (cond
        (nil? line)
        (if current
          (conj seqs (-> (dissoc current :seq-buf)
                         (assoc :sequence (str (:seq-buf current))
                                :alphabet alphabet)))
          seqs)

        (str/starts-with? line ">")
        (let [header  (parse-fasta-header line)
              updated (if current
                        (conj seqs (-> (dissoc current :seq-buf)
                                       (assoc :sequence (str (:seq-buf current))
                                              :alphabet alphabet)))
                        seqs)]
          (recur rest
                 (assoc header :seq-buf (StringBuilder.))
                 updated))

        :else
        (do (when current
              (.append ^StringBuilder (:seq-buf current) (str/trim line)))
            (recur rest current seqs))))))

;; --- File type mappings ---

(def ^:private file-type->nav-key
  {"GENOMIC_NUCLEOTIDE_FASTA" :ncbi.nav/gene-fasta
   "PROTEIN_FASTA"            :ncbi.nav/protein-fasta
   "RNA_NUCLEOTIDE_FASTA"     :ncbi.nav/rna-fasta
   "GFF3"                     :ncbi.nav/gff
   "GTF"                      :ncbi.nav/gtf
   "GBFF"                     :ncbi.nav/gbff
   "GENOME_FASTA"             :ncbi.nav/genome-fasta
   "CDS_FASTA"                :ncbi.nav/cds-fasta
   "SEQUENCE_REPORT"          :ncbi.nav/sequence-report
   "DATA_REPORT"              :ncbi.nav/data-report})

(def ^:private file-type->alphabet
  {"GENOMIC_NUCLEOTIDE_FASTA" :iupac-nucleic-acids
   "RNA_NUCLEOTIDE_FASTA"     :iupac-nucleic-acids
   "GENOME_FASTA"             :iupac-nucleic-acids
   "CDS_FASTA"                :iupac-nucleic-acids
   "PROTEIN_FASTA"            :iupac-amino-acids})

;; --- Catalog parsing ---

(defn- collect-catalog-files
  [catalog]
  (let [entity-keys (disj (set (keys catalog)) "apiVersion")]
    (mapcat (fn [k]
              (let [v (get catalog k)]
                (if (sequential? v)
                  (mapcat #(get % "files") v)
                  (get v "files"))))
            entity-keys)))

(defn- nav-keys-from-catalog
  [catalog]
  (->> (collect-catalog-files catalog)
       (keep (fn [file-entry]
               (let [file-type (get file-entry "fileType")]
                 (when-let [nav-key (file-type->nav-key file-type)]
                   [nav-key file-entry]))))
       (into {})))

;; --- File content extraction ---

(defn- extract-file-content
  [entries file-entry]
  (let [file-path (get file-entry "filePath")
        raw-bytes (get entries (str "ncbi_dataset/data/" file-path))]
    (when raw-bytes
      (let [text      (String. ^bytes raw-bytes "UTF-8")
            file-type (get file-entry "fileType")]
        (if-let [alphabet (file-type->alphabet file-type)]
          (parse-fasta text alphabet)
          text)))))

;; --- Package entity ---

(defn- datafy-package [entries catalog nav-map]
  (-> {:catalog catalog}
      (merge (zipmap (keys nav-map) (repeat :deferred)))
      (with-meta
        {`p/nav    (fn [_this k _v]
                     (when-let [file-entry (get nav-map k)]
                       (extract-file-content entries file-entry)))
         `p/datafy (fn [this] this)
         :ncbi/type :ncbi/package})))

(defn make-package
  [^bytes zip-bytes]
  (let [entries     (read-zip-entries zip-bytes)
        catalog-raw (get entries "ncbi_dataset/data/dataset_catalog.json")
        catalog     (when catalog-raw
                      (json/parse-string (String. ^bytes catalog-raw "UTF-8")))
        nav-map     (when catalog (nav-keys-from-catalog catalog))]
    (datafy-package entries catalog (or nav-map {}))))

;; --- Download helpers ---

(def ^:private annotation-type-values
  {:fasta-gene        "FASTA_GENE"
   :fasta-protein     "FASTA_PROTEIN"
   :fasta-rna         "FASTA_RNA"
   :fasta-cds         "FASTA_CDS"
   :fasta-5p-utr      "FASTA_5P_UTR"
   :fasta-3p-utr      "FASTA_3P_UTR"
   :fasta-gene-flank  "FASTA_GENE_FLANK"
   :genome-fasta      "GENOME_FASTA"
   :genome-gff        "GENOME_GFF"
   :genome-gtf        "GENOME_GTF"
   :genome-gbff       "GENOME_GBFF"
   :genome-gb         "GENOME_GB"
   :rna-fasta         "RNA_FASTA"
   :cds-fasta         "CDS_FASTA"
   :prot-fasta        "PROT_FASTA"
   :sequence-report   "SEQUENCE_REPORT"})

(defn- resolve-annotation-types [annotations]
  (when (seq annotations)
    (mapv #(get annotation-type-values % (name %)) annotations)))

(defn- package-plan [operation params include-annotations]
  (plan/plan (req/datasets operation
                           (cond-> params
                             include-annotations
                             (assoc :include-annotation-type
                                    (resolve-annotation-types include-annotations))))
             make-package))

(defn gene-package-plan
  "A plan for gene `gene-id`'s dataset package (see `make-package`).
  Options: :include-annotations, e.g. [:fasta-protein]."
  [gene-id & [{:keys [include-annotations]}]]
  (package-plan :download-gene-package {:gene-ids [(parse-long (str gene-id))]} include-annotations))

(defn assembly-package-plan
  "A plan for assembly `accession`'s dataset package (see `make-package`).
  Options: :include-annotations, e.g. [:prot-fasta]."
  [accession & [{:keys [include-annotations]}]]
  (package-plan :download-assembly-package {:accessions [accession]} include-annotations))

(defn download-gene-package
  [client gene-id & [opts]]
  (runner/run client (gene-package-plan gene-id opts)))

(defn download-assembly-package
  [client accession & [opts]]
  (runner/run client (assembly-package-plan accession opts)))

(defn- annotations-opt
  "The entity's :ncbi/include-annotations metadata, as package options."
  [e]
  (when-let [a (:ncbi/include-annotations (meta e))]
    {:include-annotations a}))

(def nav-edges
  "Edges to dataset packages, which are read on the JVM only."
  {[:ncbi/gene :ncbi.nav/package]
   {:plan (fn [_ e]
            (when-let [id (:gene_id e)]
              (gene-package-plan id (annotations-opt e))))}

   [:ncbi/assembly :ncbi.nav/package]
   {:plan (fn [_ e]
            (when-let [acc (:accession e)]
              (assembly-package-plan acc (annotations-opt e))))}})
