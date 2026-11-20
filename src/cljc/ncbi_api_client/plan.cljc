(ns ncbi-api-client.plan
  "Plans: a request, described as data, and what to do with its answer.

  `then` takes the decoded response body and returns either a value or
  another plan, so a step that takes several requests (esearch then
  esummary, elink then a Datasets fetch) is a plan that returns plans.

  Nothing here makes a request. Each platform has a runner that does:
  `ncbi-api-client.runner/run` on the JVM (blocking), and a promise-based
  one in the browser.")

(defrecord Plan [request then])

(defn plan
  "A plan to make `request` and give its body to `then` (default: return
  the body as it is)."
  ([request] (->Plan request identity))
  ([request then] (->Plan request then)))

(defn plan?
  "Whether `x` is a plan (rather than a value)."
  [x]
  (instance? Plan x))

(defn then
  "A plan that does `x`, then gives its value to `f`. `x` can be a plan or
  a value (then `f` is called with it now), and `f` can return either."
  [x f]
  (if (plan? x)
    (->Plan (:request x) (fn [body] (then ((:then x) body) f)))
    (f x)))
