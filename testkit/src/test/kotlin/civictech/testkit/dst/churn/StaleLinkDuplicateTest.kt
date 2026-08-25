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
 * two ways to produce one.
 *
 * ## WHAT THIS SUITE ASSERTS — read before trusting a green run
 *
 * "The duplicate changed nothing" is only a claim if there is a run it changed nothing *from*,
 * so the [CHA3-24] test below is a **two-arm comparison**: one plan, one seed, run twice — once
 * bare, once with a `DuplicateFault` folded in — and the converged folds and the per-replica
 * effective-delta counts are compared between the arms. The comparison is only worth reading
 * because the duplicated arm demonstrably injects: `duplicatesInjected > 0` is asserted, not
 * assumed, and the bare arm's is asserted zero. A green off an inert adversary is the vacuity
 * this feature has been caught by three times.
 *
 * **What made the adversary real.** It was previously inert, and the earlier diagnosis in this
 * file blamed the loopback for having no frames. That was wrong: a `Peering.loopback` does
 * encode frames (`BridgeEgressCell`'s outlet carries `ByteArray`; `Peering.hostIngress` decodes
 * them). What was missing is that `ChurnMesh` declared an edge per pair and never routed the
 * peering's frames through it — the loopback kept `FrameInterpose.PASS_THROUGH`, so the
 * interposer a frame-plane fault installs on the named edge saw nothing. `ChurnMesh` now wires
 * both directions through `world.edges.deliver(edge, frame)`, the idiom `PartitionFaultTest`
 * and `DuplicateFaultTest` already used, and `DuplicateFault.frames` fires on this mesh.
 *
 * Effective, not delivered: `Replicable`'s outlet carries only deltas that carried new
 * information (the echo-termination seam), so an absorbed duplicate is invisible there by
 * construction and a *counted* duplicate would show up immediately. See [GossipInstruments] for
 * why the delivered count is not measurable from outside the kernel without a `main` edit
 * ([CHA3-82] forbids one) and why the fired count of the fault is the injected-duplicate figure
 * instead.
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
 * What that stale link does NOT give [CHA3-22] is a re-delivery: it points at a replica that has
 * departed, so whatever it carries reaches a dead sink rather than a live receiver that already
 * holds the information. The re-delivery [CHA3-22] is about is the injected duplicate on the
 * SURVIVING pair's edge, and that is what the two-arm test asserts.
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
     * **[CHA3-24]**, and [CHA3-22]'s "the effective-delta count is *unchanged*" half: one plan,
     * one seed, run twice — bare, then with every frame on the surviving pair's edge delivered
     * twice byte-identically — and the two arms agree on the converged fold of every peer and on
     * each replica's effective-delta count.
     *
     * **The comparison is only a claim because the adversary demonstrably fired.** The duplicated
     * arm asserts `duplicatesInjected > 0` and the bare arm asserts it zero, so a run in which
     * nothing was injected fails here rather than passing as "duplication changed nothing".
     * [GossipRuns.assertPassed] independently requires the exact set of fired ids, which is the
     * assertion that caught this adversary being inert in the first place.
     *
     * **What "unchanged" means, precisely.** `Replicable`'s outlet carries only deltas that
     * carried new information, so a duplicate the receiver absorbs is invisible on it while a
     * duplicate that was *counted* — applied twice — would move the count. Equal effective
     * counts across the arms is therefore the statement that re-delivery of a delta the receiver
     * already holds is a no-op, and equal folds is the statement that it is a no-op on the state
     * as well as on the accounting. Asserted for this plan and this seed, not swept.
     */
    @Test
    fun `BS-6 duplicating every frame on a live pair changes neither the folds nor the effective-delta counts`() {
        val seed = 31L
        val bare = run(seed, withDuplicate = false)
        val duplicated = run(seed, withDuplicate = true)
        println(
            "[CHA3-24] seed=$seed injected=${duplicated.duplicatesInjected} " +
                "bare.effective=${bare.effective} duplicated.effective=${duplicated.effective}",
        )
        assertEquals(
            0, bare.duplicatesInjected,
            "the control arm must carry no duplicate at all, or it is not a control",
        )
        assertTrue(
            duplicated.duplicatesInjected > 0,
            "the duplicate fault injected nothing, so this comparison would measure nothing: $duplicated",
        )
        assertEquals(
            bare.folds, duplicated.folds,
            "a duplicated delta changed a converged fold (seed=$seed)",
        )
        assertEquals(
            bare.effective, duplicated.effective,
            "a duplicated delta was counted as effective by some replica (seed=$seed): " +
                "injected=${duplicated.duplicatesInjected}",
        )
    }

    /**
     * The departure half of [CHA3-22], over a seed sweep: the survivors keep a stale outbound
     * subscription to the departed replica at rest (the class KDoc's finding, stable across
     * seeds) and it costs them nothing — they go on absorbing effective deltas.
     *
     * **Single-arm, deliberately.** This is a statement about what a *departure* leaves behind,
     * so it sweeps seeds rather than pairing arms: it asserts the measured stale count and
     * continued absorption, not "unchanged relative to a control". The control/duplicated
     * comparison is the test above, at one seed.
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
