package civictech.compmodel.composite

import civictech.compmodel.check.Spec
import civictech.compmodel.check.Transition

/**
 * The glitch-free region: COH §3.3 (obligations: partial-wave custody in the join's `A`,
 * region-atomic suspension, the contagious veto) and spec 34:163-174. The spec's prohibited
 * "partial-diamond stall" is a region in which only PART parks (34:163-171: the whole region
 * parks together or none does); a partial wave held in the join's `A` while the WHOLE region
 * is parked is custody COH §3.3 assigns to `A`, not a stall. The checked properties are
 * therefore: no partial region park, and no loss of partial-wave custody (every arm a member
 * has forwarded is held at J or delivered in a complete wave), through suspend and migrate.
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
            if (variant.sequentialSuspend) add("SUSPEND delivered to m1", s.copy(s1 = true, pendingSuspend = true, suspendBudget = 0), false)
            else if (variant.m2NonSuspendable) add("SUSPEND region: m2 canSuspend()=false, whole region vetoed", s.copy(suspendBudget = 0), false)
            else add("SUSPEND region atomically (one management turn)", s.copy(s1 = true, s2 = true, suspendBudget = 0), false)
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
            val t = if (variant.joinDropsPartialOnMigrate) s.copy(joinBuf = emptySet()) else s
            add("migrate J (capture partial waves ${s.joinBuf})", t.copy(migrateBudget = 0), false)
        }
        return out
    }

    override fun invariants(s: RegionSt): List<String> {
        val v = ArrayList<String>()
        // Custody of partial waves: an arm forwarded by member m (no longer in m's queue) is held
        // in J's A or its wave was delivered complete.
        for (w in 1..waves) for ((m, q) in listOf(1 to s.m1, 2 to s.m2)) {
            if (w !in q && w !in s.delivered && (w to m) !in s.joinBuf) v.add("I3 silent loss: arm of wave $w from m$m is neither held at J nor delivered")
        }
        // No partial region park (spec 34:163-171).
        if (s.s1 != s.s2 && !s.pendingSuspend) v.add("region atomicity: only m${if (s.s1) 1 else 2} is suspended and the region was not vetoed whole")
        if (s.s1 != s.s2 && s.pendingSuspend) v.add("region atomicity: m1 suspended while m2 still runs (interleaved delivery)")
        return v
    }

    override fun quiescent(s: RegionSt): List<String> =
        (1..waves).filter { it !in s.delivered }.map { "wave $it never completed" }
}
