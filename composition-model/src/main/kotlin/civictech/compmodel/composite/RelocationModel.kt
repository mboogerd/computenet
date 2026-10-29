package civictech.compmodel.composite

import civictech.compmodel.check.Spec
import civictech.compmodel.check.Transition

/**
 * Relocation, COH §5.1 H4 (six steps, the crash table): the source host coordinates; the
 * residency entry's `Departing(tx)` is the source fence; the target prepares without
 * activating; `Departed(tx)` is the commit point. A durable term (its state lives in its
 * stream). A crash of either host is injected between every pair of steps, and an abort
 * before the decision. A sender holds each frame until a live holder accepts it (COH §3.6).
 *
 * Invariants: never two live admitting holders ([33-MOVE-01], H4 "Two live holders must be
 * impossible across a crash of either host at any point"); every accepted frame is in the
 * state of the holder of record; no frame applied by a holder whose state is then discarded.
 */
data class RelocationVariant(
    /** Control: step 2 (source fence) skipped; a re-created source comes back live. */
    val noSourceFence: Boolean = false,
    /** Control: the target activates at PREPARE instead of at COMMIT. */
    val activateOnPrepare: Boolean = false,
)

enum class SrcEntry { LIVE, DEPARTING, DEPARTED, GONE }
enum class TgtEntry { NONE, PREPARED, COMMITTED }

data class RelocSt(
    val srcUp: Boolean = true,
    val srcEntry: SrcEntry = SrcEntry.LIVE,
    val srcDrained: Boolean = false,
    val srcActive: Boolean = true,
    val srcState: Set<Int> = emptySet(),
    val capsule: Set<Int>? = null,
    val tgtUp: Boolean = true,
    val tgtEntry: TgtEntry = TgtEntry.NONE,
    val tgtStaged: Set<Int>? = null,
    val tgtActive: Boolean = false,
    val tgtState: Set<Int> = emptySet(),
    val senderRetained: List<Int>,
    val accepted: Set<Int> = emptySet(),
    val inTransfer: Boolean = false,
    val transferBudget: Int = 1,
    val srcCrash: Int = 1,
    val tgtCrash: Int = 1,
    val aborts: Int = 1,
)

class RelocationModel(val variant: RelocationVariant = RelocationVariant()) : Spec<RelocSt> {
    override val name = "relocation[$variant]"
    override fun initial() = RelocSt(senderRetained = listOf(1, 2))

    private fun srcAdmits(s: RelocSt) = s.srcUp && s.srcActive && !s.srcDrained && (s.srcEntry == SrcEntry.LIVE || variant.noSourceFence && s.srcEntry != SrcEntry.GONE)
    private fun tgtAdmits(s: RelocSt) = s.tgtUp && s.tgtActive

    override fun next(s: RelocSt): List<Transition<RelocSt>> {
        val out = ArrayList<Transition<RelocSt>>()
        fun add(l: String, t: RelocSt, p: Boolean = true) = out.add(Transition(l, t, p))
        val f = s.senderRetained.firstOrNull()
        if (f != null) {
            if (srcAdmits(s)) add("source accepts frame $f", s.copy(srcState = s.srcState + f, senderRetained = s.senderRetained.drop(1), accepted = s.accepted + f))
            if (tgtAdmits(s)) add("target accepts frame $f", s.copy(tgtState = s.tgtState + f, senderRetained = s.senderRetained.drop(1), accepted = s.accepted + f))
        }
        // The protocol, driven by the (source) coordinator.
        if (s.transferBudget > 0 && s.srcUp && s.srcEntry == SrcEntry.LIVE && !s.inTransfer) {
            add("1. DRAIN + CAPTURE(relocate, tx): admission fenced, capsule STABLE", s.copy(srcDrained = true, capsule = s.srcState, inTransfer = true, transferBudget = 0), false)
        }
        if (s.inTransfer && s.srcUp) {
            if (s.srcEntry == SrcEntry.LIVE && s.capsule != null) {
                add("2. source fence: residency entry Departing(tx), synced",
                    if (variant.noSourceFence) s.copy(srcEntry = SrcEntry.DEPARTING, srcDrained = true) else s.copy(srcEntry = SrcEntry.DEPARTING))
            }
            if (s.srcEntry == SrcEntry.DEPARTING && s.tgtUp && s.tgtEntry == TgtEntry.NONE) {
                add("3. target PREPARE from capsule (not activated), logs Prepared(tx)",
                    s.copy(tgtEntry = TgtEntry.PREPARED, tgtStaged = s.capsule, tgtActive = variant.activateOnPrepare, tgtState = if (variant.activateOnPrepare) s.capsule!! else s.tgtState))
            }
            if (s.srcEntry == SrcEntry.DEPARTING && s.tgtEntry == TgtEntry.PREPARED) {
                add("4. source logs Departed(tx): commit point", s.copy(srcEntry = SrcEntry.DEPARTED))
            }
            if ((s.srcEntry == SrcEntry.LIVE || s.srcEntry == SrcEntry.DEPARTING) && s.aborts > 0) {
                add("ABORT(tx) before the decision: target discards, source resumes in place",
                    s.copy(aborts = 0, srcEntry = SrcEntry.LIVE, srcDrained = false, srcActive = true, inTransfer = false,
                        tgtEntry = if (s.tgtUp) TgtEntry.NONE else s.tgtEntry, tgtStaged = if (s.tgtUp) null else s.tgtStaged,
                        tgtActive = if (s.tgtUp) false else s.tgtActive, tgtState = if (s.tgtUp) emptySet() else s.tgtState), false)
            }
            if (s.srcEntry == SrcEntry.DEPARTED && s.tgtUp && s.tgtEntry == TgtEntry.PREPARED) {
                add("5. COMMIT(tx): target logs Committed, activates, publishes route v",
                    s.copy(tgtEntry = TgtEntry.COMMITTED, tgtActive = true, tgtState = if (variant.activateOnPrepare) s.tgtState else s.tgtStaged!!))
            }
            if (s.srcEntry == SrcEntry.DEPARTED && s.tgtEntry == TgtEntry.COMMITTED) {
                add("6. retire source instance, entry and stream", s.copy(srcEntry = SrcEntry.GONE, srcActive = false, inTransfer = false))
            }
        }
        // A target prepared for a tx the source no longer knows: it asks, unknown tx = abort.
        if (s.tgtUp && s.tgtEntry == TgtEntry.PREPARED && s.srcUp && !s.inTransfer && s.srcEntry == SrcEntry.LIVE) {
            add("target asks source about tx: unknown, so abort", s.copy(tgtEntry = TgtEntry.NONE, tgtStaged = null, tgtActive = false, tgtState = emptySet()))
        }
        // Crashes and re-creation (H3), per the H4 crash table.
        if (s.srcUp && s.srcCrash > 0 && s.srcEntry != SrcEntry.GONE) add("CRASH source host", s.copy(srcUp = false, srcActive = false, srcCrash = 0), false)
        if (!s.srcUp) {
            when (s.srcEntry) {
                SrcEntry.LIVE -> add("re-create source normally (no fence)", s.copy(srcUp = true, srcActive = true, srcDrained = false, inTransfer = false))
                SrcEntry.DEPARTING ->
                    if (variant.noSourceFence) add("re-create source (no fence recorded): live", s.copy(srcUp = true, srcActive = true, srcDrained = false, inTransfer = false))
                    else add("re-create source as fenced stub; it resumes the transfer", s.copy(srcUp = true, srcActive = false, inTransfer = true))
                SrcEntry.DEPARTED ->
                    if (variant.noSourceFence) add("re-create source (no fence recorded): live", s.copy(srcUp = true, srcActive = true, srcDrained = false, srcEntry = SrcEntry.LIVE, inTransfer = false))
                    else add("re-create source as fenced stub; re-sends COMMIT", s.copy(srcUp = true, srcActive = false, inTransfer = true))
                SrcEntry.GONE -> {}
            }
        }
        if (s.tgtUp && s.tgtCrash > 0 && s.tgtEntry != TgtEntry.NONE) add("CRASH target host", s.copy(tgtUp = false, tgtActive = false, tgtCrash = 0), false)
        if (!s.tgtUp) {
            add("re-create target (${s.tgtEntry})", s.copy(tgtUp = true, tgtActive = s.tgtEntry == TgtEntry.COMMITTED || (variant.activateOnPrepare && s.tgtEntry == TgtEntry.PREPARED)))
        }
        return out
    }

    override fun invariants(s: RelocSt): List<String> {
        val v = ArrayList<String>()
        if (srcAdmits(s) && tgtAdmits(s)) v.add("two live holders: source and target both admit")
        val ofRecord = if (s.tgtEntry == TgtEntry.COMMITTED) s.tgtState else s.srcState
        for (f in s.accepted) if (f !in ofRecord) v.add("I3 frame $f accepted but not in the holder of record's state (src=${s.srcState}, tgt=${s.tgtState}, tgtEntry=${s.tgtEntry})")
        return v
    }
}
