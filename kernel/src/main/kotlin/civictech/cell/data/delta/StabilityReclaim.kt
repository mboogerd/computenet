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
