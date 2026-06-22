# 06 — Recommendations & Short-Term Focus

## High-level recommendations

### R1. Make the navigation graph data-driven before adding more entities
The single biggest structural improvement. Today, adding/maintaining an entity means
editing `datafy-entity` (deferred keys) **and** writing N `nav-entity` methods that
are 90% boilerplate, kept in sync by hand. Replace the ~30 methods with one **edge
table** (`{[entity-type nav-key] {:op … :param … :id … :wrap … :type …}}`) plus a
single generic interpreter, and derive the `datafy` deferred-key sets from the same
table. Payoff: less code, no sync-by-hand, an introspectable graph you can validate
against the OpenAPI operations, and a trivial path to adding new edges. Do this
*before* fleshing out coverage, so new endpoints are table rows, not new functions.
(See `02` I1.)

### R2. Add throttling + retry/backoff as a shared policy across both transports
Rate limiting is the one gap that breaks normal use today (reproducible 429s). Build a
single throttle/backoff policy and apply it in two places: a Martian interceptor for
Datasets and the `eutils/request` helper for E-utilities. Make it key-aware (3 vs 10
req/s) and introduce a typed library error (`ex-info` with `:ncbi/error`) so callers
stop catching raw hato exceptions. Cross-cutting work like this is far cheaper now
than after the surface grows. (See `04` G1/G2, `05` M4.)

### R3. Settle the return-type contract and document the public surface
Decide whether direct-lookup functions return a vector always (with a separate scalar
`get-*`/`*-one` family) or stay polymorphic — then apply it uniformly (unify the
`string?`/`sequential?` dispatch) and give every public fn a docstring stating
argument shape, return shape, and exposed `:ncbi.nav/*` keys. For a REPL-first library
this directly improves the headline experience. (See `03` E1/E2/E3.)

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

Ordered to fix what's broken first, then to make growth cheap and safe:

1. **R2 — throttling + retry + typed errors.** Unblocks real use; the 429s are not
   hypothetical. (~½–1 increment.)
2. **R5 — remove spike cruft.** Fast, removes a footgun, clarifies what the project is.
   (Hours.)
3. **R1 — data-driven nav table.** The structural unlock that makes all further entity
   work cheap; do it while the graph is still small enough to refactor confidently.
   (~1 increment.) Fold in the `datafy`-on-vector pagination wart (`02` I2) and the
   `datafy-entity` metadata-merge inconsistency (`02` I3) as part of this pass.
4. **R4 — VCR tests for the nav graph/bridge/pagination**, ideally landed *with* R1 so
   the refactor is guarded.
5. **R3 — return-type contract + docstrings.** Best done deliberately as a small
   API-polish pass once R1 settles internal shapes; promote `fetch-all`/`esummary`/
   `elink` to the facade at the same time (`03` E6, `04` G4).

Deliberately **deferred**: broad endpoint coverage, more download package types, CLJS
support, and response schema coercion. The foundation should be solid (R1–R5) before
breadth is added on top of it.
