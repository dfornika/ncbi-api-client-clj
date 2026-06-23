# ClojureScript & Async Spike Notes

Findings from a spike branch (`cljs-spike`) exploring portable CLJ+CLJS support
and async API calls via `core.async`.

## What We Explored

1. **Portable `datafy`/`nav` metadata** using `.cljc` reader conditionals
2. **Portable taxonomy function** that compiles and runs on both CLJ and CLJS
3. **Async fetch via `core.async`** channels, bridging `CompletableFuture` on the JVM

## What Worked

### datafy/nav is portable via reader conditionals

The protocol metadata keys differ between platforms but the extension mechanism
is the same (`:extend-via-metadata true` on both `IDatafy` and `INavigable`):

```clojure
;; nav_keys.cljc
(defn datafy-meta-key []
  #?(:clj  `clojure.core.protocols/datafy
     :cljs `cljs.core/-datafy))

(defn nav-meta-key []
  #?(:clj  `clojure.core.protocols/nav
     :cljs `cljs.core/-nav))
```

A single `with-datafy-nav` helper attaches both protocols via `with-meta`,
working identically on CLJ and CLJS.

### Portable fetch compiles on both targets

A `.cljc` file in `src/cross/cljc/` with the taxonomy function compiled
successfully under both `clojure -M:cross` (JVM) and `cljs.build.api/build`
(Node target). The `:cross` deps.edn alias adds the path and pulls in
`clojurescript` + `martian-cljs-http`.

### Async fetch via core.async channels

`martian-hato` supports async mode (`default-interceptors-async` +
`perform-request-async`), which returns a `CompletableFuture`. We bridged
this to `core.async` with a small helper:

```clojure
(defn- response->ch [response]
  (let [ch (a/chan 1)]
    #?(:clj  (if (future? response)
               (future (a/>!! ch @response) (a/close! ch))
               (do (a/>!! ch response) (a/close! ch)))
       :cljs (a/put! ch response (fn [_] (a/close! ch))))
    ch))
```

`fetch-async` and `taxonomy-async` use `a/go` blocks and `a/<!` to consume
the channel, returning a channel to the caller.

## Remaining Challenges

### Client creation is platform-specific

`martian/bootstrap-openapi` loads and parses the YAML spec, which relies on
JVM-specific YAML parsing. A CLJS client would need either:

- A JSON-converted spec served over HTTP
- A macro that parses the spec at compile time and emits the route data
- A different Martian bootstrap path for CLJS

### Nav stays synchronous

`nav` is a synchronous protocol — calling `(nav entity :ncbi.nav/assemblies
:deferred)` triggers a blocking API call. This works fine on the JVM with a
sync Martian client, but creates problems:

- **Async JVM client**: `nav` can't return a channel/future; it would need to
  block (`a/<!!`), defeating the purpose of async
- **CLJS**: Blocking is impossible in a single-threaded JS runtime; `nav` would
  need to return a channel, but callers expect a value

Possible approaches:
1. Keep `nav` sync on JVM, offer separate `nav-async` that returns a channel
2. Have `nav` return a "deferred entity" placeholder that resolves on `datafy`
3. Accept that interactive REPL nav is JVM-only; CLJS uses explicit async functions

### Martian backend differs

- JVM: `martian-hato` (sync and async modes)
- CLJS: `martian-cljs-http` (inherently async, returns channels)

The fetch layer would need a protocol or multimethod to abstract over these.

## Design Decisions for Full CLJS Support

If we pursue this further, the key decisions are:

1. **Spec loading**: compile-time macro vs. runtime JSON fetch vs. pre-baked routes
2. **Async model**: core.async everywhere vs. sync JVM + async CLJS vs. promises
3. **Nav semantics**: sync-only (JVM REPL) vs. async nav (breaking protocol contract)
4. **Code organization**: single `.cljc` codebase vs. shared core + platform shims

The simplest viable path is probably: keep the existing sync JVM implementation
as the primary target, add explicit async API functions (non-nav) for CLJS use,
and accept that the interactive `datafy`/`nav` REPL experience is JVM-only.

## CORS

The NCBI API reflects the `Origin` header, so browser-based CLJS clients can
make direct API calls without a proxy. This makes a CLJS frontend feasible
from a network perspective.

## Files from the Spike

These lived on the `cljs-spike` branch under `src/cross/cljc/`:

- `ncbi_api_client/nav_keys.cljc` — portable datafy/nav metadata helpers
- `ncbi_api_client/portable.cljc` — portable taxonomy with sync + async fetch

The `:cross` alias in `deps.edn` added `src/cross/cljc` to paths and pulled
in `clojurescript` and `martian-cljs-http`.
