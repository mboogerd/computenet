package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.ReassignEvent
import civictech.testkit.dst.RejoinEvent
import civictech.testkit.dst.SeedFanout
import java.util.Random

/**
 * Derives a [ChurnPlan] from a seed and a [ChurnConfig] ([CHA3-01], [CHA3-06]).
 *
 * ## Determinism, and how it is actually obtained
 *
 * [CHA3-06]: **one seed derives every randomness source.** The generator does not own a
 * `Random` — it asks [SeedFanout] for a named stream per purpose, which is the identical
 * discipline `DstWorld.rng(purpose)` publishes to graph builders and faults ([CHA1-30]), over
 * the identical implementation. Named rather than positional for the same reason it is there:
 * adding a draw to the step placement must not silently re-roll the peer roster.
 *
 * Three further things are load-bearing for "identical plan across JVM runs" ([CHA3-01]) and
 * none of them is obvious:
 *
 *  - `java.util.Random` is **specified**, not implementation-defined — the LCG, the seed
 *    scramble and `nextInt`/`nextDouble` are all in its contract — so the same seed yields the
 *    same sequence on any conforming JVM. Nothing here uses `Math.random`, `ThreadLocalRandom`,
 *    `UUID.randomUUID`, a hash-ordered collection, or `System.identityHashCode`.
 *  - [ChurnConfig.departureWeights] is walked in `DepartureMode.entries` order, never in map
 *    iteration order. Two `Map`s that compare equal may iterate differently, and a config is a
 *    value: equal configs must generate equal plans.
 *  - Ids are derived from the event's ordinal, not from an identity or a counter shared with
 *    anything else, so a plan's ids are a function of `(seed, config)` like everything else.
 *
 * ## What it generates
 *
 * [ChurnConfig.eventCount] **membership** events (join / rejoin / depart), each derived from
 * the drawn peer's current membership state so the sequence is coherent — a peer cannot depart
 * before it joins, and cannot rejoin while it is a member. Each membership event carries a
 * derived [ReassignEvent] companion at the same step, because that is what membership churn
 * *is* in this kernel: a peer joining or leaving is only half of it, and the interest/epoch
 * hand-off is the half a reconvergence property has to survive. Reassignments are therefore
 * not counted against `eventCount` — they are consequences of it.
 *
 * Interests are named `interest-<n>` after the peer index whose slice they denote, so
 * "peer1 takes over peer3's slice at epoch 7" is expressible without the generator knowing
 * anything about the kernel's `Interest` type. The graph's [civictech.testkit.dst.PeerHandle]
 * resolves the name.
 *
 * ## What it does not generate
 *
 * No mesh graph, no executor, no check. A `ChurnPlan` is a value; running it is task 2's
 * [civictech.testkit.dst.PeerHandle] implementations and task 3's reconvergence property.
 */
object ChurnGenerator {

    /**
     * The plan for `(seed, config)`. Pure: same arguments, same plan, on any JVM ([CHA3-01]).
     */
    fun generate(seed: Long, config: ChurnConfig = ChurnConfig()): ChurnPlan {
        val fanout = SeedFanout(seed)
        val rosterRng = fanout.rng("churn-roster")
        val peerRng = fanout.rng("churn-peer-pick")
        val modeRng = fanout.rng("churn-departure-mode")
        val stepRng = fanout.rng("churn-steps")
        val overlapRng = fanout.rng("churn-overlap")
        val writeRng = fanout.rng("churn-writes")

        val peers = roster(config, rosterRng)
        val events = membershipEvents(config, peers, peerRng, modeRng, stepRng, overlapRng)
        val writes = writeSchedule(config, peers, writeRng)

        return ChurnPlan(
            seed = seed,
            config = config,
            peers = peers,
            events = events,
            writeSchedule = writes,
        )
    }

    // ------------------------------------------------------------------------------ roster

    private fun roster(config: ChurnConfig, rng: Random): List<String> {
        val span = config.peerCount.last - config.peerCount.first + 1
        val count = config.peerCount.first + rng.nextInt(span)
        return List(count) { "peer$it" }
    }

    // ------------------------------------------------------------------- membership events

    /** Where a peer stands at the point the generator draws it. */
    private enum class Membership { NEVER_JOINED, MEMBER, DEPARTED }

    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    private fun membershipEvents(
        config: ChurnConfig,
        peers: List<String>,
        peerRng: Random,
        modeRng: Random,
        stepRng: Random,
        overlapRng: Random,
    ): List<ChurnEvent> {
        val events = mutableListOf<ChurnEvent>()
        val state = peers.associateWith { Membership.NEVER_JOINED }.toMutableMap()
        val horizon = config.stepBudget - 1
        val maxGap = maxOf(1, horizon / maxOf(1, config.eventCount))

        var step = 0
        var epoch = 1L
        // The end of the most recently opened PARTITION_SUSPEND window, or -1 when none is
        // open. `partitionOverlap` is the probability the next event lands *inside* it.
        var suspendOpenUntil = -1

        repeat(config.eventCount) { index ->
            step = nextStep(step, horizon, maxGap, suspendOpenUntil, config.partitionOverlap, stepRng, overlapRng)
            val peer = peers[peerRng.nextInt(peers.size)]
            val peerIndex = peers.indexOf(peer)
            val interest = "interest-$peerIndex"

            when (state.getValue(peer)) {
                Membership.NEVER_JOINED -> {
                    events += JoinEvent("churn-$index-join-$peer", peer, step)
                    events += ReassignEvent("churn-$index-reassign-$peer", peer, step, interest, epoch++)
                    state[peer] = Membership.MEMBER
                }

                Membership.DEPARTED -> {
                    events += RejoinEvent("churn-$index-rejoin-$peer", peer, step)
                    events += ReassignEvent("churn-$index-reassign-$peer", peer, step, interest, epoch++)
                    state[peer] = Membership.MEMBER
                }

                Membership.MEMBER -> {
                    val survivor = peers.firstOrNull { it != peer && state[it] == Membership.MEMBER }
                    if (survivor == null) {
                        // The last member standing. Emptying the mesh is not churn, it is the
                        // end of the run — so this event is a reassignment only, which keeps
                        // eventCount honest without generating a plan nothing can converge in.
                        events += ReassignEvent("churn-$index-reassign-$peer", peer, step, interest, epoch++)
                    } else {
                        val mode = drawDepartureMode(config, modeRng)
                        events += DepartEvent("churn-$index-depart-$peer", peer, step, mode)
                        // The departing peer's slice is shed onto a survivor at a fresh epoch:
                        // the InstanceSet.assign(ref, interest, epoch) half of a departure.
                        events += ReassignEvent("churn-$index-reassign-$survivor", survivor, step, interest, epoch++)
                        state[peer] = Membership.DEPARTED
                        if (mode == DepartureMode.PARTITION_SUSPEND) {
                            suspendOpenUntil = minOf(step + config.suspendWindow, horizon)
                        }
                    }
                }
            }
        }
        return events
    }

    /**
     * The next activation step, honouring [ChurnConfig.partitionOverlap].
     *
     * Steps are non-decreasing and always `<= horizon`, so [ChurnPlan]'s own horizon `require`
     * cannot be violated by generation ([CHA3-02]). The overlap roll is drawn from its own
     * stream **unconditionally when a window is open**, so that whether it is consulted does
     * not depend on how many draws an unrelated decision made.
     */
    @Suppress("LongParameterList")
    private fun nextStep(
        previous: Int,
        horizon: Int,
        maxGap: Int,
        suspendOpenUntil: Int,
        partitionOverlap: Double,
        stepRng: Random,
        overlapRng: Random,
    ): Int {
        val proposed = (previous + 1 + stepRng.nextInt(maxGap)).coerceAtMost(horizon)
        if (suspendOpenUntil <= previous) return proposed
        val inside = overlapRng.nextDouble() < partitionOverlap
        return if (inside) {
            // Land inside the still-open suspension window.
            proposed.coerceAtMost(suspendOpenUntil).coerceAtLeast(minOf(previous + 1, horizon))
        } else {
            // Wait the window out.
            maxOf(proposed, suspendOpenUntil).coerceAtMost(horizon)
        }
    }

    /**
     * Draw a [DepartureMode] by weight.
     *
     * Walks `DepartureMode.entries`, never `config.departureWeights`' own iteration order —
     * see the class KDoc. A mode weighted 0 is skipped; the config's `init` guarantees at
     * least one positive weight, so the fallback at the end is unreachable and is written as a
     * `error(...)` rather than a silent default.
     */
    private fun drawDepartureMode(config: ChurnConfig, rng: Random): DepartureMode {
        val total = DepartureMode.entries.sumOf { config.weightOf(it) }
        var roll = rng.nextInt(total)
        for (mode in DepartureMode.entries) {
            roll -= config.weightOf(mode)
            if (roll < 0) return mode
        }
        error("no departure mode drawn from weights ${config.departureWeights} (total=$total)")
    }

    // ------------------------------------------------------------------------ write script

    /**
     * The workload script ([ChurnConfig.opScriptLength], [ChurnConfig.writeConcurrency]).
     *
     * A write is placed on a fresh step unless the concurrency roll says otherwise, in which
     * case it shares the previous write's step — that, and only that, is what
     * "write-concurrency fraction" means here: two peers writing on the same controller step.
     */
    private fun writeSchedule(config: ChurnConfig, peers: List<String>, rng: Random): List<ChurnWrite> {
        val horizon = config.stepBudget - 1
        val writes = mutableListOf<ChurnWrite>()
        var step = 0
        repeat(config.opScriptLength) { ordinal ->
            val peer = peers[rng.nextInt(peers.size)]
            val concurrent = rng.nextDouble() < config.writeConcurrency
            if (!concurrent || writes.isEmpty()) {
                step = (step + 1).coerceAtMost(horizon)
            }
            writes += ChurnWrite(step, peer, ordinal)
        }
        return writes
    }
}
