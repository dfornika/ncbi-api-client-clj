# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is a Clojure API client library for the NCBI Datasets API (v2) and E-utilities, built on Martian for OpenAPI-driven HTTP client generation and Clojure's `datafy`/`nav` protocols for navigable data exploration. The Datasets client bootstraps from a local OpenAPI 3 specification (`resources/openapi3.docs.yaml`). The E-utilities client provides keyword search across 38 Entrez databases and cross-database linking. Together they expose a navigable graph of interconnected biological entities that can be lazily traversed via standard Clojure navigation — starting from keyword search all the way to structured entity data.

## Architecture

### Unified Client

`ncbi-api-client.client/create-client` produces a single client map that combines both APIs. The Datasets client (Martian) forms the base map; the E-utilities client is stored under the `:eutils` key. Martian ignores unknown keys, so the unified client works transparently with both APIs. Functions in `eutils.clj` use `resolve-client` to extract the `:eutils` sub-map, falling back to the map itself for standalone eutils clients.

### OpenAPI-Driven Datasets Client

The Datasets API client is generated at runtime from the OpenAPI spec using Martian's `bootstrap-openapi`. No hand-written endpoint definitions exist — all routes, parameters, and validation come from the spec. Custom interceptors handle array path parameter formatting and API token injection.

### E-utilities Client

The E-utilities client (`eutils.clj`) uses hato directly — there is no OpenAPI spec for eutils. It supports four eutils programs: `einfo` (database metadata), `esearch` (keyword search), `esummary` (document summaries), and `elink` (cross-database links). All requests use `retmode=json`. NCBI's rate guidelines: 3 req/sec without API key, 10 req/sec with one — the client does not enforce throttling automatically.

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

Different API endpoints nest their data differently. The `report-extractors` map in `datafy.clj` handles unwrapping: taxonomy reports are under `:taxonomy`, gene under `:gene`, gene-product under `:product`, annotation under `:annotation`, and assembly/biosample/sequence are used as-is.

## Project Structure

```
src/main/clj/ncbi_api_client/
  client.clj    — Unified client creation (Datasets + eutils), Martian interceptors
  core.clj      — Public API: connect, taxonomy, assembly, gene, search, einfo, etc.
  datafy.clj    — datafy/nav implementation, fetch/fetch-all, entity graph
  eutils.clj    — E-utilities client: einfo, esearch, esummary, elink
  bridge.clj    — Bridge eutils search results into the Datasets nav graph
src/dev/
  user.clj      — Dev namespace, creates client, points to demo
  demo.clj      — Extensive demo functions organized by entity type
src/test/ncbi_api_client/
  core_test.clj   — Datasets API tests (VCR-based and mock)
  eutils_test.clj — E-utilities and bridge tests (mock-based)
resources/
  openapi3.docs.yaml — NCBI Datasets API OpenAPI 3 spec (local copy)
scripts/
  setup-dev-env.sh   — Idempotent bootstrap: installs Java, Clojure CLI,
                        Babashka, bbin, clojure-mcp-light tools, deps
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
(martian/explore client)                       ; List all operations
(martian/explore client :virus-genome-table)   ; Details for specific operation
```

## Key Dependencies

- **martian** / **martian-hato**: OpenAPI-driven HTTP client with Hato backend
- **hato**: HTTP client (used directly by E-utilities, also backs Martian)
- **cheshire**: JSON parsing for eutils responses
- **clojure.core.async**: Async programming support
- **nrepl**: Development REPL server (`:nrepl` alias)

## Configuration

The API key is read from the `NCBI_API_KEY` environment variable. If unset, requests are made without authentication (rate-limited). For the Datasets API, the key is injected via a Martian interceptor in `client.clj`. For E-utilities, it's sent as the `api_key` query parameter along with `tool` and `email` per NCBI's usage policy.

## Working with the Code

When modifying Datasets API client behavior, use Martian interceptors rather than wrapping individual endpoint calls. See `client.clj` for the existing interceptor chain.

To add a new Datasets entity type: add an entry to `report-extractors`, implement `datafy-entity` and `nav-entity` methods in `datafy.clj`, add a public function to `core.clj`, and add demo functions to `demo.clj`.

To add a new eutils program: add it to `eutils.clj` following the existing pattern (use `request` helper, parse JSON response). If the new program's results should bridge into Datasets, extend `db->datasets` in `bridge.clj`.

Always use `:reload true` when requiring namespaces after changes to ensure the REPL picks up modifications.

The NCBI API supports CORS (reflects the Origin header), so a ClojureScript frontend client is feasible in the future.
