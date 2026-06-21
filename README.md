# ncbi-api-client-clj

A Clojure client for the [NCBI Datasets API](https://www.ncbi.nlm.nih.gov/datasets/) (v2), built on [Martian](https://github.com/oliyh/martian) and Clojure's `datafy`/`nav` protocols.

Every entity returned by the library is navigable: call `datafy` to see what relationships are available, then `nav` to lazily follow them. This turns the NCBI API into an explorable graph of biological data you can traverse interactively in a REPL.

## Quick Start

```clojure
(require '[ncbi-api-client.core :as ncbi]
         '[clojure.datafy :refer [datafy nav]])

(def client (ncbi/connect))

;; Fetch human taxonomy
(def human (first (ncbi/taxonomy client ["9606"])))

;; See what's navigable
(datafy human)
;; => {...  :ncbi.nav/assemblies :deferred
;;         :ncbi.nav/genes      :deferred
;;         :ncbi.nav/viruses    :deferred  ...}

;; Follow a relationship (lazy API call)
(def assemblies (nav (datafy human) :ncbi.nav/assemblies :deferred))

;; Navigate from assembly to its biosample
(def biosample (nav (datafy (first assemblies)) :ncbi.nav/biosample :deferred))
```

## Installation

Add to your `deps.edn`:

```clojure
{:deps {io.github.dfornika/ncbi-api-client-clj
        {:git/url "https://github.com/dfornika/ncbi-api-client-clj.git"
         :git/sha "LATEST_SHA"}}}
```

### API Key (optional)

Set the `NCBI_API_KEY` environment variable for higher rate limits. Without it, requests still work but are rate-limited.

## API

All functions take a `client` as the first argument, created via `ncbi/connect`.

```clojure
(def client (ncbi/connect))
;; Or with options:
(def client (ncbi/connect {:api-key "your-key" :base-url "https://..."}))
```

### Direct Lookup Functions

| Function | Arguments | Returns |
|----------|-----------|---------|
| `ncbi/taxonomy` | `client taxon-ids` | Taxonomy records |
| `ncbi/assembly` | `client accessions` | Genome assembly records |
| `ncbi/gene` | `client gene-ids` | Gene records |
| `ncbi/biosample` | `client accessions` | BioSample records |
| `ncbi/sequences` | `client assembly-accession` | Sequence records for an assembly |
| `ncbi/gene-products` | `client gene-ids` | Gene product records |
| `ncbi/annotations` | `client assembly-accession` | Genome annotation records |
| `ncbi/virus` | `client taxon` | Virus genome records by taxon |
| `ncbi/virus-by-accession` | `client accessions` | Virus genome records by accession |
| `ncbi/virus-annotations` | `client taxon` | Virus annotation records by taxon |
| `ncbi/virus-annotations-by-accession` | `client accessions` | Virus annotation records by accession |

Every function returns a vector of entities tagged with `datafy`/`nav` metadata.

### Navigation Graph

Each entity type exposes navigable relationships via `datafy`/`nav`:

| Entity | Nav keys |
|--------|----------|
| `:ncbi/taxonomy` | assemblies, genes, viruses, children, lineage, image, links |
| `:ncbi/assembly` | organism, genes, biosample, sequences, annotations, links |
| `:ncbi/gene` | organism, orthologs, assemblies, products, links |
| `:ncbi/biosample` | organism, assemblies |
| `:ncbi/sequence` | assembly |
| `:ncbi/gene-product` | gene |
| `:ncbi/annotation` | assembly, gene |
| `:ncbi/virus` | taxonomy, host, annotations |
| `:ncbi/virus-annotation` | virus |

```clojure
;; Multi-hop navigation: taxon -> assembly -> biosample -> organism
(let [human     (first (ncbi/taxonomy client ["9606"]))
      assembly  (first (nav (datafy human) :ncbi.nav/assemblies :deferred))
      biosample (nav (datafy assembly) :ncbi.nav/biosample :deferred)
      organism  (nav (datafy biosample) :ncbi.nav/organism :deferred)]
  (get-in organism [:current_scientific_name :name]))
;; => "Homo sapiens"
```

### Pagination

Results are paginated. You can handle pages manually or auto-paginate:

```clojure
;; First page only (default)
(def page (ncbi/taxonomy client ["9606"]))
(:ncbi/total-count (meta page))      ;; total available
(:ncbi/next-page-token (meta page))  ;; nil if no more pages

;; Auto-paginate with fetch-all (returns lazy seq)
(require '[ncbi-api-client.datafy :as d])
(def all-assemblies
  (d/fetch-all client :genome-dataset-reports-by-taxon
               {:taxons ["9606"]} :ncbi/assembly))

;; Manual page-by-page via nav
(def page2 (nav (datafy page) :ncbi.nav/next-page :deferred))
```

### External Links

Taxonomy, assembly, and gene entities support `:ncbi.nav/links`, which returns URLs to Wikipedia, FTP, PubMed, GDV, and other external resources:

```clojure
(nav (datafy (first (ncbi/taxonomy client ["9606"])))
     :ncbi.nav/links :deferred)
;; => {:wikipedia_url "https://..." :assembly_links [...] ...}
```

## Development

Requires JDK 21+ and the [Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
# Bootstrap all tooling (Clojure CLI, Babashka, nREPL tools)
bash scripts/setup-dev-env.sh

# Start nREPL
clojure -M:dev:nrepl    # port 7888

# Load demo functions
# (from a connected REPL)
(require '[demo :reload true])
(demo/taxonomy-summary client "9606")
(demo/virus-genes client "NC_045512.2")
(demo/supported-operations)
```

## License

Copyright Dan Fornika. All rights reserved.
