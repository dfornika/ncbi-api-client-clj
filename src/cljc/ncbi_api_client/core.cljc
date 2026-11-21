(ns ncbi-api-client.core
  "The public API, the same on both platforms. Each function builds a plan
  and runs it with the client's runner, so on the JVM it returns the
  value, and in the browser a promise of it. Package downloads and
  `fetch-all` are JVM only."
  (:require [ncbi-api-client.bridge :as bridge]
            [ncbi-api-client.client :as client]
            [ncbi-api-client.datasets :as ds]
            [ncbi-api-client.eutils :as eu]
            [ncbi-api-client.lookup :as lk]
            [ncbi-api-client.nav :as nav]
            [ncbi-api-client.plan :as plan]
            #?(:clj [ncbi-api-client.package :as pkg])))

(defn connect
  "Create a client for both the NCBI Datasets API and E-utilities.
   Returns an opaque client handle — pass it as the first argument to all
   other functions in this namespace.

   Options:
     :api-key   - NCBI API key: a string, a function or an atom, read on each
                  request (default: on the JVM, the NCBI_API_KEY environment
                  variable; in the browser, none)
     :tool      - tool name sent with eutils requests
     :email     - developer email sent with eutils requests (default: none)
     :base-urls - {:datasets \"…\" :eutils \"…\"} to send requests elsewhere
     :http      - JVM: the function that sends requests (default: hato)
     :fetch     - browser: a function like js/fetch (default: js/fetch)

   Without an API key, requests are rate-limited to ~3/sec. With a key, ~10/sec."
  ([] (connect {}))
  ([opts] (assoc (client/create-client opts)
                 :nav-edges (merge ds/nav-edges bridge/nav-edges
                                   #?(:clj pkg/nav-edges)))))

;; --- Datasets API ---

(defn- ids [x] (if (sequential? x) (vec x) [x]))
(defn- gene-ids [x] (mapv #(parse-long (str %)) (ids x)))

(defn- by-ids
  "One entity when `id-or-ids` is one, a page of them when it's a list."
  [client operation params id-or-ids entity-type]
  (nav/run client ((if (sequential? id-or-ids) ds/page-plan ds/one-plan)
                   client operation params entity-type)))

(defn fetch
  "One page of Datasets operation `operation` with `params`, as
  `entity-type` entities. The page's metadata has :async.nav/total, and
  :async.nav/next for the next page."
  [client operation params entity-type]
  (nav/run client (ds/page-plan client operation params entity-type)))

(defn taxonomy
  "Fetch taxonomy records by taxon ID.
   Pass a single ID string for one record, or a vector of IDs for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/assemblies, :ncbi.nav/genes, :ncbi.nav/viruses,
             :ncbi.nav/children, :ncbi.nav/lineage, :ncbi.nav/image, :ncbi.nav/links"
  [client taxon-ids-or-id]
  (by-ids client :taxonomy-data-report {:taxons (mapv str (ids taxon-ids-or-id))}
          taxon-ids-or-id :ncbi/taxonomy))

(defn assembly
  "Fetch genome assembly records by accession.
   Pass a single accession string for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/organism, :ncbi.nav/genes, :ncbi.nav/biosample,
             :ncbi.nav/sequences, :ncbi.nav/annotations, :ncbi.nav/package (JVM),
             :ncbi.nav/links"
  [client accessions-or-accession]
  (by-ids client :genome-dataset-report {:accessions (ids accessions-or-accession)}
          accessions-or-accession :ncbi/assembly))

(defn gene
  "Fetch gene records by gene ID.
   Pass a single integer/string ID for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/organism, :ncbi.nav/orthologs, :ncbi.nav/products,
             :ncbi.nav/assemblies, :ncbi.nav/package (JVM), :ncbi.nav/links"
  [client gene-ids-or-id]
  (by-ids client :gene-reports-by-id {:gene_ids (gene-ids gene-ids-or-id)}
          gene-ids-or-id :ncbi/gene))

(defn biosample
  "Fetch biosample records by accession.
   Pass a single accession string for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/organism, :ncbi.nav/assemblies"
  [client accessions-or-accession]
  (by-ids client :bio-sample-dataset-report {:accessions (ids accessions-or-accession)}
          accessions-or-accession :ncbi/biosample))

(defn sequences
  "Fetch sequence records for a genome assembly.

   Returns a tagged vector of sequence entities.
   Nav keys: :ncbi.nav/assembly"
  [client assembly-accession]
  (fetch client :genome-sequence-report {:accession assembly-accession} :ncbi/sequence))

(defn gene-products
  "Fetch gene product (transcript/protein) records by gene ID.
   Pass a single integer/string ID for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/gene"
  [client gene-ids-or-id]
  (by-ids client :gene-product-reports-by-id {:gene-ids (gene-ids gene-ids-or-id)}
          gene-ids-or-id :ncbi/gene-product))

(defn annotations
  "Fetch genome annotations for an assembly accession.

   Returns a tagged vector of annotation entities.
   Nav keys: :ncbi.nav/assembly, :ncbi.nav/gene"
  [client assembly-accession]
  (fetch client :genome-annotation-report {:accession assembly-accession} :ncbi/annotation))

(defn virus
  "Fetch virus records by taxon ID.

   Returns a tagged vector of virus entities.
   Nav keys: :ncbi.nav/taxonomy, :ncbi.nav/host, :ncbi.nav/annotations"
  [client taxon]
  (fetch client :virus-reports-by-taxon {:taxon (str taxon)} :ncbi/virus))

(defn virus-by-accession
  "Fetch virus records by accession.
   Pass a single accession string for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/taxonomy, :ncbi.nav/host, :ncbi.nav/annotations"
  [client accessions-or-accession]
  (by-ids client :virus-reports-by-acessions {:accessions (ids accessions-or-accession)}
          accessions-or-accession :ncbi/virus))

(defn virus-annotations
  "Fetch virus annotation records by taxon ID.

   Returns a tagged vector of virus annotation entities.
   Nav keys: :ncbi.nav/virus"
  [client taxon]
  (fetch client :virus-annotation-reports-by-taxon {:taxon (str taxon)} :ncbi/virus-annotation))

(defn virus-annotations-by-accession
  "Fetch virus annotation records by accession.
   Pass a single accession string for one record, or a vector for a batch.

   Returns a single entity map (scalar) or a tagged vector of entities (collection).
   Nav keys: :ncbi.nav/virus"
  [client accessions-or-accession]
  (by-ids client :virus-annotation-reports-by-acessions {:accessions (ids accessions-or-accession)}
          accessions-or-accession :ncbi/virus-annotation))

#?(:clj
   (defn download-gene-package
     "Download a gene dataset package as a ZIP archive (JVM only).
      Returns a navigable package map — use datafy/nav to extract FASTA sequences,
      protein data, and data reports."
     [client gene-id & [opts]]
     (pkg/download-gene-package client gene-id opts)))

#?(:clj
   (defn download-assembly-package
     "Download a genome assembly dataset package as a ZIP archive (JVM only).
      Returns a navigable package map — use datafy/nav to extract FASTA sequences,
      protein data, and data reports."
     [client accession & [opts]]
     (pkg/download-assembly-package client accession opts)))

;; --- Pagination ---

#?(:clj
   (defn fetch-all
     "Auto-paginate through all results of a Datasets API operation (JVM only).
      Returns a lazy sequence of tagged entity maps across all pages.

      Arguments:
        client      - a client from `connect`
        operation   - an operation keyword (e.g. :genome-dataset-reports-by-taxon)
        params      - parameter map for the operation
        entity-type - entity type keyword (e.g. :ncbi/assembly)"
     [client operation params entity-type]
     (ds/fetch-all client operation params entity-type)))

;; --- E-utilities ---

(defn search
  "Search an Entrez database by keyword: a page of navigable records.
   nav :ncbi.nav/datasets-entity bridges into the Datasets entity graph,
   and the :ncbi.elink/* keys follow links between databases. The page's
   metadata has :async.nav/total, and :async.nav/next for the next page.

   Options: :retmax, :retstart, :sort, :field (same as eutils esearch)."
  [client db term & [opts]]
  (nav/run client (bridge/search-plan client db term opts)))

(defn einfo
  "List available Entrez databases, or get field/link info for a specific database.
   With no db argument, returns a vector of database name strings.
   With a db name, returns a map of database metadata (fields, links, count)."
  ([client] (nav/run client (eu/einfo)))
  ([client db] (nav/run client (eu/einfo db))))

(defn esummary
  "Fetch document summaries for UIDs from an Entrez database.
   Returns a vector of summary maps."
  [client db ids]
  (nav/run client (eu/esummary db ids)))

(defn elink
  "Find linked UIDs across Entrez databases.
   Returns a vector of link result maps, each with :dbto, :linkname, :ids.

   Options: :db, :linkname, :cmd (see eutils elink documentation)."
  [client dbfrom ids & [opts]]
  (nav/run client (eu/elink dbfrom ids opts)))

(defn elink-available
  "List available link types from an Entrez database for given UIDs.
   Returns a vector of {:linkname :dbto :menutag} maps."
  [client dbfrom ids]
  (nav/run client (eu/elink-available dbfrom ids)))

(defn efetch
  "Records `ids` of an Entrez database as text, in format :rettype
  (default \"fasta\")."
  [client db ids & [opts]]
  (nav/run client (eu/efetch db ids opts)))

(defn fasta
  "The FASTA of sequence `accessions` (one, or a list), from nuccore (or
  the :db option's database, e.g. \"protein\")."
  [client accessions & [{:keys [db] :or {db "nuccore"}}]]
  (nav/run client (eu/efetch db accessions)))

;; --- Lookups by accession ---

(defn links
  "What each of `records` links to by `linkname` (from their own
  database: \"biosample_sra\" for BioSamples): a vector, in the order of
  `records`, of vectors of linked records, each with
  :ncbi.elink/total-count in its metadata. For expanding a column: each
  BioSample's SRA experiments, say. `records` are E-utilities records of
  one database, as `search` and `lookup` give them (one `lookup` didn't
  find links to none); 200 go in a request, and each links to at most
  `bridge/max-link-ids`."
  [client records linkname]
  (nav/run client (bridge/links-plan client records linkname)))

(defn lookup
  "What NCBI says about each of `accessions`, of any kind
  (`ncbi-api-client.accessions`): a vector in the order asked (each
  once; blanks and ones no database is known for are left out). Each is
  the record (an esummary document, navigable like a search result) with
  the accession it was asked about in its metadata as :ncbi/asked; or,
  for one NCBI hasn't got, {:ncbi/asked :ncbi/error :not-found
  :ncbi.eutils/db}. `rows/table` makes them rows.

  200 accessions go in a request, one database at a time; in the browser
  the requests go at once (each waits its turn). Options:
    :on-progress - called with how many accessions have been asked about
                   so far, after each request"
  [client accessions & [{:keys [on-progress]}]]
  (let [asked (lk/asked accessions)
        done  (atom 0)
        plans (for [[_ batch :as b] (lk/batches asked)]
                (plan/then (lk/batch-plan client b)
                           (fn [found]
                             (when on-progress (on-progress (swap! done + (count batch))))
                             found)))]
    (nav/run-all client plans #(lk/in-order asked %))))
