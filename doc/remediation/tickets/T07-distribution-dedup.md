# T07 — Distribution dedup

**Phase 2 · parallel with T08/T09/T10/T12 · fresh session · Sonnet 5**
**Prereq**: Phase 1 merged. Rebase awareness: T05 edited
`QuorumSetCell.kt` (the ack path) — this ticket edits its ledger fields;
work from merged main.
**Write scope**: `kernel/src/main/kotlin/civictech/cell/{replication,wire,data/op,data/delta}`,
`demo/shopping/src/main/kotlin/civictech/demo/Main.kt`,
`demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt`, matching
tests.
**Do not touch**: `partition/` beyond reading, `data/op` files other than
`QuorumSetCell`/`JoinLedger`, `graph/`, `observe/`.

## Problem

DRY audit (verified 2026-07-27 at `742f7ca`) — duplicated *semantic* logic
that has already measurably drifted:

1. **The gossip mesh is implemented twice, and the copies have diverged
   twice (critical).** `Replication` and `SingleWriterReplication` both
   implement: keyed `(from,to)` link table; on peer announce, re-fire
   catch-up if linked, else build routed proxy + `streamTo` and record.
   - `Replication.kt:222` (`onPublish → linkOut :461 → maybeLink :476`,
     table `linked`) vs `SingleWriterReplication.kt:166`
     (`onPublish → onPeerPublished :232 → shipTo :239`, table `shipped`
     :159). Identical re-fire core: `Replication.kt:496` vs
     `SingleWriterReplication.kt:244`.
   - **Divergence A — interest gating missing from SWR.**
     `Replication.kt:485-486` gates on
     `registry.interestOf(local.ref).overlaps(targetInterest)` and applies
     the CP-D2 per-emission interest slice; `SingleWriterReplication.kt` has
     zero occurrences of `interest` outside a comment. A partial-interest
     follower receives the leader's **whole** delta stream — single-writer
     replication silently violates the partitioning contract that
     "`Interest` is the one knob".
   - **Divergence B — unpublish cleanup missing from SWR.**
     `Replication.kt:227` registers `registry.onUnpublish { linked.remove }`
     (the G-45 fix); SWR never removes from `shipped`, so a departed
     follower leaves a stale `Link`, and its re-announce re-fires catch-up
     over the dead link instead of rebuilding.
2. **The shopping/exchange peer-bootstrap scaffold is a copy-paste family
   with a fix that already had to land three times (high).** Duplicated
   between `demo/shopping/.../Main.kt` and `demo/exchange/.../Main.kt`:
   `sealed interface Wire { Listen; Dial }` (:40-43 / :59-62), role
   when-blocks (:56-60 / :81-84), `Peering.Side` + transport setup
   (:134-139 / :190-194), `routedDelta` (:192-193 / :235-236), the
   re-announce chaining rule
   `registry.onPublish { chained[ref]?.let { fireLinked } }` (:154-160 /
   :206-211), and the `--listen/--peer/--journal` arg parsing (:239-255 /
   :281-297). The comments are the drift evidence: shopping `Main.kt:156-158`
   — *"PN-9: fire the full on-link multicast … Same fix as exchange/Main.kt
   and Replication.maybeLink."* One kernel semantics change required three
   hand-edits; the re-announce rule is **kernel semantics re-expressed in
   application code**.
3. **`QuorumSetCell` reimplements `AdvertisedLedger` verbatim (medium).**
   `JoinLedger`/`AdvertisedLedger` (`data/op/JoinLedger.kt:63-84`) is the
   extracted advertise-on-entry / delete-exactly-advertised-on-exit /
   snapshot-restore state machine; `IntersectSetCell.kt:43` adopts it.
   `QuorumSetCell` hand-rolls the identical machine: `advertised` map (:57 vs
   `JoinLedger.kt:64`), enter/exit inlined (:96-102 vs :69-76), `asDelta`
   re-derived (:77 vs :40), snapshot/restore re-derived (:107,110-115 vs
   :78-84). Its own KDoc (:42) declares the shared contract while declining
   the shared implementation. A tag-hygiene fix to `AdvertisedLedger` (spec
   21: exit must delete exactly what entry advertised) would reach Intersect
   and not Quorum.
4. **Pointwise-max lattice merge written three times with two different
   identity elements; `TagState.applyReBaseline` re-inlines `apply`'s del
   fold (medium).**
   - `PnCounterDelta.kt:27-28 mergeMax` (identity `0L`) vs
     `WatermarkDelta.kt:97-98 mergeSuspend` and `:100-110 mergeRows`
     (identity `Long.MIN_VALUE`). Undocumented, invisible split — the next
     lattice delta copies whichever it sees first.
   - `TagState.kt:99-107` is a character-level near-copy of `:52-60`
     differing only in the killed-tag accumulation. (The *add*-path
     divergence at :91-98 is justified and documented — leave it.) A fix to
     del-folding (e.g. the tombstone gap flagged in the `ponytail:` note at
     :14-17) would land in one branch only.

## Solution

### A. SWR minimal-correctness patch (finding 1) — NOT the full extraction

Deliberately chosen over a shared `MeshLinker` abstraction (deferred — see
COVERAGE.md): patch the two divergences and pin them with shared tests.

1. In `shipTo`/`onPeerPublished`: add the interest-overlap gate and the
   per-emission CP-D2 slice, mirroring `Replication.kt:485-486` and the
   `sliceTo` wrapping at :509-510 (post-T05, a null slice is
   counted/refused — reuse that path).
2. Register `registry.onUnpublish { shipped.remove(...) }` mirroring
   `Replication.kt:227`, so a departed follower's re-announce rebuilds.
3. Tests (in `SingleWriterReplicationTest`): (a) a disjoint-interest
   follower receives nothing (and the refusal is accounted); (b) a
   partial-interest follower receives only its slice; (c) depart + re-announce
   rebuilds the link and catch-up converges. Add the mirror of (a) to
   `Replication`'s test if absent, so the *pairing* is enforced: both meshes
   reject a disjoint-interest peer.

### B. Promote the re-announce chaining rule into the kernel (finding 2)

1. Add `Peering.chainOnReannounce(registry, chained: Map<CellRef, Link>)`
   (or fold into `Peering.Side` — pick whichever reads as *declaring what is
   chained* rather than *how re-announce works*; look at both demo call
   sites first). It encapsulates the
   `onPublish { chained[ref]?.let { (outlet as FanOutlet).linking.fireLinked(it) } }`
   pattern including the PN-9 full-multicast semantics.
2. Migrate both demo `Main.kt`s onto it; delete the three explanatory
   drift-comments (the mechanism now lives where the semantics live).
3. Leave the rest of the demo scaffold (Wire ADT, arg parsing) as-is — T12
   handles cosmetic demo dedup; this ticket only removes the *semantic* copy.
4. Test: a kernel-level test for `chainOnReannounce` (late-announce → chained
   link re-fires catch-up), so the next kernel catch-up change breaks a
   kernel test, not two demos.

### C. `QuorumSetCell` onto `AdvertisedLedger` (finding 3)

Replace the `advertised` map with `AdvertisedLedger<E>`; `evaluate` uses
`ledger.enter(e) { lanes.tags(e) }` / `ledger.exit(e)`; delta and
snapshot/restore delegate. The snapshot payload shape changes — run the
durability round-trip test for Quorum (add one if missing: snapshot →
restore → identical membership + tags). Keep T05's ack behavior intact.

### D. Shared lattice folds (finding 4)

1. New `data/delta/Lattices.kt`:
   `internal fun <K> mergeMax(a: Map<K, Long>, b: Map<K, Long>, identity: Long): Map<K, Long>`;
   route `PnCounterDelta.mergeMax` (identity `0L`) and `WatermarkDelta`'s two
   folds (identity `Long.MIN_VALUE`) through it — the identity becomes a
   declared parameter with a one-line comment on why they differ.
2. `TagState`: extract
   `private fun foldDels(delta, into, accumulateKilled: Boolean)` (or two
   small lambdas) so `apply` and `applyReBaseline` share the del fold; do
   **not** touch the documented add-path divergence.
3. Existing delta/TagState tests pin behavior; run the full kernel suite.

## Verification

```bash
./gradlew :kernel:test
./gradlew test
./gradlew :concord:test -Pconcord.profiles=core,dist,dur
./gradlew :demo:shopping:test :demo:exchange:test
```

## Report

Per finding: fix + tests. For A, state explicitly what a partial-interest
follower now receives. For B, name the kernel test that replaces the
three-site comment chain.
