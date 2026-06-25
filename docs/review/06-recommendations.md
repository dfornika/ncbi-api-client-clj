# 06 — Recommendations & Short-Term Focus

## High-level recommendations

### ~~R1. Make the navigation graph data-driven before adding more entities~~ DONE

Completed 2026-06-23. The `datafy-entity`/`nav-entity` multimethods have been
replaced with a single `nav-edges` table (30 entries) plus a generic `resolve-nav`
interpreter. Deferred keys for `datafy` are derived from the same table via
`nav-keys-for`. The graph is introspectable, metadata merge is consistent, and the
stray-element pagination bug (`02` I2) and id-coercion repetition (`02` I4/I5) were
fixed in the same pass. Net result: ~95 fewer lines of code.

### ~~R2. Add throttling + retry/backoff as a shared policy across both transports~~ DONE

Completed 2026-06-23. `throttle.clj` provides a token-bucket rate limiter
(key-aware: 3 vs 10 req/s) and `with-retry` for exponential backoff on 429/5xx.
Typed errors (`ex-info` with `:ncbi/error`) replace raw hato exceptions. The policy
is shared across Datasets (`datafy.clj`) and E-utilities (`eutils.clj`).

### ~~R3. Settle the return-type contract and document the public surface~~ DONE

Completed 2026-06-23. The polymorphic return type (scalar→map, collection→vector)
was kept by design but unified and documented:

- All 7 polymorphic functions now use `(sequential? x)` as the dispatch predicate
  (previously 5 used `(string? x)` and 2 used `(sequential? x)`).
- Every public function has a docstring documenting argument shapes, return types,
  and available `:ncbi.nav/*` keys.
- `connect` documents the opaque-handle contract.
- `fetch-all`, `esummary`, `elink`, and `elink-available` promoted to the `core` facade.

(See `03` E1/E2/E3/E5/E6, `04` G4.)

### ~~R4. Test the navigation graph and bridge~~ DONE

Completed 2026-06-23. Seven new tests cover the core navigation paths:
single-hop Datasets nav (taxonomy→assemblies, gene→organism), multi-hop
(gene→organism→assemblies), pagination (`:ncbi.nav/next-page` and `fetch-all`
auto-pagination), and bridge nav (`:ncbi.nav/datasets-entity` resolution,
`:ncbi.elink/*` link following). Tests use `mt/respond-with` mocks with
multi-operation response maps. This also uncovered and fixed a forward-reference
bug: `nav-edges` captured unbound var values for `fetch-all`/`fetch-one`; fixed
with `#'var` references. (See `04` G3.)

### ~~R5. Remove the spike cruft (or finish it deliberately)~~ DONE

Completed 2026-06-23. Deleted the empty `core.cljc` and `core.cljs` stubs,
removed `"src/main/cljc"` from `:paths`, dropped the unused `core.async`
dependency from `deps.edn`, and moved `CLJS-ASYNC-SPIKE-NOTES.md` to
`docs/exploration/` to preserve the record. (See `01` S1/S2/S3.)

## Suggested short-term focus (next 1–2 increments)

Ordered by priority, with completed items struck through:

1. ~~**R2 — throttling + retry + typed errors.**~~ DONE.
2. ~~**R5 — remove spike cruft.**~~ DONE (stubs deleted, core.async removed,
   spike notes moved to `docs/exploration/`).
3. ~~**R1 — data-driven nav table.**~~ DONE (including I2, I3, I4, I5 fixes).
4. ~~**R4 — nav graph/bridge/pagination tests.**~~ DONE (nav hops, pagination,
   bridge resolution, plus forward-reference bug fix).
5. ~~**R3 — return-type contract + docstrings.**~~ DONE (unified dispatch, docstrings,
   facade promotions for `fetch-all`/`esummary`/`elink`/`elink-available`).

Deliberately **deferred**: broad endpoint coverage, more download package types, CLJS
support, and response schema coercion.
