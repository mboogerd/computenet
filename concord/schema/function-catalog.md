# Concord function catalog — v1

The closed set of **pure functions** a scenario may reference by id — as a `fn:`
cell param (filter/map/join/group-by/combine-latest) or as a check predicate
(`observations-all-satisfy … fn:`). Semantics are defined once here, implemented
once in the harness (for the batch oracle, W1-B) and once per driver binding
(W1-A). Values are the neutral JSON-shaped `Value` model.

**Keep this under ~20 entries. Growing it is a spec change, not a test
convenience** (CONCORD-PLAN §1.2, P5) — every entry costs each future driver and
oracle.

Parameterised ids carry their arg(s) in the id: `gt(3)`, `mod-eq(2,0)`, `concat(-x)`.

| id | arity | semantic |
|---|---|---|
| `identity` | transform | Returns its input unchanged (pass-through arm). |
| `eq(v)` | predicate | True iff the element equals `v`. |
| `gt(n)` | predicate | True iff the element is numerically greater than `n`. |
| `lt(n)` | predicate | True iff the element is numerically less than `n`. |
| `mod-eq(m,r)` | predicate | True iff `element mod m == r`. |
| `even` | predicate | True iff the element is an even integer. |
| `odd` | predicate | True iff the element is an odd integer. |
| `concat(s)` | transform | Appends string `s` to a string element. |
| `add(n)` | transform | Adds integer `n` to an integer element. |
| `key-of` | transform | Extracts the join/group key from an element (identity when the element is itself the key). |
| `sum` | aggregator | Folds a group / combines inlets by integer addition. |
| `min` | aggregator | The minimum element under natural order. |
| `max` | aggregator | The maximum element under natural order. |
| `count` | aggregator | The cardinality of a group. |

14 entries. Predicates return booleans (usable by `filter` and
`observations-all-satisfy`); transforms map one element to one element (usable by
`map`); aggregators fold a multiset to one value (usable by `group-by` and
`combine-latest`).

## Notes for W1-A / W1-B

- The oracle (W1-B) and the driver (W1-A) must agree on these semantics exactly —
  a divergence surfaces as an `incremental-equals-batch` failure, which is a
  catalog-definition bug, not a code bug.
- `key-of` is deliberately generic: a join over `set-source` of pairs extracts the
  first component; over a scalar stream it is `identity`. The concrete extraction
  a scenario needs is expressed by the source's element shape, not by a new fn.
- `sum` doubles as the `combine-latest` combiner in the glitch-free diamond
  (`22-GF-DIAMOND-01`) and as a `group-by` aggregator — one semantic, two uses.
