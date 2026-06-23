# 05 — Use of Martian

Verdict: **Martian is used well and idiomatically** for the Datasets side. The client
is fully spec-driven, the interceptor chain is composed the way Martian intends, and
the test tooling (`martian-test`, `martian-vcr`) is exactly what the library should be
using. There is one genuine workaround worth understanding, and one underused
feature.

## What's done well

### Spec-driven bootstrap, no hand-written routes
`client/create-client` uses `martian-http/bootstrap-openapi` against the local
`resources/openapi3.docs.yaml`. All routes, params, and the operation keywords used
throughout `datafy`/`core` come from the spec — there are no hand-maintained endpoint
definitions. This is the core Martian value proposition and the library leans on it
fully.

### Idiomatic interceptor composition
`client.clj` builds its chain by *injecting* into the default hato interceptors rather
than re-implementing them:

```clojure
(-> martian-http/hato-interceptors
    (interceptors/inject (encode-request encoders) :replace ::encode-request)
    (interceptors/inject (coerce-response encoders …) :replace ::coerce-response)
    (interceptors/inject fix-binary-response   :after ::coerce-response)
    (interceptors/inject fix-array-path-params :after ::url)
    (conj add-token)
    (conj perform-request))
```

This is textbook Martian: `:replace` to swap the encoder/coercer (to add `text/plain`,
`application/zip`, image types), `:after` to position the binary and array fixes
relative to named built-ins, and `conj` to append the token + request stages. The
`fix-binary-response` interceptor (forcing `:as :byte-array` + `Accept:
application/zip` when an endpoint declares a binary `:produces`) is a clean,
well-commented solution to Martian's `choose-media-type` picking `text/plain` for the
download endpoints. It even has a unit test (`download-binary-response-test`).

### Token injection as an interceptor
The API token is added via the `::add-api-token` interceptor, which is the right place
for a cross-cutting request concern — exactly what `CLAUDE.md` prescribes ("use
interceptors rather than wrapping individual endpoint calls").

### Test tooling
`martian-test/respond-with` (constant + function stubs) and `martian-vcr` playback are
both used. This is the idiomatic Martian testing story and means the HTTP boundary can
be tested without network access.

## The one real workaround: `fix-array-path-params`

`client/fix-url-array-params` does regex surgery on the *final URL string* to turn an
array path parameter into a comma-joined list. This exists because Martian 0.2.3's
OpenAPI bootstrap serialises an array path param as the raw Clojure literal. Verified
against a vanilla bootstrap (no custom interceptors):

```clojure
(martian/request-for vanilla :taxonomy-data-report {:taxons ["9606" "10090"]})
;; :url ".../taxonomy/taxon/[\"9606\" \"10090\"]/dataset_report"   ← bracketed literal
```

…whereas with the interceptor the URL becomes `.../taxon/9606,10090/dataset_report`
(the form NCBI's `style: simple` path arrays expect). So the workaround is justified —
it's compensating for Martian not honouring OpenAPI path-array serialisation
(`style: simple`, `explode: false`).

**However**, doing it as a regex pass over the assembled URL is fragile: it parses
brackets and quotes out of an already-stringified URL and re-joins, which couples it to
the exact textual form Martian emits and could misfire on values containing brackets or
spaces. A more robust approach operates on the *parameter values before templating*
(an interceptor that serialises array path params to comma-joined strings in
`[:request :params]`/`:path-params`), or upstream: fix the serialisation in the route
data at bootstrap. Worth a focused spike — if a values-level interceptor works, it
removes the most brittle piece of `client.clj`.

## Underused: response coercion / schema validation

Martian carries `schema`/`schema-tools`/`spec-tools` (on the classpath via the
dependency tree) and can coerce/validate responses against the spec's schemas. The
library currently treats response bodies as raw maps — `fetch` reaches into
`(:reports body)` and `report-extractors` unwraps by hand. That's pragmatic, but it
means none of the spec's response typing is leveraged, and entities are untyped maps.
Not a problem now; flagged because as entity types multiply, spec-driven coercion (and
the validation it gives for free) is a Martian feature that would pay off.

## Eutils is (correctly) not Martian

`eutils.clj` uses hato directly with no spec — appropriate, since NCBI publishes no
OpenAPI spec for E-utilities. ~~The rate-limiting/retry concern therefore has to be
solved twice.~~ This has been addressed: `throttle.clj` provides a shared
token-bucket + retry policy used by both `datafy.clj` (Datasets) and `eutils.clj`
(E-utilities).

## Findings

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| M1 | Fully spec-driven bootstrap + idiomatic interceptor injection + martian-test/vcr | **strength** | — |
| M2 | `fix-array-path-params` works on the assembled URL string (brittle); prefer a values-level serialisation interceptor or bootstrap-time fix | should-fix | open |
| M3 | Response schema coercion/validation unused; entities are raw maps | nice-to-have | open |
| M4 | Throttle/retry must be implemented on both the Martian path and the eutils path — share one policy | should-fix (design note) | **FIXED** |
