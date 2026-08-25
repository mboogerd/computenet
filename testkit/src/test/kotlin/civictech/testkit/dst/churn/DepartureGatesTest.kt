package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstReport
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.JoinEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The departure gates a churn mesh's kernel seam must clear ([CHA3-30], [CHA3-31], [CHA3-41],
 * [CHA3-42], [CHA3-72]; feature computenet-umx.2 §4.4/§4.5), asserted through the observables in
 * [StabilityObservables] rather than duplicating their read.
 *
 * **BS-9** ([CHA3-30], [CHA3-31]): a replica whose every peer is partitioned away is suspended,
 * not evicted, on an eviction attempt — state retained, delivered-watermark row SUSPENDED and
 * not CLOSED — and resumes and reconverges on heal.
 *
 * **BS-10** ([CHA3-41], [CHA3-42]): a 3-peer mesh with the stability read attached. A clean evict
 * lets stability advance past the closed row; a run that fails to advance is a failed run
 * (`stabilityCovers` mirrors `MemberDepartureFrontierTest`'s own frontier rule). A crash without
 * evict is asserted on the half this harness can honestly check: the departed row never reads
 * CLOSED or SUSPENDED, even across a bounded further drain — no silent release. The stronger form
 * — that a brand-new post-departure wave fails to become `stabilityCovers`ed — is asserted for
 * BS-11's `EVICT_NO_CLOSE` (where it genuinely goes red) but **not** for `CRASH_UNCLEAN`: measured
 * directly, it does not hold there, because this rig's gossip delivery is synchronous
 * (`Replication`'s own KDoc: "streamTo... fine single-threaded/simulated") and a crashed peer's
 * cell objects keep merging/relaying inbound deltas as zombies as long as the surviving
 * `side`/`bridgeHost` keep them wired — see the second BS-10 test's own comment for the full
 * trace. Filed rather than silently worked around:
 * `concord/corpus/DISPUTES.md`'s `CHA3-42-stall-notice-unclean-departure` entry.
 *
 * `[CHA3-42]`'s SECOND half — that a `Stall`-family notice is observed on the reads — is
 * deliberately **not** asserted here either. See "The `unverified:` premise, resolved" below and
 * the same DISPUTES.md entry: the decided fallback (computenet-umx.2's breakdown comment,
 * "umx.2-D7") for exactly this case.
 *
 * **BS-11** ([CHA3-72]): the existing PN-0c control (`Replication.evict(closeDepartedRow =
 * false)`, reached through `DepartureMode.EVICT_NO_CLOSE`) leaves a wedge —
 * [StabilityObservables.stabilityCoversCheck] DETECTS it (a failing check naming the unclosed
 * row), rather than a green run silently tolerating it.
 *
 * Every green test below asserts the exact set of fired fault ids
 * (`report.appliedFaults.filter { it.fired > 0 }.map { it.id }` against the plan's own ids) —
 * `ChurnMeshTest`'s own rule, repeated here rather than assumed, because umx.2.2's review caught
 * exactly this omission silently passing a green "positive control".
 *
 * ## The `unverified:` premise, resolved
 *
 * The task's own text asked this to be probed before BS-10's second half was written: whether an
 * unclean departure (`CRASH_UNCLEAN`) surfaces a `Stall`-family notice
 * ([civictech.cell.control.StallNotice]) on any read this harness has. It does not, and the
 * negative is traceable rather than assumed:
 *
 *  - `MeshPeer.crash()` → `HostSlot.crash()` (`testkit`'s `DstWorld.kt`) shuts down the crashed
 *    generation's scheduler and rebuilds a fresh `ManagedHost` from the graph's own build lambda.
 *    It calls neither `suspend`, `restart` nor any invocation path — the only kinds of call site
 *    that ever construct a `StallNotice.Stall` (`ManagedHost.kt:738`/`:1326` `SUSPENDED`, `:1096`
 *    `DEAD_LETTERED` on a dead-lettered invocation, `:1101`/`:1138` `RESTARTING`/`SUSPENDED` under
 *    `SupervisionPolicy`, and `CompositeCell.kt:455` `DEAD_LETTERED` on a `BoundaryPolicy`
 *    refusal; enumerated in full in the DISPUTES entry cited below). A crash bypasses `ManagedHost`'s own lifecycle machinery entirely
 *    (`ChurnMeshTest`'s own control: `peer1.lastEvictDespawned` is null after a crash — "no
 *    eviction ran: nothing was announced or drained").
 *  - Even granting a notice fired, it travels `notifyDownstream` — to cells LINKED downstream of
 *    the crashed replica on ITS OWN host. The churn mesh's replicas are plain data cells with no
 *    such downstream consumer wired to them, and — as `ReconvergenceCheck`'s own KDoc already
 *    states — "the kernel has no failure detector, so a crashed peer never unpublishes": a
 *    SURVIVING peer has no notification path for another peer's crash at all.
 *
 * So the property is undecidable on the reads this harness has, not merely inconvenient to
 * assert. Per the decided fallback: assert the frontier-freeze half (this file's BS-10 second
 * test), file the notice half in `concord/corpus/DISPUTES.md` with this reasoning, and do
 * **not** dress the frontier-freeze assertion up as the whole of `[CHA3-42]`.
 */
class DepartureGatesTest {

    private companion object {
        /** See `writes`: one replicated write costs tens of controller steps on this mesh. */
        const val WRITE_STRIDE = 50
        const val BUDGET = 20_000
    }

    // ------------------------------------------------------------------------------- fixtures
    // Mirrors ChurnMeshTest's own fixtures (config/roster/writes/joinsAt/execute/firedIds) —
    // deliberately not shared, because ChurnMeshTest.kt is outside this task's file claim and a
    // shared private helper would require editing it.

    private fun config(peers: Int, stepBudget: Int) = ChurnConfig(
        peerCount = peers..peers,
        eventCount = 0,
        writeConcurrency = 0.0,
        partitionOverlap = 0.5,
        opScriptLength = 0,
        stepBudget = stepBudget,
        suspendWindow = 4,
    )

    private fun roster(peers: Int): List<String> = List(peers) { "peer$it" }

    private fun writes(peers: List<String>, count: Int, from: Int = 2, stride: Int = WRITE_STRIDE): List<ChurnWrite> =
        (0 until count).map { i -> ChurnWrite(from + i * stride, peers[i % peers.size], i) }

    private fun joinsAt(step: Int, peers: List<String>): List<ChurnEvent> =
        peers.map { peer -> JoinEvent("join-$peer", peer, step) }

    private fun firedIds(report: DstReport): Set<String> =
        report.appliedFaults.filter { it.fired > 0 }.map { it.id }.toSet()

    private fun execute(plan: ChurnPlan, budget: Int): Pair<DstReport, DstWorld> {
        var captured: DstWorld? = null
        // SET, not PN_COUNTER: StabilityObservables.originOf reads a SetDelta's public per-op
        // add tags — see its class KDoc for why that, and not the PnCounter path, is what
        // survives relay and matches what the watermark rows are actually keyed by.
        val spec = ChurnMesh.spec(plan, payload = MeshPayload.SET, maxPeers = plan.peers.size)
        val report = DstRun(
            graph = spec,
            plan = plan.toFaultPlan(),
            budget = budget,
            check = DstCheck { world -> captured = world },
        ).execute()
        val world = captured ?: fail(
            "the run never quiesced (${report.outcome}, ${report.steps}/${report.budget} steps), " +
                "so no check ran and nothing was observed",
        )
        return report to world
    }

    private fun departurePlan(peer: String, mode: DepartureMode, seed: Long): ChurnPlan {
        val peers = roster(3)
        return ChurnPlan(
            seed = seed,
            config = config(peers = 3, stepBudget = 4000),
            peers = peers,
            events = joinsAt(1, peers) + listOf(DepartEvent("depart-$peer", peer, 600, mode)),
            writeSchedule = writes(peers, count = 40),
        )
    }

    private fun runDeparture(peer: String, mode: DepartureMode, seed: Long): Pair<DstReport, DstWorld> {
        val plan = departurePlan(peer, mode, seed)
        val (report, world) = execute(plan, budget = BUDGET)
        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        assertEquals(plan.events.map { it.id }.toSet(), firedIds(report), report.summary())
        return report to world
    }

    // -------------------------------------------------------------------------------- BS-9

    @Test
    fun `BS-9 a fully partitioned replica is suspended not evicted, and heals`() {
        val (_, world) = runDeparture("peer1", DepartureMode.PARTITION_SUSPEND, seed = 41L)

        val peer1 = MeshPeers.require(world, "peer1")
        assertTrue(peer1.suspended, "the departure mode parked every one of this peer's links")
        assertEquals(0, peer1.reachablePeers(), "replicasOf(id) - {local} is empty, which is what evict gates on")

        // Attempt eviction while still fully partitioned: evict must refuse, not despawn.
        val despawned = peer1.evictClean()
        assertFalse(despawned, "no reachable peer remained, so evict must suspend rather than despawn")
        assertTrue(peer1.member, "a refused eviction never despawns — state is retained")
        assertNotNull(peer1.foldSnapshot(), "the suspended replica's state is retained")

        assertTrue(
            StabilityObservables.rowSuspended(peer1, peer1),
            "the delivered-watermark row reads SUSPENDED on the suspended replica's own companion",
        )
        assertFalse(
            StabilityObservables.rowClosed(peer1, peer1),
            "a partition-suspended row must never read CLOSED — [CHA3-31]",
        )

        // Heal: resumes and reconverges.
        peer1.heal()
        world.controller.runToIdle()
        assertFalse(peer1.suspended, "healing releases every parked link")
        assertFalse(
            StabilityObservables.rowSuspended(peer1, peer1),
            "the heal path retracts the recoverable Stall — the row is no longer SUSPENDED",
        )

        val peer0 = MeshPeers.require(world, "peer0")
        assertEquals(
            peer0.foldSnapshot(),
            peer1.foldSnapshot(),
            "post-heal, the previously-isolated replica reconverges with a surviving peer",
        )
    }

    // ------------------------------------------------------------------------------- BS-10

    @Test
    fun `BS-10 clean evict lets stability advance past the closed row`() {
        val (_, world) = runDeparture("peer2", DepartureMode.EVICT_CLEAN, seed = 43L)

        val peer0 = MeshPeers.require(world, "peer0")
        val peer1 = MeshPeers.require(world, "peer1")
        val peer2 = MeshPeers.require(world, "peer2")

        assertTrue(
            StabilityObservables.rowClosed(peer0, peer2),
            "PN-0c: a survivor's companion reads the cleanly-departed member's row CLOSED",
        )

        // Watch BEFORE issuing the post-departure writes: a tap only sees what happens after it
        // attaches (see StabilityObservables.watch's own KDoc).
        StabilityObservables.watch(peer0)
        repeat(5) { i -> assertTrue(peer0.write(200 + i), "peer0 is still live and a member") }
        world.controller.runToIdle()
        val origin = StabilityObservables.originOf(peer0, "peer0-204")

        // A failure to advance here IS the failed run [CHA3-41] describes.
        assertTrue(
            StabilityObservables.stabilityCovers(peer0, listOf(peer0, peer1, peer2), origin),
            "stability must advance past the closed row: a post-departure wave has to be coverable " +
                "once every survivor delivered it and the departed row is closed",
        )
    }

    @Test
    fun `BS-10 crash without evict freezes the frontier for the departed row`() {
        val (_, world) = runDeparture("peer2", DepartureMode.CRASH_UNCLEAN, seed = 47L)

        val peer0 = MeshPeers.require(world, "peer0")
        val peer2 = MeshPeers.require(world, "peer2")
        assertEquals(1, peer2.crashGeneration)
        assertFalse(peer2.member)

        // A crash bypasses Replication.evict entirely (ChurnMeshTest's own control): the row is
        // marked neither SUSPENDED nor CLOSED. Nobody released it, and nothing ever will —
        // verified across a BOUNDED FURTHER DRAIN, not only immediately after the crash, so a
        // release that showed up late would still be caught.
        assertFalse(StabilityObservables.rowClosed(peer0, peer2))
        assertFalse(StabilityObservables.rowSuspended(peer0, peer2))

        repeat(5) { i -> assertTrue(peer0.write(300 + i)) }
        world.controller.runToIdle()

        assertFalse(
            StabilityObservables.rowClosed(peer0, peer2),
            "no silent unfreeze: the departed row must still read un-CLOSED after further drain",
        )
        assertFalse(
            StabilityObservables.rowSuspended(peer0, peer2),
            "no silent unfreeze: the departed row must still read un-SUSPENDED after further drain",
        )

        // What this test does NOT assert, and why: [CHA3-41]'s stronger form — that a NEW
        // post-departure wave fails to become `StabilityObservables.stabilityCovers`ed — does
        // NOT hold for CRASH_UNCLEAN the way it demonstrably does for `EVICT_NO_CLOSE` (this
        // file's BS-11, same check, same rig, genuinely red there). Measured directly: after a
        // crash, peer2's un-closed row on a survivor's companion keeps absorbing OTHER live
        // peers' progress and `stabilityCovers` reports the wave as covered anyway. Traced to the
        // rig's own documented ceiling (`Replication`'s KDoc: "link wiring calls streamTo on the
        // local outlet directly... fine single-threaded/simulated") — gossip delivery is
        // synchronous and does not route through a host's task queue, so a crashed peer's cell
        // objects, though `MeshPeer` no longer reaches them (`replica = null`), stay fully linked
        // and keep merging/relaying inbound deltas as long as `side`/`bridgeHost` (which SURVIVE
        // a crash — see `MeshPeer`'s own KDoc) keep them wired. `Replication.evict`'s despawn has
        // no such effect: it explicitly unpublishes the DATA cell, which is why BS-11's row
        // genuinely freezes. This is filed, not asserted around:
        // concord/corpus/DISPUTES.md#CHA3-42-stall-notice-unclean-departure.
    }

    // ------------------------------------------------------------------------------- BS-11

    @Test
    fun `BS-11 EVICT_NO_CLOSE leaves a wedge the harness detects, not tolerates`() {
        val (_, world) = runDeparture("peer2", DepartureMode.EVICT_NO_CLOSE, seed = 53L)

        val peer0 = MeshPeers.require(world, "peer0")
        val peer2 = MeshPeers.require(world, "peer2")
        assertFalse(
            StabilityObservables.rowClosed(peer0, peer2),
            "the PN-0c control seam left the departed row open (closeDepartedRow=false)",
        )

        StabilityObservables.watch(peer0)
        repeat(5) { i -> assertTrue(peer0.write(400 + i)) }
        world.controller.runToIdle()
        val origin = StabilityObservables.originOf(peer0, "peer0-404")

        val failure = assertFailsWith<ChurnCheckFailure>(
            "the unclosed row must fail the stability check rather than pass silently",
        ) {
            StabilityObservables.stabilityCoversCheck(
                observerName = "peer0",
                memberNames = listOf("peer0", "peer1", "peer2"),
                origin = origin,
            ).verify(world)
        }
        assertEquals("stability failed to advance past an unclosed row", failure.message)
        assertTrue(failure.detail.contains("peer2"), "the failure names the still-open row: ${failure.detail}")
    }
}
