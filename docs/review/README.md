# Codebase Review — `ncbi-api-client-clj`

*Date: 2026-06-22 · Scope: full `src/main` + tests · Method: static reading +
empirical validation (test suite + live REPL exercise of the nav graph, bridge,
pagination, and Martian request building).*

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

**A solid, clean foundation — genuinely good bones — with one gap that breaks real use
today (rate limiting) and one structural refactor that should happen before growth
(make the nav graph data-driven).** The architecture is the strong point: the
layering is right, Martian is used the way it's meant to be used, and the
`datafy`/`nav` exploration model is a real pleasure in the REPL. The weaknesses are
concentrated and addressable, and none of them are baked so deep that they're hard to
fix now. This is a good place to pause and harden before adding breadth.

Everything documented here was checked against running code: the test suite is green
(19 tests, 81 assertions), and the nav graph, bridge, and pagination all work live —
the criticisms are about robustness, consistency, and maintainability, not broken
features.

## Top strengths

1. **Clean, one-directional module layering** with single-responsibility namespaces
   (`client`/`core`/`datafy`/`eutils`/`bridge`/`package`). (`01` S4)
2. **Idiomatic, fully spec-driven Martian usage** — `bootstrap-openapi`, interceptor
   injection via `:replace`/`:after`/`conj`, and `martian-test`/`martian-vcr` for
   testing. (`05` M1)
3. **The datafy/nav navigation graph** is a genuinely good abstraction and works
   end-to-end across multi-hop paths and the eutils→Datasets bridge. (verified live)
4. **Thoughtful transport details** — the binary-download and array-path interceptors
   solve real Martian edge cases, and the unified Datasets+eutils client is a neat
   single-handle design.

## Top risks

1. **No rate limiting or error handling.** NCBI's limiter (429) was tripped twice
   during ordinary review-time exploration and surfaced as a raw hato exception. This
   blocks real-world use. (`04` G1/G2)
2. **The nav graph is ~30 boilerplate multimethods** kept in sync with
   `datafy-entity` by hand. Refactor to a data-driven edge table before adding
   entities. (`02` I1, `06` R1)
3. **The core value proposition is under-tested.** No test exercises an actual `nav`
   hop, `fetch-all`, or bridge resolution — only metadata-key presence. (`04` G3)
4. **`datafy` on a result vector appends a stray non-entity element** (count inflated
   to 21, `contains?` broken); navigation only works because it reads metadata. (`02`
   I2)
5. **Spike cruft** — empty `core.cljc`/`core.cljs` stubs (the `.cljc` shares the real
   namespace on `:paths`) and an unused `core.async` dependency imply support that
   doesn't exist. (`01` S1–S3)

## Short-term focus (detail in [06](06-recommendations.md))

1. **Throttling + retry/backoff + typed errors**, shared across the Martian and eutils
   paths — unblocks real use.
2. **Remove the spike cruft** — fast, removes a footgun.
3. **Make the nav graph data-driven** — the structural unlock that makes further
   entity work cheap; fold in the `datafy`-vector and metadata-merge fixes here.
4. **VCR tests for the nav graph / bridge / pagination** — ideally landed with the
   refactor to guard it.
5. **Settle the return-type contract + add docstrings**, and promote
   `fetch-all`/`esummary`/`elink` to the facade.

Deferred on purpose: broad endpoint coverage, more package types, CLJS, and response
schema coercion — after the foundation (above) is hardened.
