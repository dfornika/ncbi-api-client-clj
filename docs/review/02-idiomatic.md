# 02 — Idiomatic Clojure

Overall the code reads as competent, modern Clojure: `cond->`, `some->`, threading,
metadata-based protocol extension, `parse-long`, transducers in `bridge`.

> **Update (2026-06-23):** All five findings in this section have been addressed.
> The nav graph is now a data-driven `nav-edges` table with a generic interpreter;
> the `datafy-entity`/`nav-entity` multimethods have been removed entirely.

## ~~1. The nav graph is ~30 near-identical multimethods (biggest opportunity)~~ FIXED

`datafy.clj` now defines a single `nav-edges` table (30 entries: 20 standard
data-driven, 10 custom with `:nav-fn`). A generic `resolve-nav` interpreter handles
dispatch, and `nav-keys-for` derives the deferred-key sets for `datafy` from the same
table — no manual sync required.

Standard edges are pure data:

```clojure
{[:ncbi/gene :ncbi.nav/organism]
 {:id tax-id, :op :taxonomy-data-report
  :params (fn [id] {:taxons [id]}), :fetch :one, :type :ncbi/taxonomy}}
```

Custom edges (links, image, package, multi-param) use `:nav-fn` as an escape hatch.
The graph is now introspectable — entity types, outbound nav keys, and operations can
all be queried programmatically from `nav-edges`.

## ~~2. `datafy-entity` metadata handling is repetitive and inconsistent~~ FIXED

The `datafy-entity` multimethod has been eliminated. `tag-entity` now handles both
`datafy` and `nav` setup in one place, with consistent `(merge (meta this) ...)`
across all entity types.

## ~~3. `datafy` on a result vector appends a stray element (correctness wart)~~ FIXED

`fetch` now keeps the pagination indicator in metadata only:

```clojure
`p/datafy (fn [this]
            (with-meta this
              (cond-> (meta this)
                token (assoc :ncbi.nav/next-page :deferred))))
```

The result vector is never mutated — `(count page)` and `(count (datafy page))`
agree, and there is no trailing non-entity element. Pagination is discoverable via
`(:ncbi.nav/next-page (meta (datafy page)))` and navigable via
`(nav (datafy page) :ncbi.nav/next-page :deferred)` as before.

## 4. Minor idiom nits (partially addressed)

- ~~**Repeated id coercion.**~~ FIXED — `tax-id` and `gene-id` private helpers in
  `datafy.clj` centralise the coercion. `core.clj` still uses inline
  `(parse-long (str ...))` in a few places.
- ~~**`nav-page` uses `_coll` underscore param that is actually used.**~~ FIXED —
  renamed to `coll`.
- **`gene` returns `parse-long` ids but stores them back as strings elsewhere.** NCBI
  returns `:gene_id` as a string (`"672"`) but requests want integers; the code
  re-parses on every nav hop. Normalising once at the boundary would reduce churn.

## Findings

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| I1 | `nav-entity` is ~30 boilerplate methods; should be a data-driven edge table | should-fix (high value) | **FIXED** |
| I2 | `datafy` on a result vector appends a stray non-entity element; `count` inflated, `contains?` broken | should-fix (correctness) | **FIXED** |
| I3 | `datafy-entity` metadata merge is inconsistent across types (7 of 9 replace meta) | should-fix | **FIXED** |
| I4 | Repeated id-coercion (`str`/`parse-long`) not factored into helpers | nice-to-have | **FIXED** (in `datafy.clj`; `core.clj` still inline) |
| I5 | Misleading underscore param names in `nav-page` | nice-to-have | **FIXED** |
