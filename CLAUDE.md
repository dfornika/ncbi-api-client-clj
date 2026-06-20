# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is a Clojure API client library for the NCBI Datasets API (v2), built on Martian for OpenAPI-driven HTTP client generation and Clojure's `datafy`/`nav` protocols for navigable data exploration. The client bootstraps from a local OpenAPI 3 specification (`resources/openapi3.docs.yaml`) and exposes a graph of interconnected biological entities that can be lazily traversed via standard Clojure navigation.

## Architecture

### OpenAPI-Driven Client

The API client is generated at runtime from the OpenAPI spec using Martian's `bootstrap-openapi`. No hand-written endpoint definitions exist — all routes, parameters, and validation come from the spec. The client is created via `ncbi-api-client.client/create-client`, which adds custom interceptors for array path parameter formatting and API token injection.

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
  client.clj    — Martian client creation, interceptors
  core.clj      — Public API: connect, taxonomy, assembly, gene, etc.
  datafy.clj    — datafy/nav implementation, fetch/fetch-all, entity graph
src/dev/
  user.clj      — Dev namespace, creates client, points to demo
  demo.clj      — Extensive demo functions organized by entity type
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

**Explore available API operations**:
```clojure
(martian/explore client)                       ; List all operations
(martian/explore client :virus-genome-table)   ; Details for specific operation
```

## Key Dependencies

- **martian** / **martian-hato**: OpenAPI-driven HTTP client with Hato backend
- **clojure.core.async**: Async programming support
- **nrepl**: Development REPL server (`:nrepl` alias)

## Configuration

The API key is read from the `NCBI_API_KEY` environment variable. If unset, requests are made without authentication (rate-limited). The key is injected via a Martian interceptor in `client.clj`.

## Working with the Code

When modifying API client behavior, use Martian interceptors rather than wrapping individual endpoint calls. See `client.clj` for the existing interceptor chain.

To add a new entity type: add an entry to `report-extractors`, implement `datafy-entity` and `nav-entity` methods in `datafy.clj`, add a public function to `core.clj`, and add demo functions to `demo.clj`.

Always use `:reload true` when requiring namespaces after changes to ensure the REPL picks up modifications.

The NCBI API supports CORS (reflects the Origin header), so a ClojureScript frontend client is feasible in the future.
