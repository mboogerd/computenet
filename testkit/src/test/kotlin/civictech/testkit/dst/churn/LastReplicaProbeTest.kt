package civictech.testkit.dst.churn

import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstOutcome
import civictech.testkit.dst.DstReport
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.GraphRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BS-12 and BS-13 — the last-replica probe ([CHA3-32], [CHA3-61], [CHA3-62]; feature
 * computenet-umx.2 §2.4, §4.4, §4.7).
 *
 * **BS-12** ([CHA3-32], [CHA3-61]): a plan evicts replicas until one remains and then attempts
 * departure of that last one. The kernel refuses — `Replication.evict` returns `false` and
 * suspends instead of despawning (`Replication.kt`'s `reachablePeers.isEmpty()` arm) — the
 * replica keeps its state, and the probe records whether that state is present at any durable
 * store. Every number is a [LastReplicaReport] field, not a log line, because the findings task
 * serialises the report.
 *
 * **BS-13** ([CHA3-62]): the same collapse, but the last replica is *crashed* rather than
 * evicted. The probe records what survived and where — journals, location directories,
 * delivered-watermark rows — and the report carries [NO_HANDOFF_DEFINED] verbatim.
 *
 * ## What this file deliberately does not do
 *
 * It defines no handoff, proposes none, and repairs nothing ([CHA3-84]). The refusal is the
 * observation; G-45's second clause stays undesigned and MEM2's. That is asserted, not merely
 * intended: the last test pins [NO_HANDOFF_DEFINED] into both reports' rendered summaries, so a
 * later edit that softened or dropped the statement goes red.
 *
 * ## The durable-store reading on this mesh, stated plainly
 *
 * [ChurnMesh] declares **no** journals — its `MeshPeer` hosts are built without a `journalFor`
 * selector — so `world.journals.names()` is empty and every reading below finds no durable
 * store. That is not a gap in the probe: it is the measured answer for this graph, and it is
 * exactly what makes the last-replica condition ([LastReplicaReport.lastReplicaCondition]) TRUE
 * here. The probe reads whatever journals a graph *did* declare, so a future mesh with durable
 * hosts gets a different, equally honest, answer from the same code — pinned by the second
 * BS-12 test below, which declares a journal on the world and watches the reading change.
 *
 * Every green test asserts the exact set of fired fault ids (`DepartureGatesTest`'s rule,
 * repeated rather than assumed) and every failure message is the fixed identity with the numbers
 * in [ChurnCheckFailure.detail].
 */
class LastReplicaProbeTest {

    private companion object {
        const val BUDGET = 40_000
    }

    // ------------------------------------------------------------------------------- fixtures

    private fun firedIds(report: DstReport): Set<String> =
        report.appliedFaults.filter { it.fired > 0 }.map { it.id }.toSet()

    /**
     * Run [plan] on a churn mesh, keeping the run alive to the plan's own horizon.
     *
     * `aliveUntil = plan.stepBudget` is load-bearing: [ChurnMesh.spec] defaults it to one past
     * the last scheduled *write*, and every collapse event of a last-replica plan is scheduled
     * long after the workload drains. Left at the default, the run would quiesce before the
     * first eviction and the probe would observe a mesh that never collapsed — which
     * [ChurnMesh.allEventsFired] would catch, but only after the fact.
     *
     * [decorate] runs after the mesh is built, for a test that needs a step hook of its own
     * (BS-13's pre-crash fold sample). It is a separate registered graph id per test so nothing
     * leaks between them.
     */
    private fun execute(
        plan: ChurnPlan,
        graphId: String,
        decorate: (DstWorld) -> Unit = {},
    ): Pair<DstReport, DstWorld> {
        val base = ChurnMesh.spec(
            plan,
            payload = MeshPayload.SET,
            maxPeers = plan.peers.size,
            aliveUntil = plan.stepBudget,
        )
        GraphRegistry.unregister(graphId)
        val spec = GraphRegistry.register(graphId) { world ->
            base.builder.build(world)
            decorate(world)
        }
        var captured: DstWorld? = null
        val report = DstRun(
            graph = spec,
            plan = plan.toFaultPlan(),
            budget = BUDGET,
            check = DstCheck { world -> captured = world },
        ).execute()
        val world = captured ?: fail(
            "the run never quiesced (${report.outcome}, ${report.steps}/${report.budget} steps), " +
                "so no check ran and nothing was observed",
        )
        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        assertEquals(plan.events.map { it.id }.toSet(), firedIds(report), report.summary())
        return report to world
    }

    // ---------------------------------------------------------------------------------- BS-12

    @Test
    fun `BS-12 the last replica's departure is refused, and the refusal is what the probe records`() {
        val plan = LastReplicaProbe.downToOne(seed = 71L)
        val (_, world) = execute(plan, "last-replica-down-to-one-71")

        val report = LastReplicaProbe.observeLastReplica(world)

        // The collapse actually happened: two peers left, one stands.
        assertEquals(listOf("peer1", "peer2"), report.departed.sorted(), report.summary())
        assertEquals(
            0,
            report.reachablePeers,
            "replicasOf(id) - {local} is empty on the survivor — the exact set evict gates on: ${report.summary()}",
        )

        // [CHA3-61]: the kernel REFUSED. Not repaired, not routed around — recorded.
        assertFalse(report.evictDespawned, "evict must return false when no peer is reachable: ${report.summary()}")
        assertTrue(report.stillMember, "a refused eviction never despawns — state is retained: ${report.summary()}")
        assertTrue(
            report.rowSuspended,
            "the other half of the refusal: the survivor's own delivered-watermark row reads " +
                "SUSPENDED, not CLOSED: ${report.summary()}",
        )

        // [CHA3-32]: effective state, and where it is (not).
        assertTrue(report.holdsEffectiveState, "the survivor holds a non-empty fold: ${report.summary()}")
        assertEquals(
            emptyList(),
            report.durableStores,
            "ChurnMesh declares no journals, so there is no durable store on this graph at all",
        )
        assertFalse(report.atSomeDurableStore, report.summary())
        assertTrue(
            report.lastReplicaCondition,
            "the last-replica condition: effective state present at no durable store — the " +
                "observation MEM2 designs against: ${report.summary()}",
        )
    }

    @Test
    fun `BS-12 the durable-store reading is a real read of the graph's journals, not a constant`() {
        val plan = LastReplicaProbe.downToOne(seed = 73L)
        // Declare a journal on the world and append to it. The probe must see it — otherwise
        // `atSomeDurableStore=false` above would be an artifact of the reading, not of the graph.
        val (_, world) = execute(plan, "last-replica-down-to-one-73") { w ->
            w.journals.declare("probe-store").append(byteArrayOf(1, 2, 3))
        }

        val report = LastReplicaProbe.observeLastReplica(world)

        assertEquals(
            listOf(DurableStoreReading("probe-store", records = 1, decorated = false)),
            report.durableStores,
            report.summary(),
        )
        assertTrue(report.atSomeDurableStore, report.summary())
        assertTrue(report.holdsEffectiveState, report.summary())
        assertFalse(
            report.lastReplicaCondition,
            "with a declared journal holding a record, this graph no longer reports the " +
                "last-replica condition — the coarse direction the report's KDoc states: ${report.summary()}",
        )
        // The refusal is unchanged by the reading: still the same kernel behaviour.
        assertFalse(report.evictDespawned, report.summary())
    }

    @Test
    fun `BS-12 the refusal check fails loudly when the last departure despawns`() {
        val plan = LastReplicaProbe.downToOne(seed = 79L)
        val (_, world) = execute(plan, "last-replica-down-to-one-79")

        // The real behaviour passes the check.
        LastReplicaProbe.refusalObserved().verify(world)

        // And the check is not vacuous: pointed at a peer that DID despawn (one of the evicted
        // ones, whose `evict` returned true because peers were still reachable), it fails with
        // the fixed identity and the numbers in the detail.
        val failure = assertFailsWith<ChurnCheckFailure> {
            LastReplicaProbe.refusalObserved(survivor = "peer2").verify(world)
        }
        assertEquals("the last replica's departure was not refused", failure.message)
        assertTrue(failure.detail.contains("evict despawned=true"), failure.detail)
    }

    // ---------------------------------------------------------------------------------- BS-13

    @Test
    fun `BS-13 crashing the last replica leaves state at no durable store, and CHA3 defines no handoff`() {
        val plan = LastReplicaProbe.downToZero(seed = 83L)
        // The crashed peer's fold cannot be recovered afterwards — that IS the finding — so it is
        // sampled from a step hook while the replica still exists.
        var lastFold: Any? = null
        val (_, world) = execute(plan, "last-replica-down-to-zero-83") { w ->
            w.steps.onStep { inner, _ ->
                MeshPeers.find(inner, "peer0")?.takeIf { it.member }?.foldSnapshot()?.let { lastFold = it }
            }
        }

        val report = LastReplicaProbe.observeZeroReplicas(world, lastFoldBeforeCrash = lastFold)

        assertEquals(1, report.crashGeneration, "the crash discarded and rebuilt peer0's host: ${report.summary()}")
        assertEquals(emptyList(), report.liveMembers, "membership is zero: ${report.summary()}")
        assertTrue(
            (report.lastFoldBeforeCrash as? Collection<*>)?.isNotEmpty() == true,
            "state existed before the crash — otherwise 'what survives' is a question about nothing: " +
                "${report.summary()}",
        )

        // What survives, and where. Three independent stores, reported independently.
        assertEquals(emptyList(), report.durableStores, "no journal was declared: ${report.summary()}")
        assertFalse(report.atSomeDurableStore, report.summary())

        // The directory: a crash despawns nothing and unpublishes nothing, so peer0's own
        // registry still names a replica no host serves. Recorded, not repaired.
        val peer0Directory = report.registryReadings.single { it.peer == "peer0" }
        assertFalse(peer0Directory.hostsReplica, "the crashed peer holds no replica: ${report.summary()}")
        assertTrue(
            peer0Directory.publishedInstances.isNotEmpty(),
            "the crashed peer's location directory survived the crash and still publishes its " +
                "own instance — a dangling entry: ${report.summary()}",
        )
        assertTrue(
            report.danglingDirectoryEntries.any { it.peer == "peer0" },
            report.summary(),
        )

        // The delivered-watermark rows: the peers that departed cleanly BEFORE the crash still
        // carry companions, and the crashed peer's own linker was rebuilt empty.
        val crashedRows = report.watermarkRows.single { it.peer == "peer0" }
        assertFalse(
            crashedRows.companionPresent,
            "peer0's Replication was rebuilt on the crash, so its watermark companion went with " +
                "it: ${report.summary()}",
        )
        assertTrue(
            report.watermarkRows.any { it.peer != "peer0" && it.companionPresent && it.closedRows > 0 },
            "a cleanly-departed peer's companion outlived the mesh and still carries closed rows: " +
                "${report.summary()}",
        )

        // [CHA3-62]: the statement, verbatim, in the report a findings pass renders.
        assertTrue(report.summary().contains(NO_HANDOFF_DEFINED), report.summary())
    }

    // ----------------------------------------------------------------------------- boundaries

    @Test
    fun `both reports carry the no-handoff statement verbatim, and it names MEM2 and G-45`() {
        assertTrue(NO_HANDOFF_DEFINED.contains("CHA3 defines NO last-replica handoff"), NO_HANDOFF_DEFINED)
        assertTrue(NO_HANDOFF_DEFINED.contains("G-45"), NO_HANDOFF_DEFINED)
        assertTrue(NO_HANDOFF_DEFINED.contains("MEM2"), NO_HANDOFF_DEFINED)
        assertTrue(NO_HANDOFF_DEFINED.contains("UNDESIGNED"), NO_HANDOFF_DEFINED)

        val lastReplica = LastReplicaReport(
            survivor = "peer0",
            departed = listOf("peer1"),
            reachablePeers = 0,
            evictDespawned = false,
            stillMember = true,
            rowSuspended = true,
            effectiveState = listOf("peer0-0"),
            durableStores = emptyList(),
        )
        val zeroReplica = ZeroReplicaReport(
            crashedPeer = "peer0",
            crashGeneration = 1,
            liveMembers = emptyList(),
            lastFoldBeforeCrash = listOf("peer0-0"),
            durableStores = emptyList(),
            registryReadings = emptyList(),
            watermarkRows = emptyList(),
        )
        assertTrue(lastReplica.summary().contains(NO_HANDOFF_DEFINED))
        assertTrue(zeroReplica.summary().contains(NO_HANDOFF_DEFINED))
    }

    // --------------------------------------------------------------------------- [CHA3-53]

    @Test
    fun `the churn seed stream is a reusable entry point that agrees with the generator`() {
        val config = ChurnConfig(peerCount = 3..4, eventCount = 4, opScriptLength = 6)

        assertEquals(listOf(10L, 11L, 12L), ChurnSeeds.seeds(10L..12L))

        val plans = ChurnSeeds.plans(10L..12L, config)
        assertEquals(3, plans.size)
        assertEquals(listOf(10L, 11L, 12L), plans.map { it.seed })
        // The stream is the generator, not a second derivation: seed-for-seed identity, so an
        // E3.5 consumer and a CHA3 sweep over the same range run the SAME adversary.
        plans.forEach { assertEquals(ChurnGenerator.generate(it.seed, config), it) }
        assertEquals(plans, ChurnSeeds.planSequence(10L..12L, config).toList())

        // And a default-config call is still the default generator's plan.
        assertEquals(ChurnGenerator.generate(5L), ChurnSeeds.plans(5L..5L).single())
    }
}
