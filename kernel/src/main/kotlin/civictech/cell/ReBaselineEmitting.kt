package civictech.cell

import java.util.UUID

/**
 * Cells that participate in the RESTART re-baseline (spec 20/21 closing
 * paragraph on Pull; 20/22 §Source identity; 20/24 §Tag continuity; 93 I-22
 * R2/R4). The host invokes [reBaseline] after restoring the freshest
 * available checkpoint, minting fresh outlet epochs, and reactivating —
 * before resuming live traffic — so the cell re-emits its restored state as
 * an ordinary catch-up delta flagged with the dead epochs' source ids.
 *
 * [supersedes] names the outlet source ids this recovery superseded;
 * [supersede] chooses the direction (spec 93 I-22 R4): `true` is
 * push-authoritative (single-writer roots reassert and retract),
 * `false` is pull-merge (derived/replicated cells, forward-only).
 */
interface ReBaselineEmitting {
    fun reBaseline(supersedes: Set<UUID>, supersede: Boolean)
}
