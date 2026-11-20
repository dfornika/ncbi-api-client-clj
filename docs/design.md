# Design: one NCBI client for the JVM and the browser

*Status: agreed direction, not built yet (2026-11-19).*

The library is JVM Clojure today. This document is the plan for making it
work from ClojureScript as well, with the
[Data Explorer](https://github.com/dfornika/genomics-data-explorer) as the
first browser consumer. It answers the questions in the Data Explorer's
hand-off (`doc/ncbi_client_handoff.md` in `~/code/data-explorer-cljs`), and
replaces the earlier spike notes and codebase review, which are in git
history (before this document's commit) if needed.

## Goals

- **One client, two platforms.** The same edge table, request building and
  response parsing on the JVM and in the browser.
- **The JVM REPL stays as it is:** `(nav (datafy x) :ncbi.nav/assemblies :deferred)`
  makes the request and returns the entities.
- **In the browser, `datafy` and `nav` never make a request.** The Data
  Explorer calls both for every cell on every render. `nav` returns a
  *deferred* value with a function that makes the requests and returns a
  promise.
- **Light in the browser:** no Martian, no core.async, no new JS dependencies.
  The Data Explorer's release build is one ~9 MB HTML file compiled with
  `:advanced`.

## Decisions

### Martian is dropped

The shared core describes requests itself, so keeping Martian on the JVM would
mean two ways of building the same request, and they would drift apart.
Martian also brings problems of its own:

- `bootstrap-openapi` reads YAML on the JVM only;
- `martian-cljs-http` returns core.async channels;
- the array path parameter problem needed a string-rewriting interceptor
  (`fix-array-path-params`).

Instead, a **route table** (`ncbi-api-client.datasets.routes`, `.cljc`) holds
data for the Datasets operations the library uses, about 30 of the spec's 107:

```clojure
{:taxonomy-data-report
 {:method :get
  :path   ["/taxonomy/taxon/" :taxons "/dataset_report"]   ; array params joined with ","
  :query  #{:returned_content :page_size :page_token}}
 ...}
```

- The table is generated once from `resources/openapi3.docs.yaml` by a dev
  script, and checked in. It can also be written by hand.
- A JVM test reads the spec and checks every route against it: method, path
  and parameter names.
- Martian may stay in the `:dev` alias for `martian/explore`, as a way to
  browse the spec. Nothing in `src/main` depends on it.

### Plans: describing requests, not making them

A **plan** is a request and what to do with its answer:

```clojure
{:ncbi/request {:api :datasets :method :get :url "…" :query {…}}
 :ncbi/then    (fn [body] …)}   ; returns a value, or another plan
```

Steps that take several requests are plans that return plans:

- an accession lookup is esearch, then esummary;
- following a link is elink, then a Datasets fetch or esummary;
- a page leads to the next one.

**No API key in a request.** The runner adds it when it sends the request, so
a request can be shown in an EDN view or an error without giving the key away.

Each platform has a small runner:

| | JVM (`.clj`) | Browser (`.cljs`) |
|---|---|---|
| HTTP | hato | the `:fetch` option (`js/fetch` by default; the Data Explorer passes `busy/fetch`) |
| JSON | cheshire, keyword keys | `JSON.parse` + `js->clj :keywordize-keys` |
| Waiting | `Thread/sleep` | a `setTimeout` promise |
| `run` returns | the value | a promise of the value |
| `nav` | runs the plan | returns a deferred value (below) |

### Throttling and retry as pure functions

`throttle` moves to `.cljc` as pure functions, and each runner does the
waiting:

- `(acquire state now-ms) → [wait-ms new-state]`, the token bucket;
- `(retry-in attempt status retry-after) → ms | nil`, the backoff policy
  (429 and 5xx; up to 3 retries; honour `Retry-After`).

There is one throttle per client, shared by Datasets and E-utilities: 3/s
without a key and 10/s with one, worked out from the key at request time.

### The client

```clojure
(ncbi/connect {:api-key  "…" | (fn [] …) | an atom   ; read on each request
               :tool     "data-explorer"
               :email    "…"
               :fetch    busy/fetch})                ; cljs only
```

- The key can change while the page is open, so it is read when each request
  is made.
- It is never logged, and never put into an entity, a request or an error.
- Entities keep a reference to the client in their metadata, as now.

### The deferred value (browser `nav`)

```clojure
(with-meta {:ncbi/deferred :ncbi.nav/assemblies
            :ncbi/describe "assemblies of taxon 9606"
            :ncbi/request  {…}}                          ; the first request, without the key
  {:async.nav/fetch         (fn [] promise-of-value)
   :data-explorer.nav/label "assemblies"
   :data-explorer.nav/crumb "assemblies of 9606"})
```

- `:async.nav/fetch` is deliberately not NCBI's or the Data Explorer's: the
  ENA and LAPIS clients will use it too.
- The promise resolves to an entity or a vector of entities, with `datafy`
  and `nav` metadata of their own, so Browse can keep following them.
- The metadata keys are `clojure.core.protocols/datafy` and `…/nav` on both
  platforms. The spike notes' guess of `cljs.core/-datafy` was wrong.

### Edges return a page, not everything

At present many edges use `fetch-all`. In the browser that would fetch every
page. From now on, edges return **one page**, with `:ncbi.nav/next-page`
when there are more. This applies on both platforms, so the graph behaves the
same everywhere. `fetch-all` (a lazy seq) remains as a JVM-only function.

### Edges are data

Every edge in `nav-edges` becomes a function of the entity that returns a
plan:

```clojure
[:ncbi/taxonomy :ncbi.nav/assemblies]
{:plan (fn [taxon] (ds/page-plan :genome-dataset-reports-by-taxon
                                 {:taxons [(tax-id taxon)]} :ncbi/assembly))}
```

The current `:nav-fn` closures, which call Martian and the throttle directly,
all go. The `nav-edges` table and `nav-keys-for` live in `.cljc`, and so does
the generic part of `datafy.clj`. Only the line where `nav` either runs the
plan or wraps it as a deferred value depends on the platform.

### Links between Entrez databases

At present `datafy` on a search result calls `elink-available`, which is a
request. Instead:

- A **static link table** in `.cljc` lists the links the Data Explorer wants
  most. Each one is a nav key on every record of that database:
  - biosample → sra
  - sra → bioproject
  - bioproject → assembly
  - assembly → biosample
  - taxonomy → assembly
  - gene → pubmed, nuccore
- **`:ncbi.elink/available`** becomes a deferred key of its own, for
  discovering the rest.
- elink results stay capped at 200 IDs per hop. The total goes in metadata.

### Datasets or E-utilities

- **Datasets** for genomes, genes, taxonomy and viruses.
- **E-utilities** for SRA, BioSample summaries, BioProject, PubMed and
  nuccore, and for links between databases.
- The bridge (`:ncbi.nav/datasets-entity`) continues to connect the two.

### Lookups by accession: entities and rows

- **`lookup`** takes accessions of any kind and returns entities. It:
  - groups the accessions by database (`database-of`);
  - batches 200 per request;
  - for sra, biosample, assembly and bioproject, runs esearch then esummary;
    nuccore takes accessions as IDs directly;
  - matches each answer back to the accession asked about, with or without
    its version;
  - returns them **in the order asked**, with the ones NCBI doesn't have
    marked `:ncbi/error :not-found`.
- **`ncbi-api-client.rows`** (`.cljc`) holds flat rows for tables, ported
  from the Data Explorer's `eutils.cljs`: `nuccore-row`, `sra-row`,
  `biosample-row`, `assembly-row`, `bioproject-row`, and the accession
  patterns. It works on keyword-keyed summaries, the same on both platforms.
- **Embedded XML** (the expxml and sampledata fields of SRA and BioSample
  summaries) is read with the page's small regex extractors, which run on
  both platforms. Full efetch XML is left for later.
- **`efetch`** gets text output (FASTA) to start with.

### Layout and dependencies

```
src/cljc/ncbi_api_client/   plan, routes, edges, datafy, throttle, eutils parsing, rows, accessions
src/clj/ncbi_api_client/    runner (hato), core facade, fetch-all, package (zip downloads)
src/cljs/ncbi_api_client/   runner (fetch + promises), core facade
```

- `:paths ["src/cljc" "src/clj" "src/cljs" "resources"]`.
- `:deps` holds only hato and cheshire. ClojureScript consumers get them on
  the classpath, but shadow-cljs never compiles them. If that causes a
  conflict (for example jackson), split the JVM part into a `:deps/root`
  subproject.
- Dataset packages (`package.clj`: `ZipFile`, `java.nio`) stay JVM-only. A
  browser version could return the bytes later; the Data Explorer has its own
  zip reader.
- For `:advanced` builds, JS interop gets `^js` hints.
- Keep the public API in a few namespaces, so the Data Explorer can expose
  them to SCI.

### A shared core for other services

The generic parts (plan, runners, deferred values, throttle, edge
interpreter) go in namespaces with no NCBI knowledge
(`ncbi-api-client.plan`, `.throttle`, `.nav`). Once a second client (ENA,
LAPIS) exists, they can move to a library of their own.

## Testing

- **Pure functions on both platforms, with no network:** request plans,
  routes, response parsing, rows, match-back, throttle and retry. These run
  under the `clj` test runner and the shadow-cljs `:node-test` build, which is
  a dev alias (`shadow-cljs` and `clojurescript` stay out of `:deps`).
- **The JVM route table is checked against the OpenAPI spec.**
- **Recorded answers:** the VCR fixtures under `test-resources/vcr/` were
  recorded through Martian. They become plain response-body fixtures
  (`<operation>/<case>.json`) replayed through a stub runner on both
  platforms. The Data Explorer's E-utilities fixtures can move here too.
- When request shapes change (GET or POST, parameters), the Data Explorer's
  Playwright fixtures need recording again.

## Steps

1. ~~Tidy the docs; write this.~~
2. The `.cljc` core: plan, routes (and the spec check), throttle, and the JVM
   runner. Replace Martian; the existing tests stay green.
3. Edges as plans; one page per edge; datafy without requests; the static
   link table.
4. The cljs runner, the deferred value, and `:node-test`.
5. `lookup` and `rows`, ported from the Data Explorer, with its fixtures.
   After this the page can delete its own `eutils.cljs`.
6. Links as navigation for the page (BioSample → SRA → BioProject →
   assemblies); esearch with `usehistory=y` and paged esummary.
7. Update the README and CLAUDE.md.

## Carried over from the June review

Most items in the review were fixed. These were still open:

- **M2**, the array-path hack: goes away with Martian (step 2).
- **M3**, response schema coercion: still not planned. Entities stay plain
  maps.
- **E4**, two `search` result shapes under the same name: settle it when the
  facade is rewritten (step 2 or 4).
- **G5**, the README's description of `fetch-all` and pagination is out of
  date: step 7.
- **G6**, only gene and assembly packages: unchanged, JVM-only.
