# 03 — Public API Ergonomics

The public surface is `ncbi-api-client.core`. It is pleasantly small and the
datafy/nav exploration model is genuinely nice in a REPL. The issues below are about
*consistency* and a couple of *surprises* that will bite users as the library grows.

## 1. Return type depends on argument shape (footgun)

The direct-lookup functions switch between returning a **single entity map** and a
**vector of entities** based on the *shape* of the argument:

```clojure
(type (ncbi/taxonomy client "9606"))    ;; => PersistentHashMap   (single)
(type (ncbi/taxonomy client ["9606"]))  ;; => PersistentVector    (vector)
(type (ncbi/gene client 672))           ;; => PersistentHashMap
(type (ncbi/gene client [672]))         ;; => PersistentVector
```

(Verified live.) This is convenient interactively but is a classic ergonomic trap:
callers that pass a user-supplied id can't know the return type without inspecting
it, and generic code can't compose over these functions uniformly. The README leans
on `(first (ncbi/taxonomy client ["9606"]))` to paper over it, which signals the
friction.

The dispatch is also **internally inconsistent**: `taxonomy`/`assembly`/`biosample`
branch on `(string? x)`, while `gene`/`gene-products` branch on `(sequential? x)`.
The predicates are duals, so the behaviour matches, but the asymmetry is a smell and
makes the code harder to scan.

Recommended direction (pick one and apply uniformly):
- Functions always return a vector; add a separate `*-one`/`get-*` family for the
  scalar case; **or**
- Keep the polymorphism but unify the predicate and document the contract explicitly
  in every docstring.

## 2. Docstring coverage is thin

Only `connect`, `search`, and `einfo` have docstrings. The ten direct-lookup
functions (`taxonomy`, `assembly`, `gene`, `biosample`, `sequences`, `gene-products`,
`annotations`, `virus`, `virus-by-accession`, `virus-annotations`,
`virus-annotations-by-accession`) and the two `download-*` functions have **none**.
For a library whose value proposition is interactive REPL exploration, missing
docstrings directly degrade the headline experience (`(doc ncbi/gene)` returns
nothing useful). Every public fn should document its argument shape, its return
shape, and the `:ncbi.nav/*` keys the returned entity exposes.

## 3. Two different "search" result shapes

There are two `search` functions with different return contracts:
- `eutils/search` → `{:count N :summaries [...]}` (a map)
- `core/search` (= `bridge/search`) → a **navigable vector** with pagination metadata

They share a name but return different things. `eutils/search` isn't part of the
public facade, so the collision is mostly internal, but anyone reading both
namespaces will trip over it. Consider renaming the eutils-level one
(`search+summaries` or fold it into `esearch`/`esummary` usage) so "search" means one
thing.

## 4. The unified-client trick is clever but undocumented at the call site

`connect` returns the Martian map with eutils stashed under `:eutils`. This is
explained in `CLAUDE.md`, but from a user's perspective the returned value is an
opaque map that is simultaneously a Martian client and an eutils client. That's fine,
but the contract ("treat this as an opaque handle; pass it to `core` functions
only") deserves a sentence in the `connect` docstring, because the map is tempting to
inspect/destructure and doing so couples user code to internals.

## 5. Asymmetric / missing facade functions

`core` exposes `search` and `einfo` from eutils but not `esummary`, `elink`, or
`elink-available`, and not `fetch-all` from `datafy` (the README points users at
`d/fetch-all`, reaching past the facade). See `04` for the gap analysis; from a pure
*ergonomics* standpoint the facade should be the one place a user needs — today they
must dip into `datafy`/`eutils` for common operations.

## Findings

| # | Finding | Severity |
|---|---------|----------|
| E1 | Return type varies by argument shape (scalar→map, vector→vector) | should-fix |
| E2 | Single-vs-collection dispatch predicate differs across functions (`string?` vs `sequential?`) | nice-to-have |
| E3 | Most public functions lack docstrings (hurts REPL-first UX) | should-fix |
| E4 | Two different `search` result shapes sharing the name | nice-to-have |
| E5 | `connect`'s opaque-handle contract is undocumented at the call site | nice-to-have |
| E6 | Common operations (`fetch-all`, `esummary`, `elink`) require reaching past the facade | should-fix |
