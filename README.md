# ncbi-api-client-clj

A Clojure and ClojureScript client for NCBI's [Datasets API](https://www.ncbi.nlm.nih.gov/datasets/) (v2) and [E-utilities](https://www.ncbi.nlm.nih.gov/books/NBK25501/), built on Clojure's `datafy`/`nav` protocols.

What NCBI knows becomes a graph you can walk. You can start from a taxon, a keyword search or a column of accessions, and follow links from there:

```
taxon → assemblies → BioSample → SRA experiments → BioProject → assemblies
```

`datafy` on any entity shows where it leads, and `nav` follows a link.

- **The same API on the JVM and in the browser.** The core is `.cljc`. On the JVM, functions return values. In the browser (or on Node), they return promises, and `nav` returns a *deferred value* that fetches only when asked. That suits data browsers, which call `nav` on every render.
- **Requests are data.** Each function builds a *plan*: a description of a request, plus what to do with the answer. A small runner on each platform sends it. You can inspect, log or test a plan, or run it some other way, and the API key is never part of it.
- **Polite to NCBI.** The library keeps to NCBI's rate limits: 3 requests a second, or 10 with an API key. It retries 429s and 5xx, and sends your tool name with E-utilities requests.

## Contents

- [Installation](#installation)
- [Quick start: JVM](#quick-start-jvm)
- [Quick start: browser](#quick-start-browser-and-node)
- [The client](#the-client)
- [Entities, pages and deferred values](#entities-pages-and-deferred-values)
- [API](#api)
- [The navigation graph](#the-navigation-graph)
- [Lookups by accession, and rows](#lookups-by-accession-and-rows)
- [Errors](#errors)
- [Plans: running requests your own way](#plans-running-requests-your-own-way)
- [Development](#development)

## Installation

The library is a git dependency. Pick a commit from `main`.

**JVM** (`deps.edn`):

```clojure
{:deps {io.github.dfornika/ncbi-api-client-clj
        {:git/url "https://github.com/dfornika/ncbi-api-client-clj.git"
         :git/sha "…"}}}
```

**ClojureScript** (`deps.edn`, used by shadow-cljs with `:deps true`): exclude the JVM's HTTP and JSON libraries, which the browser doesn't need.

```clojure
{:deps {io.github.dfornika/ncbi-api-client-clj
        {:git/url    "https://github.com/dfornika/ncbi-api-client-clj.git"
         :git/sha    "…"
         :exclusions [hato/hato cheshire/cheshire]}}}
```

The browser code has no JavaScript dependencies: it uses `fetch` and promises. It builds under `:advanced` optimizations. NCBI's APIs allow requests from web pages (CORS), so a page can call them directly.

## Quick start: JVM

```clojure
(require '[ncbi-api-client.core :as ncbi]
         '[clojure.datafy :refer [datafy nav]])

(def client (ncbi/connect))   ; reads NCBI_API_KEY from the environment, if set

(def human (ncbi/taxonomy client "9606"))

;; What it leads to (datafy never makes a request)
(datafy human)
;; => {:tax_id 9606, :rank "SPECIES", …
;;     :ncbi.nav/assemblies :deferred, :ncbi.nav/genes :deferred, :ncbi.nav/lineage :deferred, …}

;; Follow a link: one page of assemblies
(def assemblies (nav (datafy human) :ncbi.nav/assemblies :deferred))
(count assemblies)                       ; => 20
(:async.nav/total (meta assemblies))     ; => 2594

;; An assembly → its BioSample → the BioSample's SRA experiments
(def k12 (ncbi/assembly client "GCF_000005845.2"))                ; E. coli K-12
(def biosample (nav (datafy k12) :ncbi.nav/biosample :deferred))   ; SAMN02604091
(def experiments (nav (datafy biosample) :ncbi.nav/sra :deferred))

;; Or start from a search
(def hits (ncbi/search client "gene" "BRCA1[sym] AND human[orgn]" {:retmax 5}))
(nav (datafy (first hits)) :ncbi.nav/datasets-entity :deferred)   ; BRCA1, as a Datasets gene
```

## Quick start: browser (and Node)

The functions are the same, but each returns a promise:

```clojure
(ns my.app
  (:require [clojure.datafy :refer [datafy nav]]
            [ncbi-api-client.core :as ncbi]))

(def client (ncbi/connect {:tool "my-app"}))

(-> (ncbi/taxonomy client "9606")
    (.then (fn [human]
             ;; nav makes no request: it gives a deferred value
             (let [d (nav (datafy human) :ncbi.nav/assemblies :deferred)]
               (:ncbi/describe d)                     ; => "assemblies of taxonomy 9606"
               ((:async.nav/fetch (meta d))))))       ; a promise of the page
    (.then (fn [assemblies]
             (println (count assemblies) "of" (:async.nav/total (meta assemblies))))))
```

## The client

```clojure
(ncbi/connect {:api-key   "…"            ; a string, a function, or an atom: read on each request
               :tool      "my-app"       ; sent with E-utilities requests
               :email     "…"            ; sent with E-utilities requests only if given
               :base-urls {:datasets "…" :eutils "…"}   ; to send requests elsewhere
               :http      (fn [request] response)       ; JVM: how to send (default: hato)
               :fetch     (fn [url options] promise)})  ; browser: like js/fetch (default: js/fetch)
```

- **The API key** is optional. Without one, NCBI allows 3 requests a second; with one, 10. On the JVM the default is the `NCBI_API_KEY` environment variable; in the browser there's none unless you give one.
  - The key is read for each request, so a page can let the user change it (pass an atom).
  - It's added only when the request is sent: as the `api-key` header for Datasets, and as `api_key` in the form for E-utilities. It's never put into a request description, an entity or an error, and the client doesn't print it.
- **`:fetch`** lets a page wrap requests, for example to show that NCBI is busy: `(fn [url opts] (spinner-on!) (.finally (js/fetch url opts) spinner-off!))`.
- **One client, one rate limit:** all its requests, to both APIs, share it.
- **The defaults** for `:tool` are `ncbi-api-client-clj` on the JVM and `ncbi-api-client-cljs` in the browser. NCBI asks that tools identify themselves, so set your own.

## Entities, pages and deferred values

**Entities** are plain maps, as NCBI sends them (keyword keys), with `datafy`/`nav` in their metadata.
- `datafy` adds a `:deferred` value for each link the entity has. It never makes a request.
- `nav` on one of those keys follows the link.
- `(:ncbi/type (meta e))` says what kind of entity it is: `:ncbi/taxonomy`, `:ncbi/assembly`, `:ncbi.eutils/result`…

**Pages.** Following a link gives one page: a vector of entities. Its metadata says how to get more:

| Key | |
|---|---|
| `:async.nav/total` | how many there are in all, when NCBI says |
| `:async.nav/next` | a deferred value for the next page, when there is one |

`datafy` on a page also shows `:ncbi.nav/next-page :deferred` (in its metadata), and `nav` follows it. Search results page the same way. They're kept on NCBI's history server, so each later page is a single request; if the history has expired, the search runs again from there. On the JVM, `ncbi/fetch-all` gives every page of a Datasets operation as a lazy sequence.

**Deferred values.** In the browser, `nav` returns a deferred value instead of fetching:

```clojure
(with-meta {:ncbi/deferred :ncbi.nav/assemblies            ; what it leads to
            :ncbi/describe "assemblies of taxonomy 9606"
            :ncbi/request  {…}}                            ; the first request (never the key)
  {:async.nav/fetch (fn [] promise-of-the-value)
   :async.nav/key   [:ncbi.nav/assemblies {…}]})           ; equal for equal requests
```

A tool calls `:async.nav/fetch` when someone chooses the link. `:async.nav/key` is plain data, equal across calls, so a tool can remember what it has already fetched. The promise resolves to an entity or a page, which can be followed in turn. The `:async.nav/*` keys aren't NCBI-specific, so other clients can use the same contract. `:async.nav/next` on a page is a deferred value on both platforms; on the JVM its fetch returns the page.

## API

Every function in `ncbi-api-client.core` takes the client first. On the JVM it returns the value; in the browser, a promise of it.

**Datasets.** Give one ID for one entity, or a vector for a page of them:

| Function | Takes | Gives |
|---|---|---|
| `taxonomy` | taxon IDs or names | `:ncbi/taxonomy` |
| `assembly` | assembly accessions (GCF_, GCA_) | `:ncbi/assembly` |
| `gene` | gene IDs | `:ncbi/gene` |
| `gene-products` | gene IDs | `:ncbi/gene-product` |
| `biosample` | BioSample accessions | `:ncbi/biosample` |
| `virus-by-accession` | virus genome accessions | `:ncbi/virus` |
| `virus-annotations-by-accession` | virus genome accessions | `:ncbi/virus-annotation` |
| `sequences` | an assembly accession | a page of `:ncbi/sequence` |
| `annotations` | an assembly accession | a page of `:ncbi/annotation` |
| `virus` | a taxon | a page of `:ncbi/virus` |
| `virus-annotations` | a taxon | a page of `:ncbi/virus-annotation` |
| `fetch` | any Datasets operation, its parameters, an entity type | a page |

`fetch` covers all 107 operations in the Datasets spec, by the operation's name (`:genome-dataset-reports-by-taxon`…). Parameter names may use `-` or `_`.

**E-utilities:**

| Function | |
|---|---|
| `(search client db term opts)` | a page of navigable records (`:retmax`, `:retstart`, `:sort`, `:field`) |
| `(lookup client accessions opts)` | a record for each accession, of any kind, in the order asked ([below](#lookups-by-accession-and-rows)) |
| `(links client records linkname)` | what each record links to, in order: each BioSample's SRA experiments, say |
| `(fasta client accessions opts)` | FASTA text, from nuccore (or `{:db "protein"}`) |
| `einfo`, `esummary`, `elink`, `elink-available`, `efetch` | the E-utilities themselves, read from JSON |

**JVM only:**

| Function | |
|---|---|
| `(fetch-all client operation params entity-type)` | every page, as a lazy sequence |
| `download-gene-package`, `download-assembly-package` | a dataset package (zip): `nav` into its FASTA and reports |

Lower down, `ncbi-api-client.eutils`, `.datasets`, `.bridge` and `.lookup` have the plans these run; see [Plans](#plans-running-requests-your-own-way).

## The navigation graph

**Datasets entities**, and the keys `datafy` adds to them:

| Entity | Nav keys (`:ncbi.nav/…`) |
|---|---|
| `:ncbi/taxonomy` | `assemblies`, `genes`, `viruses`, `children`, `lineage`, `image`, `links` |
| `:ncbi/assembly` | `organism`, `genes`, `biosample`, `sequences`, `annotations`, `links`, `package` (JVM) |
| `:ncbi/gene` | `organism`, `orthologs`, `assemblies`, `products`, `links`, `package` (JVM) |
| `:ncbi/biosample` | `organism`, `assemblies`, `sra` (into E-utilities) |
| `:ncbi/sequence` | `assembly` |
| `:ncbi/gene-product` | `gene` |
| `:ncbi/annotation` | `assembly`, `gene` |
| `:ncbi/virus` | `taxonomy`, `host`, `annotations` |
| `:ncbi/virus-annotation` | `virus` |

`:ncbi.nav/links` leads out of NCBI: Wikipedia, GBIF, FTP, the genome data viewer and so on. `:ncbi.nav/image` is nil for a taxon without an image.

**E-utilities records** (from `search`, `lookup`, `links`, and links followed) are esummary documents. `datafy` adds the following, again without making a request:

- `:ncbi.nav/datasets-entity`: the same thing as a Datasets entity, for gene, taxonomy, assembly and BioSample records.
- `:ncbi.elink/<linkname>`: the links between Entrez databases used most:

  | From | Links |
  |---|---|
  | biosample | `biosample_sra` |
  | sra | `sra_bioproject` |
  | bioproject | `bioproject_assembly` |
  | assembly | `assembly_biosample` |
  | taxonomy | `taxonomy_assembly` |
  | gene | `gene_pubmed`, `gene_nuccore` |

- `:ncbi.elink/available`: asks NCBI for every link the record has, as a map of `:ncbi.elink/<linkname>` keys that `nav` follows.

Following a link gives a page of up to 200 records, with `:async.nav/next` for the rest, and the total in `:ncbi.elink/total-count`. The records are of the other database, so you can keep going.

```clojure
;; A BioSample → its SRA experiments → their BioProjects → a project's assemblies
(def sample (first (ncbi/lookup client ["SAMN02604091"])))          ; E. coli K-12
(def experiments (nav (datafy sample) :ncbi.elink/biosample_sra :deferred))
(def projects (nav (datafy (first experiments)) :ncbi.elink/sra_bioproject :deferred))
(map :project_acc projects)                                         ; => ("PRJNA706563" "PRJNA225")
(nav (datafy (second projects)) :ncbi.elink/bioproject_assembly :deferred)
;; => [{:assemblyaccession "GCF_000005845.2" …}]                      ; back to K-12's assembly
```

## Lookups by accession, and rows

`lookup` takes accessions of any kind, and gives a result for each, once, in the order asked:
- nuccore sequences: MN908947.3, NC_045512.2…
- SRA runs, experiments, studies and samples: SRR…, SRX…, SRP…, SRS…
- BioSamples (SAMN…), BioProjects (PRJNA…), and assemblies (GCA_…, GCF_…).

Blanks, and strings that aren't accessions, are left out.

```clojure
(def records (ncbi/lookup client ["MN908947.3" "SAMN14154201" "SRR11140748"
                                  "GCA_000196035.1" "PRJNA607948" "ZZ999999.1"]
                          {:on-progress #(println % "asked about")}))
```

- **Each result is the record** (navigable, like a search result), with the accession asked about in its metadata as `:ncbi/asked`. A version isn't needed, and an assembly is found by its GenBank or RefSeq accession.
- **One NCBI hasn't got** is `{:ncbi/asked "ZZ999999.1" :ncbi/error :not-found :ncbi.eutils/db "nuccore"}`.
- **Batching:** 200 accessions go in a request, one database at a time. In the browser the requests go at once, each still waiting its turn.

For tables, `ncbi-api-client.rows` makes flat rows that join back onto the column they came from:

```clojure
(require '[ncbi-api-client.rows :as rows])

(rows/table records {:as :sample_id})
;; => [{:sample_id "MN908947.3" :database "nuccore" :accession "MN908947.3"
;;      :organism "Severe acute respiratory syndrome coronavirus 2"
;;      :country "China" :host "Homo sapiens" :collection_date "Dec-2019" …}
;;     …
;;     {:sample_id "ZZ999999.1" :database "nuccore" :error "NCBI hasn't got it"}]
```

Each database's details become columns: a sequence's source (country, host, collection date…), a BioSample's attributes, an SRA experiment's runs, platform and library, and an assembly's statistics. `ncbi-api-client.accessions` has the patterns: `database-of`, `kind-of`, `refseq-nucleotide?`, `refseq-protein?`.

To expand a column, `(ncbi/links client records "biosample_sra")` gives each record's linked records, in order. `records` must all be from one database. It makes one elink per 200 records, and one esummary per 200 linked records.

## Errors

When NCBI answers with an error, you get an `ex-info`, thrown on the JVM or rejected in the browser. It says what was asked and what NCBI said:

```clojure
(try (ncbi/fetch client :gene-reports-by-id {:gene_ids ["abc"]} :ncbi/gene)
     (catch Exception e
       (ex-message e)
       (ex-data e)))

(ex-message e)  ; => "NCBI said 400 to datasets gene-reports-by-id: Invalid argument type
                ;     ('parameter: gene_ids') provided. …"
(ex-data e)     ; => {:ncbi/error :http-error   ; or :not-found, :rate-limited, :server-error
                ;     :status 400 :ncbi/request {…} …}
```

A lookup that finds nothing isn't an error: `(ncbi/taxonomy client "no-such-taxon")` is nil, and a page is empty. 429s and 5xx are retried up to 3 times, as `Retry-After` says or with backoff, before they become errors. An unknown operation or parameter is an error before anything is sent (`:ncbi/error :unknown-operation`, `:unknown-parameters`, `:missing-parameters`).

## Plans: running requests your own way

The `.cljc` core makes no requests. It describes them:

```clojure
(require '[ncbi-api-client.request :as req]
         '[ncbi-api-client.eutils :as eutils]
         '[ncbi-api-client.plan :as plan])

(req/datasets :taxonomy-data-report {:taxons ["9606"]})
;; => {:api :datasets :op :taxonomy-data-report :method :get
;;     :url "https://api.ncbi.nlm.nih.gov/datasets/v2/taxonomy/taxon/9606/dataset_report"
;;     :as :json :accept "application/json"}

(def p (eutils/esearch "gene" "BRCA1 human"))
(:request p)   ; the description: E-utilities requests are POSTed forms
((:then p) {:esearchresult {:idlist ["672"] :count "1"}})
;; => {:ids ["672"] :count 1 …}
```

A plan is a request and a `then`. The `then` takes the decoded answer, and returns a value or another plan. So a step that takes several requests, like esearch then esummary, is a plan that returns plans. Running one is a loop:

```clojure
(defn run [send! x]                 ; send!: a description -> the decoded body
  (if (plan/plan? x)
    (recur send! ((:then x) (send! (:request x))))
    x))
```

The library's runners (`runner.clj`, `runner.cljs`) are that loop, plus the parts every request needs:
- adding the key, tool and email, as `request/outgoing` says;
- keeping to the rate limit;
- retrying, and decoding the answer.

A client's `:run` is the runner it uses. A host with its own way of sending requests (a server, a worker, a test) can run plans itself, or give `connect` an `:http` or `:fetch`. The plans for links and lookups (`bridge/search-plan`, `bridge/links-plan`, `lookup/batch-plan`, `datasets/page-plan`) are public for the same reason.

## Development

Requires JDK 21+ and the [Clojure CLI](https://clojure.org/guides/install_clojure); the ClojureScript tests also need Node.

```bash
bash scripts/setup-dev-env.sh     # optional: installs the tooling
clojure -M:dev:nrepl              # nREPL on port 7888; then (require '[demo :reload true])
```

```bash
clojure -M:test                                         # JVM tests
clojure -M:cljs-test && node target/test/node-tests.js  # ClojureScript tests, on Node
clojure -X:gen-routes                                   # regenerate the route table from spec/
```

The tests make no requests: they answer from stubs and from answers recorded from NCBI (`test-resources/fixtures/`). CI runs both suites. `src/dev/demo.clj` has examples for every entity type. `docs/design.md` explains the design and how it came to be.

## License

[MIT](LICENSE). Copyright (c) 2026 Dan Fornika.
