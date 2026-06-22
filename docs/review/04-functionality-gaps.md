# 04 — Functionality Gaps

Calibrated to "early work-in-progress": broad endpoint coverage is **not** expected
and not counted against the library. The gaps below are the ones that affect the
*foundation* — things that will be painful to retrofit, or that already cause
real failures today.

## 1. No rate limiting — and it bites immediately (most important)

`CLAUDE.md` notes NCBI's guidance (3 req/s without a key, 10 req/s with one) and that
"the client does not enforce throttling automatically." This is not a theoretical
gap. During this review, ordinary exploration tripped NCBI's limiter twice, surfacing
as a raw exception:

```clojure
(demo/follow-link client "gene" "TP53 human" :ncbi.elink/gene_pubmed)
;; Execution error (ExceptionInfo) … hato.middleware/exceptions-response
;; status: 429
```

The bridge is especially exposed because a single `search`/`follow-link` fans out
into several requests (esearch + esummary + elink-available + elink + a Datasets
fetch). Two foundational pieces are missing:

- **Throttling** — a token-bucket / min-interval gate (ideally key-aware: 3 vs 10
  req/s). On the Datasets side this is a natural Martian interceptor; eutils would
  need an equivalent gate in its `request` helper.
- **Error handling for 429 (and 5xx)** — today a 429 propagates as an opaque hato
  `ExceptionInfo`. There is no retry/backoff and no library-level error type, so
  every caller must catch raw hato exceptions. A retry-with-backoff interceptor plus
  a typed error (`ex-info` with `:ncbi/error`) would make the library usable under
  real load.

Because these are cross-cutting (interceptor + eutils gate), they are much cheaper to
add now than after more endpoints exist.

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

## 3. Facade omissions

`eutils` implements `esummary`, `elink`, and `elink-available`, and `datafy`
implements `fetch-all`, but `core` surfaces none of them. Users must reach into
`datafy`/`eutils` for common needs (auto-pagination, raw cross-db links). Promoting
these to `core` is small and high-value (see `03`, E6).

## 4. Doc ↔ behaviour drift

- `README.md` (pagination section) implies you discover the next page by inspecting
  the datafied page; in practice `(contains? (datafy page) :ncbi.nav/next-page)` is
  `false` and the datafied vector gains a stray element (see `02`, I2). The `nav`
  call shown does work.
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

| # | Finding | Severity |
|---|---------|----------|
| G1 | No rate limiting; 429s reproducibly hit during normal use | **blocking** (for real use) |
| G2 | No error handling/retry; 429/5xx surface as raw hato exceptions | should-fix |
| G3 | Nav graph + bridge nav + pagination are essentially untested (VCR already available) | should-fix (high value) |
| G4 | `fetch-all`, `esummary`, `elink`, `elink-available` absent from the facade | should-fix |
| G5 | README pagination/`fetch-all` guidance drifts from actual behaviour | nice-to-have |
| G6 | Download coverage limited to gene/assembly packages | nice-to-have |
