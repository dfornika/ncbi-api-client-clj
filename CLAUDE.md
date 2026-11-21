# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A Clojure and ClojureScript client for NCBI's Datasets API (v2) and E-utilities, built on `datafy`/`nav`. The core is `.cljc`; each platform has a small runner: the JVM's (hato, blocking) and the browser's (fetch, promises). The README describes the library for its users. `docs/design.md` records the design decisions and the steps that got here.

## Architecture

### Plans, requests and the runners

Nothing in the `.cljc` core makes a request.

- **`request.cljc`** describes requests as plain data: `{:api :op :method :url :query :form :json :as :accept}`, never holding the API key.
  - `req/datasets` builds one from the route table. It fills path parameters (lists joined with commas), and puts the rest in the query (lists repeat the name) or, for POST routes, the JSON body. It throws on unknown or missing parameters. Parameter names may use `-` or `_`.
  - `req/eutils` builds a POSTed form with `retmode=json`.
  - `request/outgoing` decides what goes on the wire, for both runners: the key, tool and email, headers, and the form body.
  - `req/http-error` builds the `ex-info` for an error answer: `:ncbi/error`, `:status`, `:ncbi/request`.
- **`plan.cljc`** pairs a request with what to do with its answer (`plan`, `then`). A plan's `then` can return another plan, for steps that take several requests.
- **The runners** are both named `ncbi-api-client.runner`: `runner.clj` sends with the client's `:http` and blocks; `runner.cljs` sends with `:fetch` and returns promises. Both:
  - wait their turn (`throttle`);
  - retry 429s and 5xx;
  - decode the body (`:json`, `:text`, or `:bytes`);
  - treat a 404 as nil for a request marked `:optional?`.
- **`core.cljc`** is the public API on both platforms. Each function builds a plan and runs it with the client's `:run`. `nav/run-all` runs several plans: in turn on the JVM, at once in the browser.

### The client

`client.cljc` (`create-client`, called by `core/connect`) makes one flat map for both APIs:
- `:api-key`: a function, read on each request, so the key is never printed. It's made from a string, a function or an atom; the default is `NCBI_API_KEY` on the JVM, and none in the browser.
- `:tool`, `:email`, `:base-urls`.
- `:http` (JVM; hato by default, a stub in tests) or `:fetch` (browser; like `js/fetch`).
- `:run`: the platform's `runner/run`.
- `:rate-limiter`: shared by both APIs.

`connect` adds `:nav-edges`. NCBI's limits are 3 requests a second without a key and 10 with one. `throttle.cljc` holds the policy as pure functions (`reserve`, a token bucket; `retry-in`, backoff), plus JVM `acquire!`/`with-retry`, which sleep.

### Navigation: entities, edges, pages, deferred values

`nav.cljc` is the generic part, with no NCBI knowledge.

- `tag-entity` puts `datafy`/`nav` on an entity. `datafy` adds a `:deferred` value for each nav key its type has in the client's `:nav-edges`, and never makes a request.
- An edge is `{:plan (fn [client entity] plan-or-nil)}`, keyed by `[entity-type nav-key]`.
- `nav/follow` is the only function that depends on the platform. On the JVM it runs the plan and returns the value; in the browser it returns a deferred value (`nav/deferred`). That's a map of `:ncbi/deferred`, `:ncbi/describe` and `:ncbi/request` (never the key), whose metadata has `:async.nav/fetch` (runs the plan) and `:async.nav/key` (stable identity).
- `nav/page` makes a page: a vector whose metadata has `:async.nav/total` and `:async.nav/next` (a deferred value, on both platforms). `datafy` on it shows `:ncbi.nav/next-page` in its metadata, and `nav` follows it.
- Edges return one page; `fetch-all` (JVM only) is for everything.

### Datasets

- **`datasets/routes.cljc`** is generated from `spec/datasets-openapi3.yaml` by `scripts/gen_routes.clj` (`clojure -X:gen-routes`). Don't edit it by hand. `runner_test.clj` checks the checked-in table against the spec. Operation keywords are kebab-case operation IDs (`:bio-sample-dataset-report`); they match the names Martian used before it was dropped.
- **`datasets.cljc`**:
  - `page-plan` and `one-plan`;
  - `report-extractors`: where each type's report sits in `:reports` (taxonomy under `:taxonomy`, gene under `:gene`, gene-product under `:product`, annotation under `:annotation`, the others as they are);
  - `labels`: an entity's ID, for descriptions;
  - `nav-edges`;
  - JVM-only `call`, `fetch`, `fetch-one` and `fetch-all`.
- **`package.clj`** (JVM) has dataset package downloads (zip) and their edges, `package/nav-edges`.

### E-utilities and the bridge

- **`eutils.cljc`** has plans for einfo, esearch (`:usehistory`), esummary, `esummary-history` (a page from the history server, or `:ncbi.eutils/history-gone`), elink, `elink-each` (each ID's links separately), elink-available and efetch. It also has `search-summaries` (plain maps), and `read-*` functions for the answers.
- **`bridge.cljc`** makes E-utilities records into entities (`tag-result`). `datafy` on one makes no request, and shows:
  - `:ncbi.nav/datasets-entity`, for gene, taxonomy, assembly and BioSample records (via `db->datasets`; assembly and BioSample records give their accession);
  - `:ncbi.elink/<linkname>` keys from the static `links` table;
  - `:ncbi.elink/available`, which asks for every link the record has.
- **Following links:**
  - Into gene or taxonomy, a link gives one page of Datasets entities (200 IDs).
  - Otherwise it gives a page of 200 esummary records of the other database, with `:async.nav/next` over the remaining IDs (`summaries-page-plan`).
- **`search-plan`** keeps the search on the history server. Each later page is one esummary; if the history has expired, the plan searches again from there.
- **`links-plan`** (`core/links`) gives the links of many records at once, in order.
- **`bridge/nav-edges`** adds edges from Datasets entities into Entrez: a BioSample's `:ncbi.nav/sra`.

### Lookups and rows

- **`accessions.cljc`** has the INSDC and RefSeq patterns and `database-of`.
- **`lookup.cljc`** (`core/lookup`):
  - groups accessions by database, 200 to a request; an SRA study or sample (SRP, SRS), which has many experiments, gets a request of its own;
  - searches then summarizes; nuccore takes accessions as IDs directly;
  - matches each record back with or without its version;
  - returns them in the order asked. Records carry `:ncbi/asked` in their metadata; a missing one is `{:ncbi/asked … :ncbi/error :not-found :ncbi.eutils/db …}`.
- **`rows.cljc`** reads records as flat rows (`nuccore-row`, `sra-row`, `biosample-row`, `assembly-row`, `bioproject-row`, with regex extractors for embedded XML). `rows/table` makes rows from what `lookup` gives.

### Entity types

| Entity | Direct fn | Nav keys (`:ncbi.nav/…`) |
|--------|-----------|-------------------|
| `:ncbi/taxonomy` | `taxonomy` | assemblies, genes, viruses, children, lineage, image, links |
| `:ncbi/assembly` | `assembly` | organism, genes, biosample, sequences, annotations, links, package (JVM) |
| `:ncbi/gene` | `gene` | organism, orthologs, assemblies, products, links, package (JVM) |
| `:ncbi/biosample` | `biosample` | organism, assemblies, sra |
| `:ncbi/sequence` | `sequences` | assembly |
| `:ncbi/gene-product` | `gene-products` | gene |
| `:ncbi/annotation` | `annotations` | assembly, gene |
| `:ncbi/virus` | `virus` | taxonomy, host, annotations |
| `:ncbi/virus-annotation` | `virus-annotations` | virus |
| `:ncbi.eutils/result` | `search`, `lookup` | datasets-entity, `:ncbi.elink/*` |

## Project Structure

```
src/cljc/ncbi_api_client/   — shared by the JVM and the browser
  plan.cljc       — Plans: a request description and what to do with its answer
  request.cljc    — Describing requests; what goes on the wire; URL encoding; errors
  throttle.cljc   — Token bucket and retry policy (pure), JVM sleeping versions
  nav.cljc        — Generic datafy/nav: entities, pages, deferred values, run, run-all
  datasets.cljc   — Datasets: page-plan, report extraction, nav-edges; JVM fetch/fetch-all
  eutils.cljc     — E-utilities plans and answer parsing
  bridge.cljc     — E-utilities records as entities: search, the link table, link pages, links-plan
  accessions.cljc — Accession patterns, and which Entrez database each is in
  lookup.cljc     — Lookups by accession: batching, matching back, in order
  rows.cljc       — Records as flat rows for tables
  client.cljc     — Client creation
  core.cljc       — Public API
  datasets/routes.cljc — Datasets route table (generated; don't edit)
src/clj/ncbi_api_client/    — JVM
  runner.clj      — Runs plans: hato, throttle, retry, JSON decoding (blocking)
  package.clj     — Dataset package (zip) downloads, and their nav edges
src/cljs/ncbi_api_client/   — browser (and Node)
  runner.cljs     — Runs plans: fetch, throttle, retry, JSON decoding (promises)
src/dev/
  user.clj        — Dev namespace, creates client, points to demo
  demo.clj        — Demo functions organized by entity type
src/test/ncbi_api_client/
  test_support.clj  — stub-http / stub-client / fixture helpers (no network)
  fixtures.cljc     — `inline` (a fixture, read when compiled) and parse-json
  request_test.cljc — Requests, plans, throttle policy (portable)
  nav_test.cljc     — Edges, pages, deferred values, the bridge (portable)
  lookup_test.cljc  — Lookups and rows, on recorded answers (portable)
  links_test.cljc   — History paging, link pages, batched links (portable)
  browser_test.cljs — The browser runner, with a stub fetch (Node)
  runner_test.clj   — Route table vs spec, API key, errors, retry
  core_test.clj     — Datasets API tests (fixtures and stubs)
  eutils_test.clj   — E-utilities and bridge tests (stubs)
  throttle_test.clj — JVM rate limiter and retry
test-resources/fixtures/datasets/ — recorded Datasets answers (JSON bodies)
test-resources/fixtures/eutils/   — recorded E-utilities answers
spec/datasets-openapi3.yaml — NCBI Datasets API OpenAPI 3 spec (local copy)
scripts/
  gen_routes.clj     — Generates datasets/routes.cljc from the spec
  setup-dev-env.sh   — Idempotent bootstrap: installs Java, Clojure CLI,
                        Babashka, bbin, clojure-mcp-light tools, deps
shadow-cljs.edn      — The ClojureScript test build (:node-test)
.github/workflows/test.yml — CI: the JVM tests and the ClojureScript tests
docs/design.md       — Design decisions, and the steps that got here
LICENSE              — MIT
```

## Development Commands

**Bootstrap environment** (installs all tooling):
```bash
bash scripts/setup-dev-env.sh
```

**Start nREPL server** (port 7888, also written to `.nrepl-port`):
```bash
clojure -M:dev:nrepl
```

**Connect to running REPL**:
```bash
clj-nrepl-eval --connected-ports
clj-nrepl-eval -p 7888 "(+ 1 2)"
```

**Demo functions** (`user` creates `client`):
```clojure
(require '[demo :reload true])
(demo/taxonomy-summary client "9606")
(demo/gene-orthologs client 672 :limit 10)
(demo/search-and-bridge client "gene" "BRCA1 human")
(demo/discover-links client "gene" "TP53 human")
(demo/follow-link client "gene" "TP53 human" :ncbi.elink/gene_pubmed)
(demo/list-operations)                         ; All Datasets operations
(demo/describe-operation :virus-genome-table)  ; One operation's route
(demo/preview-request client :gene-orthologs-by-id {:gene_id 672})  ; the key shows as "…"
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
- **shadow-cljs**: the ClojureScript test build (`:cljs` and `:cljs-test` aliases only)

A ClojureScript consumer excludes hato and cheshire: `{:git/sha "…" :exclusions [hato/hato cheshire/cheshire]}`. The browser code has no JS dependencies.

## Working with the Code

**Keep the `.cljc` core free of I/O.** Build request descriptions and plans there, and do the sending in the runners.
- Things that apply to every request belong in `request/outgoing` (headers, the key) or in the runners (throttling, retry, decoding), not in individual calls.
- Keep the two runners doing the same things.
- The API key must never be put into a request description, an entity, an error, or anything printed.

**To add a new Datasets entity type:**
- add entries to `report-extractors` and `labels`, and its edges to `nav-edges`, in `datasets.cljc`. An edge is a function of the client and the entity that returns a plan, usually from `page-edge` or `one-edge`;
- add a public function to `core.cljc`;
- add demo functions to `demo.clj`.

`nav.cljc` doesn't need changing: it reads `:nav-edges` from the client. Edges return one page; never call `fetch-all` from an edge.

**To add a new eutils program:** add a plan to `eutils.cljc` (`req/eutils` plus a `read-*` function), and a function taking a client to `core.cljc`.
- If its results should bridge into Datasets, extend `db->datasets` in `bridge.cljc`.
- To show a link between Entrez databases on every record without asking NCBI, add it to `links` there.
- For an edge from a Datasets entity into Entrez, add it to `bridge/nav-edges`.

**Testing:**
- Tests make no requests: stub `:http` (`test_support.clj`), stub `:fetch` (`browser_test.cljs`), or a fake `:run` that answers plans directly (the portable tests).
- Tests in `.cljc` files must stay runnable under ClojureScript; CI runs them on both platforms. A portable test that runs `core` functions waits on a promise in the browser (see `settle` in `lookup_test.cljc`).
- In `.cljs` tests inside `cljs.test/async`, don't call `.then` on an expression like `((:run c) c x)`: the compiler awaits it, which unwraps the promise. Call a named function instead.

**REPL:** always use `:reload true` when requiring namespaces after changes, so the REPL picks up modifications.

**Browser:** NCBI's APIs allow requests from web pages (CORS), so the browser runner calls them directly.
