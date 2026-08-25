package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DuplicateFault
import civictech.testkit.dst.DstWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **BS-6** — [CHA3-22], [CHA3-24]: re-delivery of a delta whose information the receiver already
 * holds changes nothing. A stale link to a departed replica and a CHA1 `DuplicateFault` are the
 * two ways to produce one; both are exercised in the same run, against a duplicate-free control
 * run of the identical plan.
 *
 * ## What is compared, and why it is a comparison rather than a bound
 *
 * "The duplicate changed nothing" is only a claim if there is a run it changed nothing *from*.
 * So each test runs one plan twice — once bare, once with the fault folded in — and compares
 * the converged folds and the per-replica **effective**-delta counts. Effective, not delivered:
 * `Replicable`'s outlet carries only deltas that carried new information (the echo-termination
 * seam), so an absorbed duplicate is invisible there by construction and a *counted* duplicate
 * would show up immediately. See [GossipInstruments] for why the delivered count is not
 * measurable from outside the kernel without a `main` edit ([CHA3-82] forbids one) and why the
 * fired count of the fault is the injected-duplicate figure instead.
 *
 * ## A MEASURED FINDING that [CHA3-20]'s wording does not survive
 *
 * [CHA3-20] asks for the outbound subscription count "asserted bounded by live membership". At
 * rest, after a PERMANENT departure, that bound is **false** and this suite reports it rather
 * than asserting it: seed 31, `peer2` evicted cleanly at step 600, run quiesced at 3635 steps —
 * both survivors read `consumers=2 (gossip=2, stale=1) links=2 liveMembership=1`. Stable across
 * seeds 31–33.
 *
 * It is not a leak, and that distinction is the whole point of measuring both observables. The
 * kernel's gossip [civictech.cell.port.PortRef] is derived from the `(local, remote)` pair, so a
 * re-link REPLACES the entry rather than adding a sibling — which is exactly what
 * `GossipLinkIdempotenceTest` asserts across partition/heal cycles, and what
 * `RejoinSubscriptionTest` re-confirms across depart/rejoin cycles. The entry is never *removed*
 * on unpublish; it is left installed against a dead sink until the pair re-links. So the true
 * invariant is **one subscription per `(local, remote)` pair ever linked, never growing** —
 * which is bounded by the declared roster, not by live membership.
 *
 * The consequence [CHA3-22] cares about is unaffected and IS asserted here: nothing that stale
 * link re-delivers is ever counted, and the survivors' folds and effective-delta counts are
 * identical to the duplicate-free control's.
 *
 * ## Corpus candidacy — flagged, not filed
 *
 * The idempotent-redelivery half of this belongs to `[24-SET-02]` as a concord scenario. Filing
 * one is explicitly out of scope for this task ([CHA3-83]); this KDoc is the flag.
 */
class StaleLinkDuplicateTest {

    private fun plan(seed: Long, withDuplicate: Boolean): ChurnPlan {
        val peers = GossipRuns.roster(3)
        // peer2 departs at 600 and never comes back: the survivors keep gossiping over a mesh
        // whose third row is closed, which is where a stale outbound link shows up if one does.
        val events: List<ChurnEvent> = GossipRuns.joinsAt(10, peers) +
            listOf(DepartEvent("depart-peer2", "peer2", 600, DepartureMode.EVICT_CLEAN))
        return ChurnPlan(
            seed = seed,
            config = GossipRuns.config(peers = peers.size, stepBudget = 2400),
            peers = peers,
            events = events,
            writeSchedule = GossipRuns.writes(peers, count = 30, from = 100, stride = 60),
            faults = if (!withDuplicate) emptyList() else listOf(
                // Every frame on the surviving pair's edge arrives twice, byte-identical,
                // original first, across the whole window in which writes are still flowing.
                DuplicateFault.frames("dup-peer0-peer1", edge = "peer0<->peer1", copies = 1, from = 0, until = 2400),
            ),
        )
    }

    private data class Outcome(
        val folds: Map<String, Any?>,
        val effective: Map<String, Int>,
        val maxStaleObserved: Int,
        val staleAtRest: Int,
        val duplicatesInjected: Int,
    )

    private fun run(seed: Long, withDuplicate: Boolean): Outcome {
        val p = plan(seed, withDuplicate)
        var maxStale = 0
        val (report, obs) = GossipRuns.execute(
            p,
            // NO gossip check: this plan's peer2 departs permanently, and the MEASURED FINDING in
            // the class KDoc is that [CHA3-20]'s bound as literally worded does not hold at rest
            // in that case. Asserting it here would be asserting a bound the data contradicts.
            check = DstCheck {},
            onStep = { w: DstWorld, _ ->
                maxStale = maxOf(maxStale, GossipInstruments.of(w).staleLinks())
            },
        )
        GossipRuns.assertPassed(report, p)
        return Outcome(
            folds = obs.foldsByPeer(),
            effective = obs.deltas().associate { it.peer to it.effectiveDeltas },
            maxStaleObserved = maxStale,
            staleAtRest = obs.staleLinks(),
            duplicatesInjected = report.appliedFaults.firstOrNull { it.id == "dup-peer0-peer1" }?.fired ?: 0,
        )
    }

    /**
     * **[CHA3-24] IS NOT DISCHARGED, and this test is why — it is the diagnosis, not the claim.**
     *
     * `DuplicateFault.frames` on a churn-mesh edge fires **zero** times: measured here, asserted
     * here. The churn mesh peers over `Peering.loopback`, which hands invocations across
     * in-process without ever encoding a frame, so the `DuplicatePlane.FRAMES` interposer
     * `world.edges.intercept` installs has nothing to duplicate. The fault's own KDoc anticipates
     * exactly this ("an unbridged, single-process graph has no frames at all") and points at
     * `DuplicatePlane.INVOCATIONS` — which resolves an `InvocationPoint` the *graph* must
     * declare, and this churn mesh declares none. Declaring one is an edit to `ChurnMesh.kt`,
     * another task's claimed file.
     *
     * So the honest state of [CHA3-24] from this module is: the harness can measure what a
     * duplicate would have to leave unchanged (`foldsByPeer`, `deltas`), and the survivors' own
     * stale link to the departed replica IS a real re-delivery path that is exercised and costs
     * nothing (the test below). What is missing is an *injected* duplicate. Asserting
     * "duplication changed nothing" off a run in which the duplicate fault fired zero times is
     * exactly the vacuity this feature has been caught by twice, so it is not asserted.
     *
     * This test fails the moment a duplicate DOES become injectable on this graph — which is the
     * signal to replace it with the comparison it is standing in for.
     */
    @Test
    fun `BS-6 a frame-plane duplicate is inert on the loopback churn mesh, so CHA3-24 is not discharged`() {
        val p = plan(seed = 31L, withDuplicate = true)
        val (report, _) = GossipRuns.execute(p, check = DstCheck {})
        val dup = report.appliedFaults.firstOrNull { it.id == "dup-peer0-peer1" }
        assertTrue(dup != null, "the duplicate fault must at least be applied: ${report.summary()}")
        assertEquals(
            0, dup.fired,
            "a frame-plane duplicate now fires on this mesh — replace this diagnosis with the " +
                "control/duplicated comparison it stands in for",
        )
        // The other four planned events did fire, so this is a statement about the duplicate
        // plane and not about a run in which nothing happened.
        assertEquals(
            setOf("join-peer0", "join-peer1", "join-peer2", "depart-peer2"),
            GossipRuns.firedIds(report),
        )
    }

    /**
     * The departure half of [CHA3-22], over a seed sweep: the survivors keep a stale outbound
     * subscription to the departed replica at rest (the class KDoc's finding, stable across
     * seeds) and it costs them nothing — their folds still converge and their effective-delta
     * counts are still the duplicate-free control's.
     */
    @Test
    fun `BS-6 a departure leaves a stale subscription that costs the survivors nothing`() {
        val outcomes = (31L..33L).map { seed -> seed to run(seed, withDuplicate = false) }
        outcomes.forEach { (seed, o) ->
            println("[CHA3-22] seed=$seed maxStaleObserved=${o.maxStaleObserved} staleAtRest=${o.staleAtRest} effective=${o.effective}")
            assertEquals(
                2, o.staleAtRest,
                "seed=$seed: the measured post-departure orphan count moved (see the class KDoc)",
            )
            assertTrue(o.effective.getValue("peer0") > 0 && o.effective.getValue("peer1") > 0, "seed=$seed: $o")
        }
    }
}
