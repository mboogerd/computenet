package civictech.cell.port

import java.util.UUID

/**
 * An outlet's emission-epoch identity (spec 20/22 §Source identity: emission
 * epochs; 93 I-14 Rule S1): the current `sourceId` and its counter high-water.
 * Captured/adopted wholesale on a **preserved-epoch** continuation — drain,
 * migration, a promotion whose state transfer carries it inside the buffered
 * swap window ([civictech.cell.evolve.Promotion.promote]), or **durable
 * recovery** ([durable], KFX-12 below) — so the successor's waves continue the
 * same source lane instead of minting a fresh one. Every other transition
 * (cold start of a volatile cell, RESTART supervision, replica/candidate
 * spawn, a fallback promotion swap) mints a fresh `sourceId` instead of
 * adopting.
 */
data class OutletWaveState(val sourceId: UUID, val highWater: Long) {

    companion object {

        /**
         * The **durable-recovery** epoch of the outlet identified by [outlet]
         * — the `[KFX-12]` decision, in code.
         *
         * ### The decision: durable recovery is a preserved-epoch continuation
         *
         * `[24-DUR-04]` (spec `20/24`) is the highest authority that speaks to
         * this and it is unconditional: *"a recovered instance MUST re-mint the
         * identities the network already observed — set tags and PN source
         * slots derived from the cell's ref (never `randomUUID`)"*. `30/31`
         * agrees on the mechanism — *"Durability subsumes RESTART: a durable
         * cell's RESTART MUST restore its latest snapshot + journal tail
         * instead of the spawn-time checkpoint — same mechanism, richer
         * checkpoint source"* — a **richer** checkpoint source is precisely one
         * that can prove what the plain spawn-time checkpoint cannot.
         *
         * 93 I-14 Rule S1 does **not** contradict this, which is why nothing
         * here is smoothed over. Rule S1 preserves an epoch *"**only** when the
         * framework restores a continuous counter high-water for that outlet"*,
         * and lists RESTART under fresh-epoch with its reason stated inline:
         * *"restores a checkpoint that **cannot prove** the live counter
         * high-water"*. The condition is provability, not the transition's
         * name. I-14's own §4 closing paragraph then names this exact case:
         * *"A durable host that already journals invocations (G-25) MAY
         * additionally persist each outlet's counter high-water … and, on
         * RESTART, restore `sourceId` + `counter` instead of minting a fresh
         * epoch"* — Candidate A, an opt-in available precisely to a host that
         * *can* prove continuity. Durable recovery is that host. So the sources
         * agree, and the fresh-epoch reading applies to the non-durable
         * transitions that genuinely cannot prove a high-water — which keep
         * minting fresh, untouched (`[KFX-14]`).
         *
         * Because the continuation is preserved-epoch, 93 I-14 Rule S5 makes it
         * *invisible to versioning*: the same source lane continues
         * monotonically and downstream needs no re-tracking, so recovery emits
         * **no** `ReBaseline` supersession notice. `[KFX-13]` is `WHERE`-gated
         * on the fresh-epoch arm and is therefore vacuous here.
         *
         * ### Derived on the live path, journaled at the checkpoint
         *
         * The `sourceId` this function returns is *derived from the outlet's
         * ref* — the literal reading of `[24-DUR-04]`, and the same
         * `UUID.nameUUIDFromBytes` derivation
         * [civictech.cell.port.PortRef.of], `SetCell.tagSource` and
         * [civictech.cell.data.delta.MintedTags] already use. Installing it on
         * the live path (below) closes the plane asymmetry the epic names: the
         * tag plane satisfied `[24-DUR-04]`, the wave plane did not. Deriving
         * rather than reading also covers the case a checkpoint cannot: a
         * journal holding frames but *no* checkpoint yet still recovers the
         * identity, because there is nothing to read.
         *
         * The derivation is not, however, the whole restore rule. The
         * **counter** cannot be derived at all, and the epoch actually in force
         * at checkpoint time need not be the derived one, so the host's
         * checkpoint carries the outlet's *whole* epoch — `sourceId` **and**
         * high-water — beside the `Stateful` snapshot (`HostDurability`'s
         * `RECORD_OUTLET_WAVE`, written for every journaled outlet
         * unconditionally), and recovery adopts it, rewinding the outlet to the
         * *checkpoint's* high-water rather than the crash-time one. That is the
         * load-bearing detail. (The recorded `sourceId` is the derived one in
         * the ordinary case; it is carried rather than re-derived because a
         * transition this decision deliberately leaves alone may have rotated
         * the outlet off its derived epoch before the checkpoint: RESTART's
         * `mintFreshEpoch` (`[KFX-14]`) or a drain/migration/promotion adoption
         * (`[KFX-15]`). Re-deriving over such a rotation would pair the derived
         * id with another epoch's counter and re-issue pairs the derived lane
         * already spent — silent effect loss.) Replay is
         * deterministic re-execution from the checkpoint, so rewinding to the
         * checkpoint makes the replayed re-emissions carry *exactly* the
         * `(sourceId, counter)` pairs the pre-crash run emitted — "old ids for
         * old content" (93 I-14, N11×N27) — which is what an `Effectful` sink's
         * restored processed-frontier can match and suppress (`[24-DUR-05]`,
         * `[KFX-09]`). Replaying the tail then advances the counter back
         * through that whole range, so the first *live* post-recovery emission
         * is strictly above every counter the network observed pre-crash
         * (`[KFX-10]`/`[KFX-11]`) — no silent effect loss.
         *
         * Installed on the live path too (at spawn, for journaled cells only),
         * because a recovered identity can only equal an observed identity if
         * the pre-crash run already emitted under it — `[KFX-12]`'s "consistently
         * across journal, checkpoint, and live paths".
         */
        fun durable(outlet: PortRef, highWater: Long = 0L): OutletWaveState = OutletWaveState(
            UUID.nameUUIDFromBytes("wave:${outlet.id}".toByteArray()),
            highWater,
        )
    }
}
