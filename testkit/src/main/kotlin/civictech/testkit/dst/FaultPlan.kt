package civictech.testkit.dst

import java.util.Random

/**
 * A seeded, serialisable adversary: [seed] plus the [faults] to apply ([CHA1-02], [CHA1-30]).
 *
 * **The seed lives here, and nowhere else.** The controller's cross-host pick, every fault's
 * own randomness, and any workload randomness a graph builder asks for all derive from this
 * one field via [DstWorld.rng] — that is [CHA1-30] made structural rather than promised. It
 * also gives the shrinker ([CHA1-35]) exactly one field it must not touch: a shrunk plan is
 * `plan.copy(faults = fewer)`, and `seed` comes along unchanged by construction.
 *
 * Activation points are **controller step indices** — a fault schedules itself in
 * [Fault.onStep], and there is no wall-clock anywhere in this type or in [TraceSink]
 * ([CHA1-02]).
 */
data class FaultPlan(
    val seed: Long,
    val faults: List<Fault> = emptyList(),
) {
    init {
        val duplicates = faults.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "fault ids must be unique within a plan — duplicated: ${duplicates.sorted()}"
        }
    }

    val isEmpty: Boolean get() = faults.isEmpty()

    /** Drop one fault by id — the shrinker's only reduction that changes the plan's size. */
    fun without(faultId: String): FaultPlan = copy(faults = faults.filterNot { it.id == faultId })

    companion object {
        /** The fault-free plan on [seed]: the [CHA1-04] / BS-2 baseline case. */
        fun empty(seed: Long): FaultPlan = FaultPlan(seed, emptyList())

        fun of(seed: Long, vararg faults: Fault): FaultPlan = FaultPlan(seed, faults.toList())
    }
}

/**
 * Derives an independent, deterministic [Random] per named purpose from one run seed.
 *
 * Named rather than positional on purpose: two graph builders that each ask for "workload"
 * get the same stream, while a builder that asks for "workload" and a fault that asks for
 * "partition-1" do not interfere — so adding a fault to a plan does not silently re-roll the
 * workload and change what the run means. Repeated calls for one purpose return the *same*
 * generator, never a fresh one replaying the same numbers.
 */
internal class SeedFanout(private val seed: Long) {
    private val streams = mutableMapOf<String, Random>()

    fun rng(purpose: String): Random = streams.getOrPut(purpose) { Random(mix(seed, purpose)) }

    private fun mix(seed: Long, purpose: String): Long {
        // splitmix64 finaliser over seed ^ a stable 64-bit hash of the purpose string.
        var z = seed xor purpose.fold(-0x61c8864680b583ebL) { acc, c -> (acc xor c.code.toLong()) * 0x100000001B3L }
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }
}
