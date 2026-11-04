package civictech.compmodel.composite

import civictech.compmodel.check.Spec
import civictech.compmodel.check.Transition

/**
 * Replica set effect authority and failover: COH §3.1 ("Effect authority: only proven
 * dispositions transfer"), PLP §3.2 "Replica-set lanes" and §2 (a follower's
 * authority-suppressed delivery advances `received`, never `disposed`).
 *
 * Two instances of one `Effectful` term, leader L and follower F, Total interest. Each
 * logical delivery (one set-lane position) is a forwarding fan-out to both instances. L acts,
 * advances `disposed`, writes its X record (the [24-DUR-09] window), and only then (STABLE)
 * publishes its disposed frontier on the set's watermark channel. F delivers suppressed
 * (advances `received`), retains every frame above the folded frontier, and releases
 * retention as the fold passes. L may die at any point (it is never re-created: the
 * no-automatic-failover default, a designator declares it dead); management then designates
 * F, which — if its retention is gapless above the fold — acts in lane order on every
 * retained frame above the folded frontier.
 *
 * Declared ceiling checked (COH §3.1, §6 "Replica failover"; M9 option (b)): at most one
 * duplicate per position the old leader acted on but had not published; none at an exact
 * witness. No omission, ever.
 */
data class ReplicaVariant(
    /** COH §3.1 posture 2(a): the destination rejects a position already applied under any epoch. */
    val exactWitness: Boolean = false,
    /** Control: a follower's suppressed delivery advances `disposed` (acted-vs-received confused). */
    val followerAdvancesDisposed: Boolean = false,
    /** Control: followers do not retain suppressed frames. */
    val noRetention: Boolean = false,
)

data class ReplicaSt(
    val toL: List<Int>,
    val toF: List<Int>,
    val lAlive: Boolean = true,
    val lDisposedMem: Int = 0,
    val lDisposedStable: Int = 0,
    val lPending: Int? = null,
    val lPublished: Int = 0,
    val channel: List<Int> = emptyList(),
    val fReceived: Int = 0,
    val fDisposed: Int = 0,
    val fFolded: Int = 0,
    val fRetained: List<Int> = emptyList(),
    val fLeader: Boolean = false,
    val acts: Map<Int, Int> = emptyMap(),
    val witnessApplied: Set<Int> = emptySet(),
    /** Ghost: positions L acted on that were unpublished (unfolded at F) at failover. */
    val unpublishedAtFailover: Set<Int> = emptySet(),
    val lActed: Set<Int> = emptySet(),
    val ceiling: Map<Int, Int> = emptyMap(),
    val deaths: Int = 1,
)

class ReplicaSetModel(val variant: ReplicaVariant = ReplicaVariant(), val frames: Int = 3) : Spec<ReplicaSt> {
    override val name = "replica-set[$variant]"
    private val script = (1..frames).toList()

    override fun initial() = ReplicaSt(toL = script, toF = script)

    private fun act(s: ReplicaSt, seq: Int, byLeader: Boolean): ReplicaSt {
        if (variant.exactWitness && seq in s.witnessApplied) return s // AlreadyApplied: recorded as a proven act
        return s.copy(
            acts = s.acts + (seq to (s.acts[seq] ?: 0) + 1),
            witnessApplied = s.witnessApplied + seq,
            lActed = if (byLeader) s.lActed + seq else s.lActed,
        )
    }

    override fun next(s: ReplicaSt): List<Transition<ReplicaSt>> {
        val out = ArrayList<Transition<ReplicaSt>>()
        fun add(l: String, t: ReplicaSt, progress: Boolean = true) = out.add(Transition(l, t, progress))
        if (s.lAlive) {
            if (s.lPending != null) {
                add("L writes X record for ${s.lPending} (STABLE)", s.copy(lPending = null, lDisposedStable = s.lDisposedMem))
            } else if (s.toL.isNotEmpty()) {
                val seq = s.toL.first()
                if (seq <= s.lDisposedMem) add("L drops duplicate $seq", s.copy(toL = s.toL.drop(1)))
                else add("L acts on $seq", act(s.copy(toL = s.toL.drop(1), lDisposedMem = seq, lPending = seq), seq, true))
            }
            if (s.lDisposedStable > s.lPublished) {
                add("L publishes disposed frontier ${s.lDisposedStable}", s.copy(lPublished = s.lDisposedStable, channel = s.channel + s.lDisposedStable))
            }
        }
        if (s.channel.isNotEmpty()) {
            val p = s.channel.first()
            val folded = maxOf(s.fFolded, p)
            add("F folds published frontier $p", s.copy(channel = s.channel.drop(1), fFolded = folded, fRetained = s.fRetained.filter { it > folded }))
        }
        // A new leader first acts, in lane order, on its retention above the fold (COH §3.1).
        if (s.toF.isNotEmpty() && !(s.fLeader && s.fRetained.isNotEmpty())) {
            val seq = s.toF.first()
            val t = s.copy(toF = s.toF.drop(1))
            if (s.fLeader) {
                if (seq <= s.fDisposed) add("F (leader) drops duplicate $seq", t)
                else add("F (leader) acts on $seq", act(t.copy(fDisposed = seq), seq, false))
            } else {
                // Suppressed for lack of effect authority: advances `received` only (PLP §2).
                var u = t.copy(fReceived = seq)
                if (variant.followerAdvancesDisposed) u = u.copy(fDisposed = seq)
                if (!variant.noRetention && seq > s.fFolded) u = u.copy(fRetained = u.fRetained + seq)
                add("F delivers $seq suppressed", u)
            }
        }
        if (s.fLeader && s.fRetained.isNotEmpty()) {
            val seq = s.fRetained.first()
            val t = s.copy(fRetained = s.fRetained.drop(1))
            if (seq <= s.fDisposed) add("F (leader) skips retained $seq (<= its disposed)", t)
            else add("F (leader) acts on retained $seq", act(t.copy(fDisposed = seq), seq, false))
        }
        // Faults and management.
        if (s.lAlive && s.deaths > 0) {
            var ceil = s.ceiling
            s.lPending?.let { ceil = ceil + (it to (ceil[it] ?: 0) + 1) }
            add("L dies", s.copy(lAlive = false, deaths = 0, ceiling = ceil), progress = false)
        }
        if (!s.lAlive && !s.fLeader) {
            val gapless = s.fRetained.isEmpty() || s.fRetained == ((s.fFolded + 1)..s.fRetained.last()).toList()
            if (gapless) {
                val unpublished = s.lActed.filter { it > s.fFolded }.toSet()
                add(
                    "DESIGNATE F (retention gapless above folded ${s.fFolded})",
                    s.copy(fLeader = true, fDisposed = if (variant.followerAdvancesDisposed) s.fDisposed else s.fFolded, unpublishedAtFailover = unpublished),
                )
            }
        }
        return out
    }

    override fun invariants(s: ReplicaSt): List<String> {
        val v = ArrayList<String>()
        for ((seq, n) in s.acts) {
            val allowed = 1 + (if (seq in s.unpublishedAtFailover) 1 else 0) + (s.ceiling[seq] ?: 0)
            val exact = if (variant.exactWitness) 1 else allowed
            if (n > exact) v.add("I2 effect: position $seq acted $n times (allowed $exact)")
        }
        for (seq in script) {
            if ((s.acts[seq] ?: 0) > 0 || (variant.exactWitness && seq in s.witnessApplied)) continue
            val held = (s.lAlive && seq in s.toL) || seq in s.toF || seq in s.fRetained || (s.lAlive && s.lPending == seq)
            if (!held) v.add("I3 omission: position $seq neither acted nor held by any instance")
        }
        return v
    }

    override fun quiescent(s: ReplicaSt): List<String> =
        if (!s.lAlive && !s.fLeader) emptyList() // waiting for a designation is a declared stall, not a loss
        else script.filter { (s.acts[it] ?: 0) == 0 }.map { "position $it never acted" }
}
