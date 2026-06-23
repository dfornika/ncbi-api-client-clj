# 01 — Codebase Structure

## Module layering

The library has a clean, well-considered layering. Each namespace has a single,
defensible responsibility and the dependency direction flows one way (no cycles):

```
core      ── public facade (connect, taxonomy, gene, search, einfo, …)
  │
  ├── client    ── transport: bootstrap-openapi + Martian interceptor chain
  ├── datasets  ── Datasets API: fetch/pagination, report extraction, nav-edge table
  │     └── datafy   ── generic datafy/nav framework (tag-entity, edge interpretation)
  ├── eutils    ── raw E-utilities client (hato): einfo/esearch/esummary/elink
  ├── bridge    ── makes eutils search results navigable into the Datasets graph
  │     └── datasets, eutils
  └── package   ── ZIP download handling + FASTA parsing
       │
datasets ── depends on datafy, package, throttle
datafy   ── zero project dependencies (only clojure.core.protocols)
bridge   ── depends on datasets + eutils
```

This is the strongest aspect of the codebase. The separation between *transport*
(`client`), *navigation* (`datafy`), *raw eutils* (`eutils`), and the *bridge* that
unifies them is exactly the right set of seams. A newcomer can read `core.clj` (~200
lines, mostly docstrings) and understand the whole public surface in one sitting.

## Namespace responsibilities

| Namespace | Lines | Responsibility | Verdict |
|-----------|------:|----------------|---------|
| `core` | ~200 | Public API facade: connect, all entity lookups, search, eutils, pagination | Clear; grew from ~84 with docstrings + facade promotions |
| `client` | 79 | Martian bootstrap + interceptors + unified client map | Dense but well-commented |
| `datasets` | ~245 | Datasets API: fetch/pagination, report extraction, nav-edge definitions | Extracted from `datafy`; mirrors `eutils` as an API-specific namespace |
| `datafy` | ~40 | Generic datafy/nav framework: tag-entity, resolve-nav, nav-keys-for | Slimmed to pure framework; zero project dependencies |
| `throttle` | ~94 | Token-bucket rate limiter + retry with backoff | Shared across Datasets and eutils paths |
| `eutils` | 128 | Raw eutils programs | Clean, uniform `request` helper |
| `bridge` | 95 | eutils→Datasets datafy/nav bridge | Clever; the trickiest logic |
| `package` | 185 | ZIP/FASTA extraction for download packages | Self-contained, Java-interop heavy |

## Dead / aspirational code

Three items are remnants of an explored-but-shelved ClojureScript/async spike
(documented in `CLJS-ASYNC-SPIKE-NOTES.md`). They currently mislead more than they
help:

1. **`src/main/cljc/ncbi_api_client/core.cljc`** — contains only `(ns ncbi-api-client.core)`.
   It *is* on `:paths`, which means it shares the `ncbi-api-client.core` namespace name
   with the real `src/main/clj/.../core.clj`. Two files claiming the same namespace on
   the classpath is a latent footgun (load order / tooling confusion).
2. **`src/main/cljs/ncbi_api_client/core.cljs`** — also an empty `ns` stub, and
   `src/main/cljs` is **not** on `:paths` at all, so it is invisible to the build.
3. **`org.clojure/core.async`** is declared in `deps.edn` but is **not required
   anywhere** in `src/main`. It was only used in the spike's async fetch experiment.

None of these affect runtime behaviour today, but they make the project look like it
has CLJS/async support that it does not. The spike notes are valuable; the empty
stubs and unused dep are not.

## Build / deps hygiene

`deps.edn` is small and sensible:
- `:paths` includes `src/main/clj`, `src/main/cljc`, `resources` (the `cljc` path is
  the questionable one above).
- `:dev`, `:test`, `:nrepl` aliases are cleanly separated. `:test` wires in
  `martian-test`, `martian-vcr`, and `cognitect test-runner` — good choices.
- One nit: `:nrepl` adds `src/test` to `:extra-paths` (so test namespaces are
  available in the REPL). Reasonable for development, slightly surprising for a
  nominally "nrepl" alias; worth a comment.

## Documentation set

`README.md`, `CLAUDE.md`, and `CLJS-ASYNC-SPIKE-NOTES.md` are all present and of good
quality. `CLAUDE.md` in particular is an unusually thorough architecture document.
The main risk is **drift**: the docs describe behaviour (e.g. pagination discovery
via `datafy`) that doesn't quite match the implementation — see `03` and `04`.

## Findings

| # | Finding | Severity |
|---|---------|----------|
| S1 | Empty `core.cljc` shares the `ncbi-api-client.core` namespace with the real `.clj` file while on `:paths` | should-fix |
| S2 | Empty `core.cljs` stub + `src/main/cljs` not on `:paths` (invisible dead file) | nice-to-have |
| S3 | `core.async` declared but unused in `src/main` | should-fix |
| S4 | Clean one-directional module layering with single-responsibility namespaces | **strength** |
| S5 | `:nrepl` alias silently pulls in `src/test` without explanation | nice-to-have |
