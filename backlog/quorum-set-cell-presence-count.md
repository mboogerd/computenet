# Idea: `QuorumSetCell` / presence-count over a dynamic set fan-in

> Type: missing primitive / combinator
> Origin: `:demo:slotfinder` — the fixed 3-participant intersect chain (demo-findings **F-4**)
> Relates to: `IntersectSetCell` (binary), `UnionSetCell` (variadic fan-in), `GroupByCell`,
> demo-findings F-2 (bucketing), F-4 (dynamic topology)

## Origin

Slotfinder wires exactly three participants because intersection is only available as a
**binary** cell:

```
alice ─┐
bob   ─┴► pairAB (∩) ─┐
carol ─────────────────┴► common (∩) ─► filtered ─► byDay
```

`IntersectSetCell` is documented as "binary … n-ary by chaining". Adding a fourth participant
means splicing another `IntersectSetCell` into the serving graph at runtime (F-4). Meanwhile
`UnionSetCell` already scales to N sources on a single fan-in inlet — so the asymmetry is
purely that intersection needs to know *how many* sources currently assert an element, and the
binary form hides that count in its topology.

While testing I also kept wanting a view the current pipeline **cannot express**: "slots that
all-but-one participant share" (the near-miss — *if only Carol freed Tue-15, everyone could
meet*). That is also a statement about the per-element source count.

## What it is

A single cell that consumes a **dynamic fan-in of `SetDelta<E>` streams** (one link per source,
exactly like `UnionSetCell`'s inlet) and maintains, per element, **the number of distinct live
source links currently asserting it** — the *presence count*. Two output shapes:

- **Primitive:** `PresenceCountCell<E>` → emits `MapDelta<E, Int>` (effective-only: only when an
  element's live-source count changes).
- **Convenience:** `QuorumSetCell<E>(threshold: (liveSources: Int) -> Int)` → emits
  `SetDelta<E>` of the elements whose presence count meets the threshold.

The insight that makes this worth a primitive: **intersection, union, majority, quorum, and
near-miss are all one operator** parameterised by a threshold on the presence count:

| View | threshold(n) | today |
|---|---|---|
| union (any) | `1` | `UnionSetCell` |
| intersection (all) | `n` | chained `IntersectSetCell` |
| majority | `n/2 + 1` | — (impossible) |
| k-of-n quorum | `k` | — (impossible) |
| near-miss (all-but-one) | `n - 1` | — (impossible) |

## Why it fits the framework

- **It is incremental and tag-native.** `GlitchFreeCell` already keeps per-link state keyed by
  `Link` UUID and folds `EdgeOpen`/`EdgeClose` into a live-edge frontier; `TagState` already
  folds a source's `SetDelta` into live membership. `PresenceCountCell` is those two mechanisms
  composed: per-link `TagState`, and a count of links whose `TagState` holds `E`. Nothing new in
  the consistency model.
- **It unifies existing cells rather than adding a parallel one.** `IntersectSetCell` and
  `UnionSetCell` become documented special cases; the kernel gets smaller in concept even as it
  gains capability.
- **It resolves F-4 without live topology surgery.** A participant joining/leaving is just a
  link opening/closing on the fan-in; `EdgeClose` drops that link's lane and recomputes counts
  for the affected elements. No promotion-swap window needed for the common case.
- **Effective-only emission** (spec 21) is the natural contract: emit only on count-band change,
  so tag churn that doesn't move a count is absorbed — same discipline the current cells use.

## Solution sketch

```kotlin
interface PresenceCountApi<E> {
    val inlet: Serve<Propagate<SetDelta<E>>>   // fan-in: one link per source
    val outlet: Subscribe<Propagate<MapDelta<E, Int>>>
}

class PresenceCountCell<E> : PresenceCountApi<E>, Cell, Stateful {
    // per source-link membership; link identity from EdgeOpen/EdgeClose (as GlitchFreeCell does)
    private val perLink = mutableMapOf<UUID /*linkId*/, TagState<E>>()
    private val count   = mutableMapOf<E, Int>()   // last emitted live-source count

    // on delta from link L: fold into perLink[L]; for each affected E recompute
    //   newCount = perLink.values.count { E in it }; if newCount != count[E] emit put/removal
    // on EdgeClose(L): drop perLink[L]; recompute affected E; group-death removal at count 0
}

// convenience filter on top — reuses FilterCell-style effective emission
fun quorum(threshold: (Int) -> Int): /* MapDelta<E,Int> -> SetDelta<E> */ …
```

`QuorumSetCell` composes `PresenceCountCell` with a threshold filter that also needs the current
**live-source count `n`** (from the open-edge frontier) so `n`, `n-1`, `n/2+1` are expressible.

## Inputs / outputs

- **Input:** N links into one fan-in inlet, each carrying `SetDelta<E>` under its own tag lane;
  links open/close over the cell's life.
- **Output (primitive):** `MapDelta<E, Int>` — `put(E, k)` when E's live-source count becomes k;
  `removal(E)` when it drops to 0. Effective-only.
- **Output (quorum):** `SetDelta<E>` — adds when count first meets threshold, dels when it drops
  below, with tags such that downstream membership tracks exactly (same contract as
  `IntersectSetCell`).

## Acceptance criteria

- [ ] Seeded incremental-vs-batch equivalence (mirroring `SlotFinderPipelineTest`): after random
      per-source add/remove churn **and** random link open/close, the emitted counts equal a
      batch recompute (`elements.associateWith { e -> liveLinks.count { e in it } }`) on every seed.
- [ ] `QuorumSetCell(threshold = { n -> n })` is observationally equal to a chained
      `IntersectSetCell` over the same sources; `{ 1 }` equals `UnionSetCell` membership.
- [ ] Adding a 4th source link to a running cell yields the correct new intersection with **no
      recompute of unaffected elements** and no graph re-spawn (closes F-4 for the static-N case).
- [ ] Removing a source link (`EdgeClose`) retracts exactly that source's sole contributions and
      raises previously-below-threshold near-miss elements as appropriate.
- [ ] Effective-only: pure tag churn from one source that leaves the count unchanged emits nothing.
- [ ] `Stateful` snapshot/restore round-trips per-link state and re-emits a delta-from-empty to a
      late-joining outlet link (late-join catch-up, G-22, as `IntersectSetCell` does today).
- [ ] Slotfinder rewired onto one `QuorumSetCell` drops the hard-coded `pairAB`/`common` chain and
      gains a "near-miss (all but one)" panel with no new operator.
