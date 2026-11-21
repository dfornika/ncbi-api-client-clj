# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is a Clojure API client library for the NCBI Datasets API (v2) and E-utilities, built on Clojure's `datafy`/`nav` protocols for navigable data exploration. It has a shared `.cljc` core with a JVM runner (hato, blocking) and a browser runner (fetch, promises): see `docs/design.md` for the design and its steps. The Data Explorer (a ClojureScript page) is its browser consumer. Datasets requests are built from a route table generated from the OpenAPI 3 spec (`spec/datasets-openapi3.yaml`). The E-utilities client provides keyword search across 38 Entrez databases and cross-database linking. Together they expose a navigable graph of interconnected biological entities that can be lazily traversed via standard Clojure navigation — starting from keyword search all the way to structured entity data.

## Architecture

### Unified Client

`ncbi-api-client.client/create-client` produces one flat client map for both APIs: `:api-key` (a function, read on each request, so the key is never printed), `:tool`, `:email`, `:base-urls`, `:http` (JVM: the function that sends requests; hato by default, a stub in tests) or `:fetch` (browser: a function like `js/fetch`, which the Data Explorer passes to show that NCBI is busy), `:run` (how plans are run: the platform's `runner/run`) and `:rate-limiter` (shared by both APIs). `core/connect` adds `:nav-edges`.

### Plans, Requests and the Runner

Nothing in the `.cljc` core makes a request. `request.cljc` *describes* requests as plain data (`{:api :op :method :url :query :form :json :as :accept}`, never holding the API key). `plan.cljc` pairs a request with what to do with its answer (`plan`, `then`); a plan's `then` can return another plan, for steps that take several requests. `request/outgoing` decides what goes on the wire (the key, tool and email, headers, the form body), the same on both platforms. Each platform has a runner, both named `ncbi-api-client.runner`: `runner.clj` (JVM) sends with `:http` and blocks; `runner.cljs` sends with `:fetch` and returns promises. Both wait their turn (`throttle`), retry 429/5xx, decode the body, and fail with an `ex-info` (with `:ncbi/error`, `:status`, `:ncbi/request`) on errors. `core.cljc` is the public API on both platforms: each function builds a plan and runs it with the client's `:run`, so it returns a value on the JVM and a promise in the browser.

### Datasets Route Table

`datasets/routes.cljc` is generated from the spec by `scripts/gen_routes.clj` (`clojure -X:gen-routes`); don't edit it by hand. Operation keywords are the names Martian used (`:bio-sample-dataset-report`). `runner_test.clj` checks the checked-in table against the spec. `req/datasets` fills path parameters (lists joined with commas), puts the rest in the query (lists repeat the name) or, for POST routes, the JSON body, and throws on unknown or missing parameters. Parameter names may use `-` or `_`.

### E-utilities Client

`eutils.cljc` returns plans for four eutils programs: `einfo` (database metadata), `esearch` (keyword search), `esummary` (document summaries), and `elink` (cross-database links), plus `search-summaries` (esearch then esummary, as plain maps; `core/search` gives navigable records). Requests are POSTed forms with `retmode=json`. The `read-*` functions parse answers. `core.cljc` has the same functions taking a client, which run the plans. NCBI's rate guidelines: 3 req/sec without API key, 10 req/sec with one. `throttle.cljc` holds the policy as pure functions (`reserve`, a token bucket; `retry-in`, backoff), plus JVM `acquire!`/`with-retry` that sleep.

### Eutils↔Datasets Bridge

The bridge (`bridge.cljc`) makes eutils records navigable via `datafy`/`nav`. `search-plan` returns a page of records (`tag-result`); `datafy` on one makes no request and shows:
- `:ncbi.nav/datasets-entity` — navigates into the Datasets entity graph (for gene, taxonomy, assembly, biosample databases)
- `:ncbi.elink/<linkname>` keys from the static `links` table (biosample→sra, sra→bioproject, bioproject→assembly, assembly→biosample, taxonomy→assembly, gene→pubmed and nuccore)
- `:ncbi.elink/available` — asks NCBI for every link the record has: a map of `:ncbi.elink/<linkname>` keys, which nav follows

Following a link gives Datasets entities when the other database is gene or taxonomy, otherwise esummary records tagged as records of that database, so they can be followed further. The `db->datasets` mapping translates eutils database names to Datasets operations. For databases with numeric UIDs that don't match Datasets identifiers (assembly, biosample), the accession is pulled from the esummary data instead. elink results are capped at 200 IDs (`max-link-ids`); the total is in metadata (`:ncbi.elink/total-count`).

### datafy/nav Navigation Graph

The core abstraction is a navigable entity graph built on `clojure.core.protocols/datafy` and `clojure.core.protocols/nav`. Every entity returned by the library carries metadata implementing these protocols:

- **`datafy`** on an entity exposes `:ncbi.nav/*` keys with `:deferred` values, representing navigable relationships. It never makes a request.
- **`nav`** on a deferred key follows the edge's plan: on the JVM it runs it and returns the value; in the browser (step 4) it returns a *deferred value* (`nav/deferred`): a map of `:ncbi/deferred`, `:ncbi/describe` and `:ncbi/request` (never the key), whose metadata has `:async.nav/fetch` (runs the plan) and `:async.nav/key` (stable identity).
- **Edges return one page**: a vector whose metadata has `:async.nav/total` and `:async.nav/next` (a deferred value for the next page, on both platforms).
- **`datafy`** on a page exposes `:ncbi.nav/next-page` in its metadata when there are more pages; **`nav`** on it follows `:async.nav/next`.

The generic part is `nav.cljc` (`tag-entity`, `page`, `deferred`, `follow`); `follow` is the only line that depends on the platform. Edges are data: `datasets/nav-edges` maps `[entity-type nav-key]` to `{:plan (fn [client entity] plan-or-nil)}`, and `package/nav-edges` adds the JVM-only package downloads.

This allows interactive exploration in a REPL: `(-> entity datafy (nav :ncbi.nav/assemblies :deferred))`.

### Entity Types

Nine entity types with full datafy/nav support:

| Entity | Direct fn | Nav keys (outbound) |
|--------|-----------|-------------------|
| `:ncbi/taxonomy` | `ncbi/taxonomy` | assemblies, genes, viruses, children, lineage, image, links |
| `:ncbi/assembly` | `ncbi/assembly` | organism, genes, biosample, sequences, annotations, links |
| `:ncbi/gene` | `ncbi/gene` | organism, orthologs, assemblies, products, links |
| `:ncbi/biosample` | `ncbi/biosample` | organism, assemblies |
| `:ncbi/sequence` | `ncbi/sequences` | assembly |
| `:ncbi/gene-product` | `ncbi/gene-products` | gene |
| `:ncbi/annotation` | `ncbi/annotations` | assembly, gene |
| `:ncbi/virus` | `ncbi/virus` | taxonomy, host, annotations |
| `:ncbi/virus-annotation` | `ncbi/virus-annotations` | virus |

External links (`:ncbi.nav/links`) on taxonomy, assembly, and gene entities serve as exit ramps to Wikipedia, FTP, PubMed, GDV, etc.

### Pagination

API responses use forward-only `next_page_token` pagination:

- `page-plan` (`.cljc`) is a plan for one page; `fetch` runs it, returning a tagged vector with `:async.nav/total`, `:async.nav/next`, `:ncbi/total-count` and `:ncbi/next-page-token` in its metadata
- `fetch-all` (JVM only) returns a lazy sequence that auto-paginates through all results
- Result vectors support `datafy`/`nav` for manual page-by-page traversal via `:ncbi.nav/next-page`
- Search results page the same way, by `retstart`

### Report Extraction

Different API endpoints nest their data differently. The `report-extractors` map in `datasets.cljc` handles unwrapping: taxonomy reports are under `:taxonomy`, gene under `:gene`, gene-product under `:product`, annotation under `:annotation`, and assembly/biosample/sequence are used as-is.

## Project Structure

```
src/cljc/ncbi_api_client/   — shared by the JVM and the browser
  plan.cljc     — Plans: a request description and what to do with its answer
  request.cljc  — Describing Datasets and eutils requests; URL encoding; errors
  throttle.cljc — Token bucket and retry policy (pure), JVM sleeping versions
  eutils.cljc   — E-utilities plans and answer parsing
  nav.cljc      — Generic datafy/nav: entity and page tagging, deferred values
  datasets.cljc — Datasets: page-plan, report extraction, nav-edges; JVM fetch/fetch-all
  bridge.cljc   — Eutils records as entities: search-plan, the link table, links into Datasets
  client.cljc   — Client creation
  core.cljc     — Public API: connect, taxonomy, assembly, gene, search, einfo, etc.
  datasets/routes.cljc — Datasets route table (generated; don't edit)
src/clj/ncbi_api_client/    — JVM
  runner.clj    — Runs plans: hato, throttle, retry, JSON decoding (blocking)
  package.clj   — Dataset package (zip) downloads, and their nav edges
src/cljs/ncbi_api_client/   — browser (and Node)
  runner.cljs   — Runs plans: fetch, throttle, retry, JSON decoding (promises)
src/dev/
  user.clj      — Dev namespace, creates client, points to demo
  demo.clj      — Extensive demo functions organized by entity type
src/test/ncbi_api_client/
  test_support.clj  — stub-http / stub-client / fixture helpers (no network)
  request_test.cljc — Requests, plans, throttle policy (portable)
  nav_test.cljc     — Edges, pages, deferred values, the bridge (portable)
  browser_test.cljs — The browser runner, with a stub fetch (Node)
  runner_test.clj   — Route table vs spec, API key, errors, retry
  core_test.clj     — Datasets API tests (fixtures and stubs)
  eutils_test.clj   — E-utilities and bridge tests (stubs)
  throttle_test.clj — JVM rate limiter and retry
test-resources/fixtures/datasets/ — recorded Datasets answers (JSON bodies)
spec/datasets-openapi3.yaml — NCBI Datasets API OpenAPI 3 spec (local copy)
scripts/
  gen_routes.clj     — Generates datasets/routes.cljc from the spec
  setup-dev-env.sh   — Idempotent bootstrap: installs Java, Clojure CLI,
                        Babashka, bbin, clojure-mcp-light tools, deps
shadow-cljs.edn      — The ClojureScript test build (:node-test)
.github/workflows/test.yml — CI: the JVM tests and the ClojureScript tests
docs/design.md       — The cljc/ClojureScript design and its steps
```

## Development Commands

**Bootstrap environment** (installs all tooling):
```bash
bash scripts/setup-dev-env.sh
```

**Start nREPL server**:
```bash
clojure -M:dev:nrepl
```
The nREPL server starts on port 7888 (configured in `deps.edn`). Port is also written to `.nrepl-port`.

**Connect to running REPL**:
```bash
clj-nrepl-eval --connected-ports
clj-nrepl-eval -p 7888 "(+ 1 2)"
```

**Load dev environment and run demo functions**:
```clojure
(require '[demo :reload true])
(demo/taxonomy-summary client "9606")
(demo/gene-orthologs client 672 :limit 10)
(demo/supported-operations)
```

**Search Entrez databases and bridge to Datasets**:
```clojure
(demo/search-entrez client "gene" "BRCA1 human")
(demo/search-and-bridge client "gene" "BRCA1 human")
(demo/discover-links client "gene" "TP53 human")
(demo/follow-link client "gene" "TP53 human" :ncbi.elink/gene_pubmed)
```

**Explore available API operations**:
```clojure
(demo/list-operations)                         ; All Datasets operations
(demo/describe-operation :virus-genome-table)  ; One operation's route
(demo/preview-request client :gene-orthologs-by-id {:gene_id 672})
```

**Run the tests** (JVM):
```bash
clojure -M:test
```

**Run the ClojureScript tests** (the `.cljc` and `.cljs` tests, on Node):
```bash
clojure -M:cljs-test && node target/test/node-tests.js
```
`node` exits 1 if a test fails; shadow-cljs's `:autorun` wouldn't, so it isn't used.

**Regenerate the Datasets route table** (after updating the spec):
```bash
clojure -X:gen-routes
```

## Key Dependencies

- **hato**: HTTP client (the JVM runner's default `:http`)
- **cheshire**: JSON on the JVM
- **clj-yaml**: reading the spec, for `gen-routes` and its test only (`:dev`, `:test`)
- **nrepl**: Development REPL server (`:nrepl` alias)
- **shadow-cljs**: the ClojureScript test build (`:cljs` and `:cljs-test` aliases only). A ClojureScript consumer excludes hato and cheshire: `{:git/sha "…" :exclusions [hato/hato cheshire/cheshire]}`

## Configuration

On the JVM, the API key is read from the `NCBI_API_KEY` environment variable; in the browser there's none unless it's given. If unset, requests are made without authentication (rate-limited). The `:api-key` option to `connect` can also be a function or an atom. The runner adds it at send time: for the Datasets API as the `api-key` header, for E-utilities as the `api_key` form parameter along with `tool` and `email` (email only if given) per NCBI's usage policy. Request descriptions never hold the key.

## Working with the Code

Keep the `.cljc` core free of I/O: build request descriptions and plans there, and do the sending in the runner. Things that apply to every request belong in `request/outgoing` (headers, the key) or the runners (throttling, retry, decoding), not in individual calls. Keep the two runners doing the same things.

To add a new Datasets entity type: add entries to `report-extractors` and `labels` and its edges to the `nav-edges` table in `datasets.cljc` (an edge is a function of the client and the entity that returns a plan, usually from `page-edge` or `one-edge`), add a public function to `core.cljc`, and add demo functions to `demo.clj`. The generic `nav.cljc` does not need modification — it reads nav-edges from the client map at runtime. Edges return one page; never call `fetch-all` from an edge.

To add a new eutils program: add a plan to `eutils.cljc` following the existing pattern (`req/eutils` plus a `read-*` function), and a function taking a client to `core.cljc`. If the new program's results should bridge into Datasets, extend `db->datasets` in `bridge.cljc`. To show a link between Entrez databases on every record without asking NCBI, add it to `links` there.

Always use `:reload true` when requiring namespaces after changes to ensure the REPL picks up modifications.

The NCBI APIs support CORS, so the browser runner can call them directly. Tests in `.cljc` files must stay runnable under ClojureScript (CI runs them on both platforms). In `.cljs` tests inside `cljs.test/async`, don't call `.then` on an expression like `((:run c) c x)`: the compiler awaits it, which unwraps the promise. Call a named function instead.
