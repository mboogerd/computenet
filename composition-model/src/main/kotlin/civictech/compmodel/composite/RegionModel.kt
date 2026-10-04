package civictech.compmodel.composite

import civictech.compmodel.check.Spec
import civictech.compmodel.check.Transition

/**
 * The glitch-free region: COH §3.3 (obligations: partial-wave custody in the join's `A`,
 * region-atomic suspension, the contagious veto) and spec 34:163-174, whose claim is that the
 * region "suspends atomically ... (a partial-diamond stall cannot exist by construction)".
 *
 * A diamond on one host: each wave reaches members m1 and m2 (already accepted into their
 * `P` inboxes), each member forwards its arm to the join J, whose `A` holds a wave until both
 * arms are present. Management may SUSPEND the region (COH §3.3: query `canSuspend()` on every
 * member, any `false` vetoes; otherwise deliver SUSPEND to every member's `P` within one
 * management-band turn) and migrate J (capture its partial waves). RESUME is emergent.
 */
data class RegionVariant(
    /** m2 is `NonSuspendable`: its veto is contagious. */
    val m2NonSuspendable: Boolean = false,
    /** Control: SUSPEND delivered member by member, veto discovered as reached, no undo. */
    val sequentialSuspend: Boolean = false,
    /** Control computenet-5jhg3: J's deactivation for migrate drops its partial waves. */
    val joinDropsPartialOnMigrate: Boolean = false,
    /** Check spec 34's "a partial-diamond stall cannot exist by construction". */
    val checkNoPartialDiamondStall: Boolean = false,
    /** Fix candidate: suspend only at a wave boundary (J holds no partial wave, members aligned). */
    val suspendAtWaveBoundary: Boolean = false,
)

data class RegionSt(
    val m1: List<Int>,
    val m2: List<Int>,
    val s1: Boolean = false,
    val s2: Boolean = false,
    val joinBuf: Set<Pair<Int, Int>> = emptySet(),
    val delivered: Set<Int> = emptySet(),
    val pendingSuspend: Boolean = false,
    val suspendBudget: Int = 1,
    val migrateBudget: Int = 1,
    val lost: Set<Pair<Int, Int>> = emptySet(),
)

class RegionModel(val variant: RegionVariant = RegionVariant(), val waves: Int = 2) : Spec<RegionSt> {
    override val name = "region[$variant]"
    override fun initial() = RegionSt(m1 = (1..waves).toList(), m2 = (1..waves).toList())

    private fun join(s: RegionSt, wave: Int, member: Int): RegionSt {
        val buf = s.joinBuf + (wave to member)
        return if ((wave to 1) in buf && (wave to 2) in buf) s.copy(joinBuf = buf - (wave to 1) - (wave to 2), delivered = s.delivered + wave)
        else s.copy(joinBuf = buf)
    }

    override fun next(s: RegionSt): List<Transition<RegionSt>> {
        val out = ArrayList<Transition<RegionSt>>()
        fun add(l: String, t: RegionSt, p: Boolean = true) = out.add(Transition(l, t, p))
        if (!s.s1 && s.m1.isNotEmpty()) add("m1 forwards wave ${s.m1.first()} to J", join(s.copy(m1 = s.m1.drop(1)), s.m1.first(), 1))
        if (!s.s2 && s.m2.isNotEmpty()) add("m2 forwards wave ${s.m2.first()} to J", join(s.copy(m2 = s.m2.drop(1)), s.m2.first(), 2))
        if (s.suspendBudget > 0 && !s.s1 && !s.s2) {
            val boundaryOk = !variant.suspendAtWaveBoundary || (s.joinBuf.isEmpty() && s.m1.firstOrNull() == s.m2.firstOrNull())
            if (boundaryOk) {
                if (variant.sequentialSuspend) add("SUSPEND delivered to m1", s.copy(s1 = true, pendingSuspend = true, suspendBudget = 0), false)
                else if (variant.m2NonSuspendable) add("SUSPEND region: m2 canSuspend()=false, whole region vetoed", s.copy(suspendBudget = 0), false)
                else add("SUSPEND region atomically (one management turn)", s.copy(s1 = true, s2 = true, suspendBudget = 0), false)
            }
        }
        if (s.pendingSuspend) {
            add(
                if (variant.m2NonSuspendable) "SUSPEND reaches m2: vetoed (m1 stays parked)" else "SUSPEND delivered to m2",
                s.copy(pendingSuspend = false, s2 = !variant.m2NonSuspendable),
            )
        }
        if (s.s1 && s.s2) add("RESUME region (renewed interest)", s.copy(s1 = false, s2 = false))
        else if (s.s1 && !s.pendingSuspend) add("RESUME m1", s.copy(s1 = false))
        if (s.migrateBudget > 0) {
            val t = if (variant.joinDropsPartialOnMigrate) s.copy(joinBuf = emptySet(), lost = s.lost + s.joinBuf) else s
            add("migrate J (capture partial waves ${s.joinBuf})", t.copy(migrateBudget = 0), false)
        }
        return out
    }

    override fun invariants(s: RegionSt): List<String> {
        val v = ArrayList<String>()
        if (s.lost.isNotEmpty()) v.add("I3 silent loss: partial-wave arms ${s.lost} dropped at J")
        if (s.s1 != s.s2 && !s.pendingSuspend) v.add("region atomicity: only m${if (s.s1) 1 else 2} is suspended and the region was not vetoed whole")
        if (s.s1 != s.s2 && s.pendingSuspend) v.add("region atomicity: m1 suspended while m2 still runs (interleaved delivery)")
        if (variant.checkNoPartialDiamondStall && s.s1 && s.s2 && s.joinBuf.isNotEmpty()) {
            v.add("partial-diamond stall: region suspended while J holds partial wave(s) ${s.joinBuf} (spec 34:166-168 says this cannot exist)")
        }
        return v
    }

    override fun quiescent(s: RegionSt): List<String> =
        (1..waves).filter { it !in s.delivered }.map { "wave $it never completed" }
}
