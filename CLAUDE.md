# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is a Clojure API client for the NCBI Datasets API (v2), using Martian for OpenAPI-based HTTP client generation. The client bootstraps from a local OpenAPI 3 specification (`resources/openapi3.docs.yaml`) to provide type-safe access to NCBI's genome, virus, taxonomy, gene, and other biological data endpoints.

## Architecture

**OpenAPI-Driven Client**: The entire API client is generated at runtime from the OpenAPI specification using Martian's `bootstrap-openapi` function. No hand-written endpoint definitions exist - all routes, parameters, and validation come from the spec.

**Development Namespace**: Primary development happens in `dev/user.clj`, which sets up the Martian client instance and provides a rich comment block with example API calls. The main `ncbi-api-client.core` namespace is currently empty.

**Martian Interceptor Chain**: API requests flow through Martian's interceptor chain. Custom interceptors (e.g., `add-api-token`) can be added to inject headers or transform requests/responses.

## Development Commands

**Start nREPL Server**:
```bash
clojure -M:nrepl
```
The nREPL server starts on port 7888 (configured in `deps.edn`). The current port is also written to `.nrepl-port`.

**Connect to Running REPL**:
```bash
clj-nrepl-eval --discover-ports
clj-nrepl-eval -p 7888 "(+ 1 2)"
```

**Load Development Environment**:
From a connected REPL:
```clojure
(require '[user :reload true])
```

**Explore Available API Endpoints**:
```clojure
(martian/explore m)                    ; List all operations
(martian/explore m :virus-genome-table) ; Details for specific operation
```

**Make API Requests**:
```clojure
;; Preview the request
(martian/request-for m :virus-genome-table {:taxon "2697049" :accessions ["NC_045512"]})

;; Execute and get response
(martian/response-for m :virus-genome-table {:taxon "2697049" :accessions ["NC_045512"]})
```

## Key Dependencies

- **martian**: OpenAPI-driven HTTP client library
- **martian-hato**: Hato-based HTTP backend for Martian
- **clojure.core.async**: Async programming support

## Important Files

- `dev/user.clj`: Development namespace with client setup and example usage
- `resources/openapi3.docs.yaml`: NCBI Datasets API OpenAPI 3 specification (local copy)
- `deps.edn`: Project dependencies and REPL configuration
- `.dir-locals.el`: Emacs/CIDER configuration (uses `:dev` alias)

## Working with the API

The API token is currently hardcoded in `dev/user.clj:10`. For production use, this should be externalized to environment variables or configuration.

When modifying API client behavior, use Martian interceptors rather than wrapping individual endpoint calls. See `add-api-token` interceptor in `dev/user.clj:12-15` for an example.

Always use `:reload true` when requiring namespaces after making changes to ensure the REPL picks up modifications.
