# 04 — Functionality Gaps

Calibrated to "early work-in-progress": broad endpoint coverage is **not** expected
and not counted against the library. The gaps below are the ones that affect the
*foundation* — things that will be painful to retrofit, or that already cause
real failures today.

## ~~1. No rate limiting — and it bites immediately (most important)~~ FIXED

> **Update (2026-06-23):** Throttling and retry/backoff have been added in
> `throttle.clj`. A token-bucket rate limiter (key-aware: 3 req/s without key,
> 10 req/s with) gates all requests. Retry with exponential backoff handles 429/5xx,
> and failed requests surface as typed errors (`ex-info` with `:ncbi/error`).
> The policy is shared across the Datasets (via `throttle/with-retry` in `datafy.clj`)
> and E-utilities (via `throttle/with-retry` in `eutils.clj`) paths.

## 2. The core value proposition is under-tested

The navigation graph is the reason this library exists, yet the test suite barely
exercises it (19 tests / 81 assertions, all green — but see what they cover):

- `core_test.clj` tests single-entity lookups, the *presence* of datafy keys, the
  pagination *metadata* on a `fetch` result, the binary-download interceptor, and
  package/FASTA parsing. It does **not** test an actual `nav` hop end-to-end (every
  `nav-entity` method is untested), nor `fetch-all` auto-pagination, nor the
  `:ncbi.nav/next-page` traversal.
- `eutils_test.clj` tests eutils parsing and that bridge results carry the right
  metadata keys — but **not** that `nav`-ing `:ncbi.nav/datasets-entity` or an
  `:ncbi.elink/*` key actually resolves.

The VCR machinery (`martian-vcr` playback from `test-resources/vcr`) is already wired
up, so recording cassettes for representative nav hops is low-friction and would lock
down the most valuable and most fragile behaviour. (All of these paths *do* work
live — verified during this review — they're just unguarded by tests.)

## ~~3. Facade omissions~~ FIXED

> **Update (2026-06-23):** `fetch-all`, `esummary`, `elink`, and `elink-available`
> are now promoted to `core` as thin wrappers with docstrings. Users no longer need
> to reach into `datafy`/`eutils` for common operations.

## 4. Doc ↔ behaviour drift

- ~~`README.md` (pagination section) implies you discover the next page by inspecting
  the datafied page; in practice `(contains? (datafy page) :ncbi.nav/next-page)` is
  `false` and the datafied vector gains a stray element (see `02`, I2).~~ The stray
  element bug is fixed — pagination is now in metadata. The README pagination guidance
  should be updated to reflect the new metadata-based discovery.
- `README.md` advertises `d/fetch-all` with a `:genome-dataset-reports-by-taxon`
  operation keyword — correct, but it pushes raw operation ids onto users because no
  facade equivalent exists (ties back to the facade gap).

## 5. Smaller, acceptable-for-now gaps

These are noted for completeness; none are foundational:

- Download packages cover gene + assembly only (no taxonomy/virus packages).
- `elink` results are capped at 200 ids (documented; total count preserved in meta).
- No streaming/large-result handling beyond `fetch-all`'s lazy seq.
- eutils is JSON-only (`retmode=json`) — fine, but XML-only eutils endpoints would
  need a different path later.

## Findings

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| G1 | No rate limiting; 429s reproducibly hit during normal use | **blocking** (for real use) | **FIXED** |
| G2 | No error handling/retry; 429/5xx surface as raw hato exceptions | should-fix | **FIXED** |
| G3 | Nav graph + bridge nav + pagination are essentially untested (VCR already available) | should-fix (high value) | open |
| G4 | `fetch-all`, `esummary`, `elink`, `elink-available` absent from the facade | should-fix | **FIXED** |
| G5 | README pagination/`fetch-all` guidance drifts from actual behaviour | nice-to-have | partially fixed (I2 stray-element bug resolved) |
| G6 | Download coverage limited to gene/assembly packages | nice-to-have | open |
