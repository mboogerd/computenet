package civictech.testkit.dst.churn

import civictech.testkit.dst.DstWorld
import java.util.WeakHashMap

/**
 * One workload operation a replica **accepted** — applied to its own local state — recorded at
 * the issuing site ([MeshPeer.write]).
 *
 * ## Why acceptance is recorded here and not derived from the folds
 *
 * [CHA3-11] asks for a batch reference "over the operations the surviving replicas actually
 * accepted", and explicitly refuses replica-vs-replica agreement alone. A reference re-derived
 * from the folds under test would be that refused thing wearing a different name: it would agree
 * with them by construction and could not detect a lost, duplicated or invented operation. So
 * the ledger is written where the operation is issued, before any gossip, and the check compares
 * two independently produced values.
 *
 * @property peer the accepting replica's declared name.
 * @property ordinal the write's position in the plan's op script ([ChurnWrite.ordinal]).
 * @property element what a [MeshPayload.SET] write added, or null for a counter write.
 * @property increment what a [MeshPayload.PN_COUNTER] write added, or 0 for a set write.
 */
data class AcceptedOp(
    val peer: String,
    val ordinal: Int,
    val element: String? = null,
    val increment: Long = 0L,
)

/**
 * The accepted-operation ledger of one run, per [DstWorld].
 *
 * A per-graph [WeakHashMap] registry for the reason `doc/dst-rig.md` §1 gives for
 * `LinkControls`, `CrashWitnesses` and [MeshPeers]: an observation the graph produces and a
 * check reads is a per-graph declaration, never a seventh seam on [DstWorld]. Entries are keyed
 * by identity so nothing outlives the run that produced it.
 */
object AcceptedOps {

    private val byWorld = WeakHashMap<DstWorld, MutableList<AcceptedOp>>()

    /** Record one accepted operation. Called from [MeshPeer.write], never from a check. */
    @Synchronized
    fun record(world: DstWorld, op: AcceptedOp) {
        byWorld.getOrPut(world) { mutableListOf() } += op
    }

    /** Every operation accepted on [world], in acceptance order. */
    @Synchronized
    fun of(world: DstWorld): List<AcceptedOp> = byWorld[world]?.toList() ?: emptyList()
}

/**
 * A fold value comparable across a replica's delta stream and the batch reference.
 *
 * Two arms because [BatchReference] covers exactly two payloads — see its KDoc for why the
 * tagged (OR-map) family is deferred rather than approximated here.
 */
sealed interface ReferenceFold {

    /** A one-line rendering for a failure detail. Never a check's *message* — see [ChurnCheckFailure]. */
    fun render(): String

    /**
     * What this fold holds that [other] does not, or null when [other] covers it.
     *
     * Used twice by [ReconvergenceCheck], in both directions: `required.shortfallIn(actual)`
     * is a lost operation, `actual.shortfallIn(permitted)` is an invented one.
     */
    fun shortfallIn(other: ReferenceFold): String?

    /** The OR-set outcome of the accepted adds: which elements are present. */
    data class Elements(val elements: Set<String>) : ReferenceFold {
        override fun render(): String = "${elements.size} element(s) ${elements.sorted()}"

        override fun shortfallIn(other: ReferenceFold): String? {
            val theirs = (other as Elements).elements
            val missing = elements - theirs
            return if (missing.isEmpty()) null else "${missing.size} element(s) ${missing.sorted()}"
        }
    }

    /** The sum of the accepted counter operations. */
    data class Total(val total: Long) : ReferenceFold {
        override fun render(): String = "total $total"

        override fun shortfallIn(other: ReferenceFold): String? {
            val theirs = (other as Total).total
            return if (theirs >= total) null else "${total - theirs} (reference $total, observed $theirs)"
        }
    }
}

/**
 * The batch-mode reference fold ([CHA3-11]): what a mesh's converged state **must** be, computed
 * from the ledger of operations replicas actually accepted rather than from the replicas.
 *
 * ## Scope: set and counter only, by decision (umx.2-D6)
 *
 * [MeshPayload.SET] folds to the OR-set outcome of the accepted adds and [MeshPayload.PN_COUNTER]
 * to their sum, because for both the batch outcome is a function of the *multiset of accepted
 * operations alone*: an add-only OR-set converges to the set of added elements whatever the
 * interleaving, and a PN counter to the sum whatever the interleaving. Nothing here models tags,
 * dots or causal order, and that is the boundary.
 *
 * **Tagged meshes (`OrMapCell`'s dot order) are deliberately NOT covered.** Their batch outcome
 * depends on which writes are *causally* concurrent, so a reference for them is a dot model, not
 * a fold over a flat ledger — and one already exists in the making: ORA2's `DotModel` in
 * `:oracle`, which composes with the same `civictech.cell.verify.ReplicaConvergence` this check
 * composes with. Building a second, weaker dot model here would put two disagreeing references
 * in the repo and make neither trustworthy (feature §9 risk 3). Extending this file to OR-maps
 * means joining ORA2's model, not adding a third arm to [ReferenceFold]. Note also what this
 * file does NOT import: no `civictech.cell.data.op` type appears anywhere in it, so the
 * reference cannot silently become a second copy of the implementation.
 *
 * ## What "surviving" means, and why the reference is a pair of bounds
 *
 * [foldOf] takes the peer names whose accepted operations the caller claims are part of the
 * mesh's state. [ReconvergenceCheck] supplies two such sets:
 *
 *  - **required** — replicas still counted as live membership. Everything they accepted must be
 *    in the converged fold: this is the substance of [CHA3-11], and a lost or invented operation
 *    of a live replica fails the run.
 *  - **permitted** — required plus every replica that has *departed*, orderly or not. Whether a
 *    departing replica's last operations left with it is a race this harness does not control,
 *    so they may appear in the fold and may not.
 *
 * **Why an orderly eviction is in the permitted arm and not the required one — measured, not
 * assumed.** `Replication.evict`'s final push-catch-up is documented as *best-effort*, and the
 * BS-1 seed sweep measured what that costs: at seed 1 the element `peer2-11` was accepted by
 * peer2 at controller step 552 and peer2 was evicted at step 553, and the element never reached
 * the survivors (seed 2 lost two the same way, each accepted within three steps of its
 * replica's departure). Requiring a cleanly departed replica's every operation would therefore
 * assert something the kernel does not promise. What *is* asserted, where the harness controls
 * the race, is the stronger claim: `ReconvergenceCheckTest`'s BS-2 and `:kernel`'s
 * `ChurnReconvergenceTest` both evict a replica whose last write is ~100 controller steps old
 * and assert the survivors hold it — i.e. `[42-REPL-06]`'s handoff, checked as an equality
 * against the reference over *every* peer.
 *
 * When nothing has departed the two sets coincide and the check is a plain equality, which is
 * the case [CHA3-11]'s wording describes. The bounds exist so a plan containing a departure is
 * checked honestly rather than either skipped or asserted into a lie.
 */
class BatchReference private constructor(
    val payload: MeshPayload,
    val ops: List<AcceptedOp>,
) {

    /** The reference fold over the operations accepted by [peers]. */
    fun foldOf(peers: Set<String>): ReferenceFold {
        val mine = ops.filter { it.peer in peers }
        return when (payload) {
            MeshPayload.SET -> ReferenceFold.Elements(mine.mapNotNull { it.element }.toSet())
            MeshPayload.PN_COUNTER -> ReferenceFold.Total(mine.sumOf { it.increment })
        }
    }

    /** Which peers accepted anything at all — for a failure detail, never for a judgement. */
    fun acceptingPeers(): Set<String> = ops.map { it.peer }.toSet()

    override fun toString(): String = "BatchReference($payload, ${ops.size} accepted op(s))"

    companion object {
        /** The reference over everything accepted on [world]. */
        fun of(world: DstWorld, payload: MeshPayload): BatchReference =
            BatchReference(payload, AcceptedOps.of(world))
    }
}
