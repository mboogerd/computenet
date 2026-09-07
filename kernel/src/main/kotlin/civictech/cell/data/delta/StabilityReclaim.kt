package civictech.cell.data.delta

import civictech.cell.TagFrontier

/**
 * A replicable, tag-carrying cell whose **reclaimer can be armed with a
 * stability read** (`[KE3-30]`, decision 9sm.6-D1).
 *
 * ## What it is for
 *
 * A tombstoned OR-set can only discard a tag once every open member of its
 * logical id has delivered it — the causal-stability frontier of
 * `Replication.stableFrontier`. The cell cannot compute that: the frontier is
 * a property of the replica *set*, held on the delivered-watermark companion
 * `Replication` owns. And the cell must not go and ask, because
 * `civictech.cell.data` does not depend on `civictech.cell.replication` (the
 * `ArchitectureRatchetTest` baseline has no `data -> replication` edge, and
 * this seam exists so it never acquires one).
 *
 * So the direction is inverted: `Replication.trackDeliveries` — the one place
 * that already knows both the cell and the logical id — *installs* a read,
 * beside the [DeliveryTracking.onDeliver] listener it installs on the same
 * pass. The cell calls it when it is about to reclaim and knows nothing else
 * about where the answer comes from.
 *
 * ## The null contract, which is the safety default
 *
 * **No read installed means no reclamation.** A cell that is not under
 * `Replication` — a plain in-process `SetCell`, a fixture, a single-instance
 * operator cell — never reclaims, and a read that returns `null` is the
 * installer saying "I cannot certify anything right now", which is likewise
 * not a licence to discard. Both cases must leave the cell's tag state exactly
 * as it was. The fail-safe direction is retaining a tombstone (bounded waste)
 * rather than discarding an uncertified one (a resurrection, or with the
 * re-admission fence in place, a permanent divergence).
 *
 * ## Why a new interface rather than a member on [DeliveryTracking]
 *
 * [DeliveryTracking] is implemented by `PnCounterCell` as well as `SetCell`,
 * and a `PnCounter` carries cumulative totals rather than tags — there is
 * nothing there to reclaim. Adding an abstract member to [DeliveryTracking]
 * would conscript it (and every future delivery-tracking cell) into
 * implementing a reclaimer it does not have. Two interfaces, installed
 * independently at the same call site, keep the two capabilities separable.
 *
 * ## What the implementer owes
 *
 * The read is a **foreign call** — it walks another cell's state — so an
 * implementation must not invoke it while holding its own state monitor, and
 * must treat a frontier read a few instructions before the discard as what it
 * is: a conservative under-read. Stability frontiers are monotone per source,
 * so an earlier read is never *higher* than the truth at discard time; it can
 * only decline a discard that a later read would have allowed, which is the
 * safe direction and which the next reclamation pass picks up.
 */
interface StabilityReclaim {
    /**
     * Install [read] as this cell's stability read. Replaces any previously
     * installed read (installation is idempotent — `Replication` re-runs its
     * tracking on a rehome, and a second install must not stack).
     *
     * [read] returns the frontier tags may be reclaimed below, or `null` when
     * nothing can be certified; see the null contract in the interface KDoc.
     */
    fun onStability(read: () -> TagFrontier?)
}

/**
 * A cell that mints tags from a **ref-derived source and a per-instance
 * counter**, and whose counter must therefore be carried across a
 * reincarnation of its [civictech.cell.CellRef] (computenet-uju5,
 * `doc/kernel-lane-findings.md` `## KE3-23-ROWCONTENT`).
 *
 * ## The defect this closes
 *
 * `SetCell.tagSource` is `nameUUIDFromBytes("set-tags:{id}:{instanceId}")` —
 * derived, so a recovered instance replaying its journal re-mints the exact
 * tags the network already observed (M10.1). The *counter* is per instance and
 * restarts at 0 on any construction that does not `restore`. A replica that
 * despawns and returns on the same ref therefore re-mints `(T,1) (T,2) …`
 * **for different elements**, and the delivered lane cannot tell:
 * `DeliveredFrontier.deliver` returns null for a counter at or below the
 * prefix it already holds, so a peer whose row for `T` stands at the
 * pre-departure high-water absorbs the second incarnation's tags as "already
 * covered". Its row does not move — and it already stands above a del-dot the
 * second incarnation has just minted and the peer never applied.
 * `CausalStability.stableFrontier`'s MIN then certifies that dot,
 * `SetCell.compactBelow` reclaims and fences it, and the peer still holding
 * the element live diverges permanently. The contiguity holdback is working
 * correctly; it is the re-used counter space that defeats it.
 *
 * ## Why the continuation is installed rather than computed
 *
 * The cell cannot see its own past incarnations: `tagSource` is derived, so
 * the second instance is indistinguishable from the first from inside. Only
 * the component that owns the ref's lifecycle across a departure —
 * `Replication`, which already retains the delivered-watermark companion
 * across the same departure — knows that a returning ref is a *return*. So the
 * direction is inverted exactly as it is for [StabilityReclaim]: the cell
 * exposes its lane position and accepts a continuation, and `Replication`
 * remembers and re-installs it. `civictech.cell.data` acquires no dependency
 * on `civictech.cell.replication`.
 *
 * ## What it is NOT
 *
 * It is not an incarnation-distinct tag *source*: `tagSource` is untouched, so
 * the journal-replay contract it exists to keep is untouched, and a restored
 * checkpoint continues to re-mint what the network saw. The counter is already
 * snapshot state — `Replication.rebind`'s `carryTagState` continues the same
 * lane through a replicated promotion by restoring the incumbent's snapshot —
 * so this is that continuation extended to the despawn/rejoin path, which had
 * none. No frame, delta or snapshot key changes.
 */
interface TagLaneContinuity {
    /**
     * The highest counter this instance has minted under its ref-derived tag
     * source, or 0 if it has minted nothing.
     */
    fun tagLaneHighWater(): Long

    /**
     * Continue this instance's tag lane strictly above [counter]: the next
     * mint yields a counter greater than [counter]. Never *lowers* the lane —
     * a value at or below the current high-water is ignored, so a restored
     * checkpoint (which already carries the counter) is not disturbed and a
     * re-installation is idempotent.
     */
    fun continueTagLaneAbove(counter: Long)
}
