# 02 — Idiomatic Clojure

Overall the code reads as competent, modern Clojure: `cond->`, `some->`, threading,
multimethods, metadata-based protocol extension, `parse-long`, transducers in
`bridge`. The issues below are about *repetition* and a couple of *correctness
warts* in the most important namespace (`datafy`), not about basic fluency.

## 1. The nav graph is ~30 near-identical multimethods (biggest opportunity)

`datafy.clj` defines one `nav-entity` method per `[entity-type nav-key]` pair, and
almost every one is the same shape:

```clojure
(defmethod nav-entity [:ncbi/gene :ncbi.nav/organism]
  [client _ coll _ _]
  (let [tax-id (str (:tax_id coll))]
    (fetch-one client :taxonomy-data-report {:taxons [tax-id]} :ncbi/taxonomy)))
```

The varying parts are only: (a) how to extract an id from `coll`, (b) which operation
to call, (c) the parameter key, (d) `fetch` vs `fetch-one` vs `fetch-all`, and (e) the
result entity-type. That is *data*, not code. The same edge defined declaratively:

```clojure
;; sketch
{[:ncbi/gene :ncbi.nav/organism]
 {:op :taxonomy-data-report :param :taxons
  :id #(some-> % :tax_id str) :wrap :one :type :ncbi/taxonomy}}
```

…with a single generic `nav-entity :default` (or one method) interpreting the table,
would collapse ~150 lines into a ~20-line table plus an interpreter. It would also
make the graph **introspectable** (you could print the whole edge set, validate it
against the OpenAPI operations, and auto-generate the `datafy` deferred-key sets in
`datafy-entity` from the same table instead of hand-maintaining both). Right now the
outbound keys in `datafy-entity` and the methods in `nav-entity` must be kept in sync
by hand.

This is the single highest-leverage idiomatic improvement in the codebase.

## 2. `datafy-entity` metadata handling is repetitive and inconsistent

Every `datafy-entity` method repeats the same `with-meta` block:

```clojure
(with-meta {`p/nav (fn [this k v] (nav-entity client :ncbi/X this k v))
            :ncbi/type :ncbi/X
            :ncbi/client client})
```

More importantly, the methods are **inconsistent** about preserving existing
metadata. `:ncbi/assembly` and `:ncbi/gene` do `(merge (meta data) {…})`; the other
seven (`taxonomy`, `biosample`, `annotation`, `gene-product`, `sequence`, `virus`,
`virus-annotation`) **replace** metadata wholesale. The entity arrives from
`tag-entity` carrying `{`p/datafy `p/nav :ncbi/type :ncbi/client}`; the non-merging
methods drop the original `` `p/datafy `` and `:ncbi/client` bindings. It happens to
work because `datafy` already ran to reach `nav`, but the inconsistency is a latent
bug and should be unified through a single helper (e.g. extend `tag-entity` or a
`with-nav` helper used by every method).

## 3. `datafy` on a result vector appends a stray element (correctness wart)

`fetch` attaches this `datafy` to the result vector (`datafy.clj:61`):

```clojure
`p/datafy (fn [this]
            (if token
              (with-meta (conj this [:ncbi.nav/next-page :deferred]) (meta this))
              this))
```

`this` is a **vector**, so `conj` appends `[:ncbi.nav/next-page :deferred]` as a new
*element*, not as a map entry. Empirically:

```clojure
(let [page (d/fetch client :genome-dataset-reports-by-taxon {:taxons ["9606"]} :ncbi/assembly)]
  (count page)          ;; => 20
  (count (datafy page)) ;; => 21   ← inflated
  (last (datafy page))) ;; => [:ncbi.nav/next-page :deferred]   ← not an entity
```

Consequences:
- `(count (datafy page))` disagrees with `(count page)`.
- Mapping/iterating over a datafied page yields a trailing non-entity pair.
- `(contains? (datafy page) :ncbi.nav/next-page)` is **false** (on a vector
  `contains?` tests indices), so the "discover next page via datafy" story doesn't
  actually work as a map-style lookup.

Navigation still works only because `nav-page` reads the token from *metadata*, not
from the appended element. The cleaner idiom is to keep the pagination sentinel in
metadata only, or to datafy a page to a map (`{:results [...] :ncbi.nav/next-page
:deferred}`) rather than mutating the vector's contents.

## 4. Minor idiom nits

- **Repeated id coercion.** `(str (:tax_id coll))`, `(parse-long (str (:gene_id coll)))`
  recur throughout `datafy`/`core`. Small helper fns (`tax-id`, `gene-id`) would dry
  this up and centralise the string/long coercion that NCBI's mixed id types force.
- **`nav-page` uses `_coll`/`_v` underscore params that are actually used** (`_coll`
  is read via `meta`). Underscore-prefixed names conventionally signal "unused";
  rename to `coll`.
- **`gene` returns `parse-long` ids but stores them back as strings elsewhere.** NCBI
  returns `:gene_id` as a string (`"672"`) but requests want integers; the code
  re-parses on every nav hop. Normalising once at the boundary would reduce churn.

## Findings

| # | Finding | Severity |
|---|---------|----------|
| I1 | `nav-entity` is ~30 boilerplate methods; should be a data-driven edge table | should-fix (high value) |
| I2 | `datafy` on a result vector appends a stray non-entity element; `count` inflated, `contains?` broken | should-fix (correctness) |
| I3 | `datafy-entity` metadata merge is inconsistent across types (7 of 9 replace meta) | should-fix |
| I4 | Repeated id-coercion (`str`/`parse-long`) not factored into helpers | nice-to-have |
| I5 | Misleading underscore param names in `nav-page` | nice-to-have |
