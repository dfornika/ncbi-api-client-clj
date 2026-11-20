# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is a Clojure API client library for the NCBI Datasets API (v2) and E-utilities, built on Clojure's `datafy`/`nav` protocols for navigable data exploration. It is being reworked into a shared `.cljc` core with JVM and browser runners: see `docs/design.md` for the plan and its steps. Datasets requests are built from a route table generated from the OpenAPI 3 spec (`spec/datasets-openapi3.yaml`). The E-utilities client provides keyword search across 38 Entrez databases and cross-database linking. Together they expose a navigable graph of interconnected biological entities that can be lazily traversed via standard Clojure navigation — starting from keyword search all the way to structured entity data.

## Architecture

### Unified Client

`ncbi-api-client.client/create-client` produces one flat client map for both APIs: `:api-key` (a function, read on each request, so the key is never printed), `:tool`, `:email`, `:base-urls`, `:http` (the function that sends requests; hato by default, a stub in tests) and `:rate-limiter` (shared by both APIs).

### Plans, Requests and the Runner

Nothing in the `.cljc` core makes a request. `request.cljc` *describes* requests as plain data (`{:api :op :method :url :query :form :json :as :accept}`, never holding the API key). `plan.cljc` pairs a request with what to do with its answer (`plan`, `then`); a plan's `then` can return another plan, for steps that take several requests. `runner.clj` (JVM) runs plans: it adds the key, tool and email, waits its turn (`throttle`), sends with `:http`, retries 429/5xx, decodes the body and throws `ex-info` (with `:ncbi/error`, `:status`, `:ncbi/request`) on errors.

### Datasets Route Table

`datasets/routes.cljc` is generated from the spec by `scripts/gen_routes.clj` (`clojure -X:gen-routes`); don't edit it by hand. Operation keywords are the names Martian used (`:bio-sample-dataset-report`). `runner_test.clj` checks the checked-in table against the spec. `req/datasets` fills path parameters (lists joined with commas), puts the rest in the query (lists repeat the name) or, for POST routes, the JSON body, and throws on unknown or missing parameters. Parameter names may use `-` or `_`.

### E-utilities Client

`eutils.cljc` returns plans for four eutils programs: `einfo` (database metadata), `esearch` (keyword search), `esummary` (document summaries), and `elink` (cross-database links), plus `search` (esearch then esummary). Requests are POSTed forms with `retmode=json`. The `read-*` functions parse answers. `core.clj` has the same functions taking a client, which run the plans. NCBI's rate guidelines: 3 req/sec without API key, 10 req/sec with one. `throttle.cljc` holds the policy as pure functions (`reserve`, a token bucket; `retry-in`, backoff), plus JVM `acquire!`/`with-retry` that sleep.

### Eutils↔Datasets Bridge

The bridge (`bridge.clj`) makes eutils search results navigable via `datafy`/`nav`. Each search result carries metadata with:
- `:ncbi.nav/datasets-entity` — navigates into the Datasets entity graph (for gene, taxonomy, assembly, biosample databases)
- `:ncbi.elink/*` keys — follows cross-database links (e.g., gene→pubmed, gene→nuccore)

The `db->datasets` mapping translates eutils database names to Datasets operations. For databases with numeric UIDs that don't match Datasets identifiers (assembly, biosample), the accession is pulled from the esummary data instead. elink results are capped at 200 IDs to avoid connection issues with large result sets; total count is available in metadata.

### datafy/nav Navigation Graph

The core abstraction is a navigable entity graph built on `clojure.core.protocols/datafy` and `clojure.core.protocols/nav`. Every entity returned by the library carries metadata implementing these protocols:

- **`datafy`** on an entity exposes `:ncbi.nav/*` keys with `:deferred` values, representing navigable relationships
- **`nav`** on a deferred key triggers a lazy API call to fetch the related entity
- **`datafy`** on a result vector exposes `:ncbi.nav/next-page` when more pages are available
- **`nav`** on `:ncbi.nav/next-page` fetches the next page of results

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

- `fetch` returns one page as a tagged vector with pagination metadata (`:ncbi/total-count`, `:ncbi/next-page-token`)
- `fetch-all` returns a lazy sequence that auto-paginates through all results
- Result vectors support `datafy`/`nav` for manual page-by-page traversal via `:ncbi.nav/next-page`

### Report Extraction

Different API endpoints nest their data differently. The `report-extractors` map in `datasets.clj` handles unwrapping: taxonomy reports are under `:taxonomy`, gene under `:gene`, gene-product under `:product`, annotation under `:annotation`, and assembly/biosample/sequence are used as-is.

## Project Structure

```
src/cljc/ncbi_api_client/   — shared by the JVM and the browser
  plan.cljc     — Plans: a request description and what to do with its answer
  request.cljc  — Describing Datasets and eutils requests; URL encoding; errors
  throttle.cljc — Token bucket and retry policy (pure), JVM sleeping versions
  eutils.cljc   — E-utilities plans and answer parsing
  datasets/routes.cljc — Datasets route table (generated; don't edit)
src/clj/ncbi_api_client/    — JVM
  runner.clj    — Runs plans: hato, throttle, retry, JSON decoding
  client.clj    — Client creation
  core.clj      — Public API: connect, taxonomy, assembly, gene, search, einfo, etc.
  datasets.clj  — Datasets: page-plan/fetch/fetch-all, report extraction, nav-edges
  datafy.clj    — Generic datafy/nav framework: entity tagging, edge interpretation
  bridge.clj    — Bridge eutils search results into the Datasets nav graph
  package.clj   — Dataset package (zip) downloads
src/dev/
  user.clj      — Dev namespace, creates client, points to demo
  demo.clj      — Extensive demo functions organized by entity type
src/test/ncbi_api_client/
  test_support.clj  — stub-http / stub-client / fixture helpers (no network)
  request_test.cljc — Requests, plans, throttle policy (portable)
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

**Run the tests**:
```bash
clojure -M:test
```

**Regenerate the Datasets route table** (after updating the spec):
```bash
clojure -X:gen-routes
```

## Key Dependencies

- **hato**: HTTP client (the JVM runner's default `:http`)
- **cheshire**: JSON on the JVM
- **clj-yaml**: reading the spec, for `gen-routes` and its test only (`:dev`, `:test`)
- **nrepl**: Development REPL server (`:nrepl` alias)

## Configuration

The API key is read from the `NCBI_API_KEY` environment variable. If unset, requests are made without authentication (rate-limited). The `:api-key` option to `connect` can also be a function or an atom. The runner adds it at send time: for the Datasets API as the `api-key` header, for E-utilities as the `api_key` form parameter along with `tool` and `email` (email only if given) per NCBI's usage policy. Request descriptions never hold the key.

## Working with the Code

Keep the `.cljc` core free of I/O: build request descriptions and plans there, and do the sending in the runner. Things that apply to every request (headers, the key, throttling, retry, decoding) belong in `runner.clj`, not in individual calls.

To add a new Datasets entity type: add an entry to `report-extractors` and a nav-edge in the `nav-edges` table in `datasets.clj`, add a public function to `core.clj`, and add demo functions to `demo.clj`. The generic `datafy.clj` framework does not need modification — it reads nav-edges from the client map at runtime.

To add a new eutils program: add a plan to `eutils.cljc` following the existing pattern (`req/eutils` plus a `read-*` function), and a function taking a client to `core.clj`. If the new program's results should bridge into Datasets, extend `db->datasets` in `bridge.clj`.

Always use `:reload true` when requiring namespaces after changes to ensure the REPL picks up modifications.

The NCBI APIs support CORS, so the browser runner (step 4 of `docs/design.md`) can call them directly. Tests in `.cljc` files should stay runnable under ClojureScript.
