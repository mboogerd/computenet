package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.RejoinEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **BS-5** — [CHA3-20], [CHA3-21], [CHA3-43]: N depart/rejoin cycles for one peer leave every
 * live replica's outbound gossip subscription count bounded by its own live membership, on BOTH
 * observables (the outlet consumer map and the port's `LinkSupport` record), with no orphaned
 * subscription to the departed attachment surviving on either — and a cycle that *increases* the
 * count fails the run.
 *
 * ## What this generalises
 *
 * `civictech.cell.replication.GossipLinkIdempotenceTest` asserts the same invariant for a
 * partition/heal cycle on a hand-built two-peer mesh, and states why convergence alone is blind
 * to it: "the mergeable merge is idempotent, so duplicated delivery still converges. Only the
 * subscription count exposes it." A churn harness that only asserted reconvergence would be
 * green on an unbounded leak. This is the churn-plan form of that assertion — a real departure
 * (evict / crash) rather than a park, a rejoin rather than a heal, and a mesh of three.
 *
 * The failing control that proves these checks *can* go red is the controls task's ([CHA3-73]),
 * not this file's. What is pinned here is that the assertion is on the adversary's actual
 * firing: every test asserts the exact set of fired fault ids.
 */
class RejoinSubscriptionTest {

    private fun cyclePlan(
        seed: Long,
        cycles: Int,
        mode: DepartureMode,
        peers: List<String> = GossipRuns.roster(3),
        churner: String = "peer1",
        firstDepartAt: Int = 400,
        period: Int = 500,
    ): ChurnPlan {
        val cycleEvents = (0 until cycles).flatMap { c ->
            val at = firstDepartAt + c * period
            listOf(
                DepartEvent("depart-$c", churner, at, mode) as ChurnEvent,
                RejoinEvent("rejoin-$c", churner, at + period / 2),
            )
        }
        val horizon = firstDepartAt + cycles * period + 400
        return ChurnPlan(
            seed = seed,
            config = GossipRuns.config(peers = peers.size, stepBudget = horizon + 200),
            peers = peers,
            events = GossipRuns.joinsAt(10, peers) + cycleEvents,
            // Strided across the whole horizon so writes land while the mesh is churning, not
            // all inside the first sixty steps. See GossipRuns' KDoc.
            writeSchedule = GossipRuns.writes(peers, count = horizon / 60, from = 100, stride = 60),
        )
    }

    /**
     * The core BS-5 assertion, over three cycles and both real departure modes.
     *
     * `EVICT_CLEAN` and `CRASH_UNCLEAN` are the two that genuinely *leave*: a
     * `PARTITION_SUSPEND` peer never departs (its rejoin is a heal, and it stays in every
     * registry throughout), so it exercises the park/heal path the kernel test already covers
     * rather than the depart/rejoin path this one is about.
     */
    @Test
    fun `BS-5 repeated depart and rejoin leaves the subscription count bounded on both observables`() {
        listOf(DepartureMode.EVICT_CLEAN, DepartureMode.CRASH_UNCLEAN).forEach { mode ->
            val plan = cyclePlan(seed = 21L, cycles = 3, mode = mode)
            val (report, obs) = GossipRuns.execute(plan)
            GossipRuns.assertPassed(report, plan)

            val readings = obs.subscriptions()
            assertEquals(3, readings.size, "every peer is live again at quiescence ($mode)")
            readings.forEach { r ->
                assertEquals(2, r.liveMembership, "$mode :: ${r.summary()}")
                // both observables, each against MEMBERSHIP — not against each other, which would
                // pass a run in which the two records leaked together
                assertEquals(2, r.gossipConsumers.size, "$mode :: ${r.summary()}")
                assertEquals(2, r.links, "$mode :: ${r.summary()}")
                assertEquals(0, r.unattributedConsumers, "$mode :: ${r.summary()}")
                assertEquals(emptySet(), r.staleConsumers, "$mode orphan survived :: ${r.summary()}")
            }
            println("[CHA3-20/21] $mode after 3 cycles: ${obs.report().summary()}")
        }
    }

    /**
     * [CHA3-43]: a rejoin cycle that *increases* the count fails the run — expressed as a check
     * over per-cycle snapshots rather than as an end-state reading, because an end state that is
     * right says nothing about the four intermediate states that were not.
     *
     * The snapshots are taken at the settle step before each departure and after each rejoin, so
     * the sequence covers every cycle boundary. The failure identity is the fixed
     * [GossipInstruments.REJOIN_INCREASED_SUBSCRIPTIONS] string with the counts in `detail`.
     *
     * ## A MEASURED TRANSIENT, and the assertion it rules out
     *
     * The stronger per-checkpoint form — `gossipConsumers <= liveMembership` at *every* sampled
     * step — was written first and is **false**, measured rather than guessed: at step 850 of the
     * seed-22 EVICT_CLEAN run, 200 steps after `peer1`'s rejoin, `peer2` read
     * `consumers=2 (gossip=2, stale=1) links=2 liveMembership=1` — it still held the gossip
     * subscription to `peer1` while its own directory had processed the unpublish and not yet the
     * rejoin's republish. That is a mid-flight disagreement between the linker and the directory,
     * not a leak: it is gone at quiescence (`staleLinks=0`), and the kernel promises directory /
     * link agreement at rest, not at an arbitrary step. So the per-checkpoint assertion here is
     * the one that IS sound mid-flight — the count did not move — and the orphan claim
     * ([CHA3-21]) is asserted where it is promised, at quiescence.
     */
    @Test
    fun `BS-5 a rejoin that increased the subscription count fails the run`() {
        val cycles = 3
        val firstDepartAt = 400
        val period = 500
        val plan = cyclePlan(seed = 22L, cycles = cycles, mode = DepartureMode.EVICT_CLEAN)
        // one sample just before each departure, one 200 steps after each rejoin
        val checkpoints = (0 until cycles).flatMap { c ->
            listOf(firstDepartAt + c * period - 20, firstDepartAt + c * period + period / 2 + 200)
        }.toSet()
        val snapshots = linkedMapOf<Int, List<SubscriptionReading>>()

        val (report, obs) = GossipRuns.execute(
            plan,
            check = DstCheck { world ->
                GossipInstruments.checks().verify(world)
                // The rejoin-increases-count property, over the whole cycle sequence.
                val peers = snapshots.values.flatten().map { it.peer }.toSet()
                peers.forEach { peer ->
                    val series = snapshots.entries
                        .mapNotNull { (step, rs) -> rs.firstOrNull { it.peer == peer }?.let { step to it } }
                    val worst = series.maxByOrNull { it.second.gossipConsumers.size }
                    val first = series.firstOrNull()
                    if (worst != null && first != null && worst.second.gossipConsumers.size > first.second.liveMembership) {
                        throw ChurnCheckFailure(
                            GossipInstruments.REJOIN_INCREASED_SUBSCRIPTIONS,
                            detail = "$peer over cycles: " +
                                series.joinToString("; ") { (s, r) -> "@$s ${r.summary()}" },
                        )
                    }
                }
            },
            onStep = { w, step ->
                if (step in checkpoints) snapshots[step] = GossipInstruments.of(w).subscriptions()
            },
        )
        GossipRuns.assertPassed(report, plan)

        assertEquals(checkpoints.size, snapshots.size, "every checkpoint must have been sampled")
        // The sequence is a measurement, not just a gate: report it ([CHA3-25]).
        println(
            "[CHA3-43] per-cycle subscription counts: " +
                snapshots.entries.joinToString("; ") { (s, rs) ->
                    "@$s ${rs.associate { it.peer to it.gossipConsumers.size }}"
                },
        )
        // The count never GREW across the cycle sequence — which is [CHA3-43] itself, and is the
        // strongest thing that is true at an arbitrary mid-flight checkpoint. See the MEASURED
        // TRANSIENT note below for the assertion that was tried here and is NOT sound.
        val counts = snapshots.values.map { rs -> rs.associate { it.peer to it.gossipConsumers.size } }
        counts.forEach { c ->
            assertEquals(
                counts.first(), c,
                "the subscription count moved across a depart/rejoin cycle: $counts",
            )
        }
        // …and every transient reading has healed by quiescence, which is where [CHA3-21] bites.
        assertTrue(obs.staleLinks() == 0, "no stale link survives the cycles: ${obs.report().summary()}")
    }
}
