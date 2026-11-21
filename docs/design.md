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
data for all 107 of the spec's operations (about 25 KB of source), so `fetch`
still works with any operation, as it did with Martian:

```clojure
{:taxonomy-data-report
 {:method :get, :path "/taxonomy/taxon/{taxons}/dataset_report",   ; lists joined with ","
  :as :json, :accept "application/json",
  :query #{"page_size" "page_token" "returned_content" ...}}
 ...}
```

- The table is generated from `spec/datasets-openapi3.yaml` by
  `scripts/gen_routes.clj` (`clojure -X:gen-routes`), and checked in.
- A JVM test regenerates it and checks it against the checked-in file.
- Operation keywords are the names Martian gave them, and were checked
  against Martian's when the table was first generated.
- Martian is gone entirely. `demo/list-operations`, `describe-operation` and
  `preview-request` read the route table instead of `martian/explore`.
- The Datasets API's key goes in the `api-key` header, as the spec says. The
  Martian version sent `api-token`, so Datasets probably never saw the key.

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

**E-utilities requests are POSTs** with a form body (`:form {…}`): an
esearch `term` of 200 accessions joined with OR makes a URL too long for a
GET. Datasets requests are GETs, or POSTs with a JSON body where the route
says so.

```clojure
{:api :eutils :method :post :url "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi"
 :form {:db "biosample" :term "SAMN1 OR SAMN2" :retmode "json" :retmax 10}}
```

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
without a key and 10/s with one, worked out from the key at request time. It
covers only the requests the library makes. Other calls to NCBI from the
same program (such as the Data Explorer's generic OpenAPI forms) don't share
it.

### The client

```clojure
(ncbi/connect {:api-key  "…" | (fn [] …) | an atom   ; read on each request
               :tool     "data-explorer"
               :email    "…"                         ; unset by default
               :fetch    #(busy/fetch %1 %2 "NCBI")}) ; cljs only: (fn [url opts] promise)
```

- `:email` is sent only when it's given. The browser never sends an address
  unless the user chooses to.
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
   :async.nav/key           [:ncbi.nav/assemblies {…request…}]
   :data-explorer.nav/label "assemblies"
   :data-explorer.nav/crumb "assemblies of 9606"})
```

- `:async.nav/key` is a stable identity: plain data, equal across renders,
  and without the API key. `nav` makes a new deferred map, with a new
  function, every time it's called. A consumer uses the key to remember what
  it has already fetched.

- `:async.nav/fetch` is deliberately not NCBI's or the Data Explorer's: the
  ENA and LAPIS clients will use it too.
- The promise resolves to an entity or a vector of entities, with `datafy`
  and `nav` metadata of their own, so Browse can keep following them.
- The metadata keys are `clojure.core.protocols/datafy` and `…/nav` on both
  platforms. The spike notes' guess of `cljs.core/-datafy` was wrong.

### Edges return a page, not everything

At present many edges use `fetch-all`. In the browser that would fetch every
page. From now on, edges return **one page**: a bare vector of entities, so
Browse shows it as a table. This applies on both platforms, so the graph
behaves the same everywhere. `fetch-all` (a lazy seq) remains as a JVM-only
function.

The vector's metadata says how to get more, with neutral keys that the ENA
and LAPIS clients can use too:

- **`:async.nav/next`**: a deferred value for the next page, when there is
  one. In cljs its fetch returns a promise; on the JVM it returns the page.
- **`:async.nav/total`**: the total count, when the API gives one.

As now, `datafy` on the vector also shows `:ncbi.nav/next-page :deferred`,
and `nav` follows it, for REPL use.

### Edges are data

Every edge in `nav-edges` becomes a function of the entity that returns a
plan:

```clojure
[:ncbi/taxonomy :ncbi.nav/assemblies]
{:plan (fn [client taxon] (ds/page-plan client :genome-dataset-reports-by-taxon
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
src/cljc/ncbi_api_client/   plan, request, routes, nav, datasets, bridge, throttle, eutils,
                            client, core (the facade); rows and accessions (step 5)
src/clj/ncbi_api_client/    runner (hato), package (zip downloads)
src/cljs/ncbi_api_client/   runner (fetch + promises)
```

- `:paths ["src/cljc" "src/clj" "src/cljs"]`. The
  spec lives in `spec/`, not on the classpath.
- `:deps` holds only hato and cheshire. A ClojureScript consumer excludes
  them in its coordinate, which also keeps jackson away from shadow-cljs's
  own dependencies:
  `{:git/sha "…" :exclusions [hato/hato cheshire/cheshire]}`. Splitting the
  JVM part into a `:deps/root` subproject is the fallback if that isn't
  enough.
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
- **Recorded answers:** plain response bodies under
  `test-resources/fixtures/` (converted from Martian's VCR recordings), served
  by a stub `:http` (`test-support/stub-client`). A test checks that requests
  still go to the URLs the recordings were made from. The Data Explorer's
  E-utilities fixtures can move here too.
- When request shapes change (GET or POST, parameters), the Data Explorer's
  Playwright fixtures need recording again.

## Steps

1. ~~Tidy the docs; write this.~~
2. ~~The `.cljc` core: plan, routes (and the spec check), throttle, and the JVM
   runner. Replace Martian; the existing tests stay green.~~ `plan`,
   `request`, `throttle`, `eutils` and `datasets.routes` are `.cljc`, and their
   tests also pass under ClojureScript (a throwaway `cljs.main` build on Node).
   `datasets`, `datafy` and `bridge` are still `.clj`, and become `.cljc` in
   step 3.
3. ~~Edges as plans; one page per edge; datafy without requests; the static
   link table.~~ `nav`, `datasets` and `bridge` are `.cljc`, and their
   portable tests (`nav_test.cljc`) pass on Node too. What changed from the
   plan above:
   - An edge's function takes the client as well as the entity, because the
     entities it returns are tagged with the client.
   - The client has `:run`, the function that runs plans (`runner/run` on
     the JVM). The `.cljc` code runs plans through it, so it doesn't depend
     on a runner.
   - The deferred value exists on both platforms now, because
     `:async.nav/next` is one. On the JVM `nav` still returns values; only
     `nav/follow` depends on the platform. The deferred value has
     `:ncbi/deferred`, `:ncbi/describe` ("assemblies of taxonomy 9606") and
     `:ncbi/request`. The Data Explorer's label and crumb keys are left to
     the page.
   - A request marked `:optional?` answers nil for a 404: the image edge,
     since most taxa have no image.
   - Search results page too: `:async.nav/next` by `retstart`, until step 6
     adds `usehistory`.
   - Records reached by an elink are tagged as records of their own
     database, so they can be followed further (BioSample → SRA →
     BioProject → assembly works).
   - `:ncbi.elink/available` gives a map of `:ncbi.elink/<linkname>` →
     `{:dbto :menutag}`; nav on its keys follows the links.
   - The link names, checked live: `biosample_sra`, `sra_bioproject`,
     `bioproject_assembly`, `assembly_biosample`, `taxonomy_assembly`,
     `gene_pubmed`, `gene_nuccore`.
4. ~~The cljs runner, the deferred value, and `:node-test`.~~ Done, with
   these details:
   - `core` and `client` are `.cljc`. Each `core` function runs a plan
     with the client's `:run`: a value on the JVM, a promise in the
     browser. Package downloads and `fetch-all` stay JVM-only.
   - Both runners are `ncbi-api-client.runner` (`runner.clj`,
     `runner.cljs`), so `client.cljc` requires the platform's own. What
     goes on the wire (the key, tool, email, headers, the form body) is
     `request/outgoing`, shared, so the two can't drift.
   - In the browser the default tool is `ncbi-api-client-cljs`, and there's
     no key unless it's given.
   - The tests: `clojure -M:cljs-test && node target/test/node-tests.js`.
     shadow-cljs's `:autorun` exits 0 even when a test fails, so CI runs
     `node` itself. `browser_test.cljs` tests the runner with a stub
     `:fetch` that answers with real `Response` objects. An `:advanced`
     build passes too, with no externs inference warnings.
   - Checked live from Node with its own `fetch`: lookups, edges, the next
     page, the image 404, search, a link and `:ncbi.elink/available`.
   - Not yet done: trying it in the Data Explorer itself, where Browse
     turns `:async.nav/fetch` into a lead.
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
- ~~**E4**, two `search` result shapes under the same name~~: settled in
  step 4. `core/search` gives navigable records on both platforms;
  `eutils/search` became `eutils/search-summaries`.
- **G5**, the README's description of `fetch-all` and pagination is out of
  date: step 7.
- **G6**, only gene and assembly packages: unchanged, JVM-only.
