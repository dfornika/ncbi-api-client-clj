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

### R4. Test the navigation graph and bridge with VCR
The library's reason for existing is the least-tested part. The `martian-vcr` harness
is already wired up; record cassettes for a handful of representative nav hops (a
multi-hop Datasets path, `fetch-all` pagination, `:ncbi.nav/next-page`, a bridge
`:ncbi.nav/datasets-entity` resolve, and one `:ncbi.elink/*` follow). This locks down
the most valuable and most fragile behaviour. (See `04` G3.)

### R5. Remove the spike cruft (or finish it deliberately)
Delete the empty `core.cljc`/`core.cljs` stubs and drop the unused `core.async`
dependency, keeping `CLJS-ASYNC-SPIKE-NOTES.md` as the record. The empty `core.cljc`
sharing the real namespace on `:paths` is a latent footgun. If CLJS is genuinely
near-term, promote the spike into a real (non-empty) `cljc` core instead — but don't
leave stubs implying support that doesn't exist. (See `01` S1/S2/S3.)

## Suggested short-term focus (next 1–2 increments)

Ordered by priority, with completed items struck through:

1. ~~**R2 — throttling + retry + typed errors.**~~ DONE.
2. **R5 — remove spike cruft.** Fast, removes a footgun, clarifies what the project is.
   (Hours.)
3. ~~**R1 — data-driven nav table.**~~ DONE (including I2, I3, I4, I5 fixes).
4. **R4 — VCR tests for the nav graph/bridge/pagination** — now the highest-priority
   remaining item. The data-driven refactor is unguarded by nav-hop tests.
5. ~~**R3 — return-type contract + docstrings.**~~ DONE (unified dispatch, docstrings,
   facade promotions for `fetch-all`/`esummary`/`elink`/`elink-available`).

Deliberately **deferred**: broad endpoint coverage, more download package types, CLJS
support, and response schema coercion.
