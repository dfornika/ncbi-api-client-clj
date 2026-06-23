# Codebase Review — `ncbi-api-client-clj`

*Date: 2026-06-22 · Scope: full `src/main` + tests · Method: static reading +
empirical validation (test suite + live REPL exercise of the nav graph, bridge,
pagination, and Martian request building).*

*Updated: 2026-06-23 — marked findings fixed by the throttling (`04` G1/G2, `05` M4),
data-driven nav graph (`02` I1–I5), and API contract/docstring/facade work
(`03` E1–E3/E5/E6, `04` G4).*

This is a point-in-time assessment of the **foundation**, taken at the author's
request before fleshing out more of the Datasets/E-utilities surface. Broad endpoint
coverage was explicitly **not** expected and is not counted against the library.

## Read first

| Doc | Theme |
|-----|-------|
| [01-structure.md](01-structure.md) | Module layering, dead/spike code, build hygiene |
| [02-idiomatic.md](02-idiomatic.md) | Clojure idiom; nav-graph boilerplate; datafy warts |
| [03-api-ergonomics.md](03-api-ergonomics.md) | Public API shape, return types, docstrings |
| [04-functionality-gaps.md](04-functionality-gaps.md) | Rate limiting, testing, facade gaps |
| [05-martian-usage.md](05-martian-usage.md) | How well Martian is leveraged |
| [06-recommendations.md](06-recommendations.md) | Prioritized recommendations + short-term focus |

## Overall verdict

**A solid, clean foundation — genuinely good bones.** The architecture is the strong
point: the layering is right, Martian is used the way it's meant to be used, and the
`datafy`/`nav` exploration model is a real pleasure in the REPL.

Since the initial review, the three most critical recommendations have been addressed:
rate limiting/retry is in place (`throttle.clj`), the nav graph has been refactored
to a data-driven edge table (`nav-edges` in `datafy.clj`), and the public API
contract has been settled with unified dispatch, comprehensive docstrings, and
facade promotions for `fetch-all`/`esummary`/`elink`/`elink-available`. The remaining
work is incremental hardening — tests and spike cleanup.

Everything documented here was checked against running code: the test suite is green
(36 tests, 120 assertions), and the nav graph, bridge, and pagination all work
live.

## Top strengths

1. **Clean, one-directional module layering** with single-responsibility namespaces
   (`client`/`core`/`datafy`/`eutils`/`bridge`/`package`/`throttle`). (`01` S4)
2. **Idiomatic, fully spec-driven Martian usage** — `bootstrap-openapi`, interceptor
   injection via `:replace`/`:after`/`conj`, and `martian-test`/`martian-vcr` for
   testing. (`05` M1)
3. **The datafy/nav navigation graph** is a genuinely good abstraction and works
   end-to-end across multi-hop paths and the eutils→Datasets bridge. Now backed by a
   data-driven edge table that is introspectable and trivial to extend. (verified live)
4. **Thoughtful transport details** — the binary-download and array-path interceptors
   solve real Martian edge cases, and the unified Datasets+eutils client is a neat
   single-handle design.
5. **Shared throttling + retry/backoff** — token-bucket rate limiter and exponential
   backoff with typed errors, shared across Datasets and eutils paths.

## Remaining risks

1. **The core value proposition is under-tested.** No test exercises an actual `nav`
   hop, `fetch-all`, or bridge resolution — only metadata-key presence. (`04` G3)
2. **Spike cruft** — empty `core.cljc`/`core.cljs` stubs (the `.cljc` shares the real
   namespace on `:paths`) and an unused `core.async` dependency imply support that
   doesn't exist. (`01` S1–S3)

## Short-term focus (detail in [06](06-recommendations.md))

1. ~~**Throttling + retry/backoff + typed errors**~~ — done (`throttle.clj`).
2. **Remove the spike cruft** — fast, removes a footgun.
3. ~~**Make the nav graph data-driven**~~ — done (`nav-edges` table in `datafy.clj`).
4. **VCR tests for the nav graph / bridge / pagination** — the most valuable untested
   behaviour.
5. ~~**Settle the return-type contract + add docstrings**~~ — done (unified dispatch,
   docstrings on all public fns, facade promotions).

Deferred on purpose: broad endpoint coverage, more package types, CLJS, and response
schema coercion — after the foundation (above) is hardened.
