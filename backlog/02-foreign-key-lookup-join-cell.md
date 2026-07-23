# LookupJoinCell — foreign-key enrichment join over map streams

**Type:** missing combinator (new — not in `demo-findings.md`)
**Origin:** `:demo:skillmatch` qualification view (`matched == required`).

## Origin of the idea

Qualification asks, per `(candidate, job)` pair: does the pair's match count
equal that **job's** required-skill count? The two facts live at different
grains:

- `matchCounts : MapDelta<CandidateJob, Long>` — keyed by the pair.
- `required    : MapDelta<String, Long>` — keyed by the job.

`JoinCell` needs the **same** key type on both sides, so it cannot express this.
`CombineLatestCell` (backlog 01) also assumes a shared key. The natural
relational operation is a **foreign-key join**: many pairs reference one job.
Today the demo does the lookup at the edge:

```kotlin
matchCounts.entries.map { (cj, matched) ->
    val need = required[cj.job] ?: 0L      // <-- FK lookup by cj.job
    Qualified(cj, matched, need, matched == need && need > 0)
}
```

That is a per-snapshot re-scan of a dimension table — the classic streaming
"enrich a fact stream with a dimension" pattern, absent from the operator suite.

## What it is

A cell that joins a **fact** stream keyed by `K` against a **dimension** stream
keyed by `J`, where each fact projects to a dimension key via `fk: K -> J` (or
`fk: (K, V) -> J`). It emits the fact enriched with the current dimension value.

```kotlin
class LookupJoinCell<K, V, J, D, R>(
    private val fk: (K) -> J,
    private val combine: (K, V, D?) -> R?,   // D? ⇒ left-outer; null ⇒ drop
)
interface LookupJoinApi<K, V, J, D, R> {
    val fact:      Serve<Propagate<MapDelta<K, V>>>
    val dimension: Serve<Propagate<MapDelta<J, D>>>
    val outlet:    Subscribe<Propagate<MapDelta<K, R>>>
}
```

Crucially it is **reactive on both sides**: a change to one dimension row
(`required[backend]`) must re-emit **every** fact that references it (all
`(_, backend)` pairs), not just facts arriving afterwards. That fan-out —
one dimension delta → N fact re-emissions — is the part an app hand-roll gets
wrong, and the part worth owning in the kernel.

## Why it is a proper fit

- It completes the join family: `JoinSetCell` (set equi-join), `JoinCell`
  (same-key map join), `SemiJoinCell` (existence), and now **FK/dimension
  join** (referential lookup) — a named relational operator with clear
  semantics rather than an app idiom.
- It reuses the framework's spine: `MapDelta` in/out, effective-only emission,
  wave grouping (one dimension delta ⇒ one output `MapDelta` covering all
  dependent facts), `Stateful`, G-22 late-join, G-23 convergence inheritance.
- It needs a **reverse index** `J -> Set<K>` internally — a reusable piece of
  machinery the kernel is better positioned to get right (and test) than each
  demo.

## Solution sketch

State: `facts: Map<K, V>`, `dims: Map<J, D>`, `byDim: Map<J, MutableSet<K>>`
(reverse index), `emitted: Map<K, R>`.
- Fact put `(k,v)`: index `k` under `fk(k)`; recompute `combine(k, v, dims[fk(k)])`;
  diff vs `emitted[k]`.
- Fact removal `k`: de-index; remove from output.
- Dimension put/removal `j`: for **each** `k in byDim[j]`, recompute and diff.
  Emit all changed `k` as one `MapDelta` under the dimension's wave id.

`combine`'s `D?` gives left-outer (fact survives a missing dimension row);
returning `null` filters (e.g. the demo's `need > 0` guard).

## Expected inputs / outputs

fact `matchCounts`, dimension `required`, `fk = { it.job }`,
`combine = { cj, m, need -> Qualified(cj, m, need ?: 0, m == (need ?: -1)) }`:

| event | outlet |
|---|---|
| fact put `{(ada,backend):1}`, dim `{backend:2}` | `puts {(ada,backend):1/2 ¬qual}` |
| fact put `{(ada,backend):2}` | `puts {(ada,backend):2/2 qual}` |
| dim put `{backend:3}` (job adds a skill) | `puts {(ada,backend):2/3 ¬qual}` — **fact re-emitted from a dim change** |
| dim removal `{backend}` | `puts {(ada,backend):2/0 …}` per outer rule |

## Acceptance criteria

- A dimension-row change re-emits **all** referencing facts (verified with ≥2
  facts sharing one FK), as a single wave-grouped `MapDelta`.
- Left-outer: a fact with no matching dimension row still emits (`D = null`);
  `combine → null` filters it out.
- Effective-only, group-death, `Stateful` snapshot of facts+dims+reverse index,
  G-22 late-join — all as per the join family.
- Seeded incremental-vs-batch test: batch = for each live fact, look up the
  final dimension map; equality on every seed.
- Refactor proof: skillmatch qualification becomes
  `LookupJoinCell(fk = { it.job })` feeding a materialized view, and the hub
  stops doing the `required[cj.job]` lookup.
