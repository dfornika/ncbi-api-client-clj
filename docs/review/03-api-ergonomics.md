# 03 — Public API Ergonomics

The public surface is `ncbi-api-client.core`. It is pleasantly small and the
datafy/nav exploration model is genuinely nice in a REPL.

> **Update (2026-06-23):** Findings E1, E2, E3, E5, and E6 have been addressed.
> The dispatch predicate is unified, all public functions have docstrings documenting
> argument shapes / return types / nav keys, `connect` documents the opaque-handle
> contract, and `fetch-all`/`esummary`/`elink`/`elink-available` are promoted to the
> facade.

## ~~1. Return type depends on argument shape (footgun)~~ ADDRESSED

The polymorphic return-type behavior (scalar→map, collection→vector) has been
**kept by design** but made consistent and documented:

- All 7 polymorphic functions now use `(sequential? x)` as the dispatch predicate
  (previously 5 used `(string? x)` and 2 used `(sequential? x)`).
- Every polymorphic function's docstring explicitly states the contract: "Pass a
  single ID for one record, or a vector for a batch. Returns a single entity map
  (scalar) or a tagged vector of entities (collection)."

The polymorphism remains a tradeoff — convenient for REPL exploration, less
predictable for generic code — but it is now uniform and clearly documented.

## ~~2. Docstring coverage is thin~~ FIXED

All public functions now have docstrings documenting argument shapes, return types,
and available `:ncbi.nav/*` keys. `(doc ncbi/gene)` now returns useful information
including the nav keys for interactive exploration.

## 3. Two different "search" result shapes

This remains as-is: `eutils/search` returns `{:count :summaries}` and `core/search`
returns a navigable vector. Since `eutils/search` is not part of the public facade,
this is an internal naming collision only.

## ~~4. The unified-client trick is clever but undocumented at the call site~~ FIXED

`connect`'s docstring now states: "Returns an opaque client handle — pass it as the
first argument to all other functions in this namespace."

## ~~5. Asymmetric / missing facade functions~~ FIXED

`core` now exposes `fetch-all`, `esummary`, `elink`, and `elink-available` as thin
wrappers with docstrings. Users no longer need to reach into `datafy` or `eutils`
for common operations.

## Findings

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| E1 | Return type varies by argument shape (scalar→map, vector→vector) | should-fix | **ADDRESSED** (kept, unified, documented) |
| E2 | Single-vs-collection dispatch predicate differs across functions (`string?` vs `sequential?`) | nice-to-have | **FIXED** |
| E3 | Most public functions lack docstrings (hurts REPL-first UX) | should-fix | **FIXED** |
| E4 | Two different `search` result shapes sharing the name | nice-to-have | open (internal only) |
| E5 | `connect`'s opaque-handle contract is undocumented at the call site | nice-to-have | **FIXED** |
| E6 | Common operations (`fetch-all`, `esummary`, `elink`) require reaching past the facade | should-fix | **FIXED** |
