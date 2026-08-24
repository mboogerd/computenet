package civictech.testkit.dst

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.host.HostScheduler
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.testkit.forEachSeed
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [PartitionFault]'s contract: [CHA1-11] (traffic stops on the targeted edge, in the
 * configured direction, until the healing step), [CHA1-12] (park and drop are distinguished
 * and labelled in the report), [CHA1-13]/BS-5 (a one-way partition suppresses one direction
 * only), and [CHA1-62]/[CHA1-63]/BS-4 (a diverging control: park converges after the heal,
 * drop provably diverges).
 *
 * ## The graph, and why it is a real peering
 *
 * Two peers, each with its own [LocationRegistry] and its own [OrMapCell] replica of one
 * logical map, joined by `Peering.loopback` — the kernel's in-process peer connection, the
 * same M5 frame path a socket carries. Both directions are routed through named edges on
 * [DstWorld.edges] via the loopback's `interposeAToB`/`interposeBToA` parameters, which is the
 * kernel seam this task adds and the only reason a rig fault can reach a loopback's frames at
 * all.
 *
 * A synthetic edge graph (the shape [SelfTestGraphs] uses) would have been cheaper and would
 * have proved nothing about convergence: the [CHA1-62]/[CHA1-63] pair needs a system that
 * *can* converge, so that failing to is a finding. Replicated OR-map gossip is that system,
 * and it is the one `OrMapConvergenceTest` already establishes converges under a park-shaped
 * partition and heal.
 *
 * ## The workload is step-driven, and that is load-bearing
 *
 * Writes are issued from a [StepHooks] hook, one per controller step, so the workload and the
 * fault share the rig's single clock ([CHA1-02]) — a window `[from, until)` is a window over
 * the same steps the writes are counted in. It also guarantees the run *reaches* the healing
 * step: [Fault.onStep] is the only clock a fault has, so a run that quiesced at step 20 would
 * end still partitioned, and a park test would silently become a drop test. Every park
 * assertion below therefore also asserts the heal fired.
 */
class PartitionFaultTest {

    private companion object {
        const val A_TO_B = "peerA->peerB"
        const val B_TO_A = "peerB->peerA"

        /** Writes are issued one per step at steps `0 until WRITES`, alternating peers. */
        const val WRITES = 60

        /** The fault window, comfortably inside the write schedule at both ends. */
        const val FROM = 10
        const val UNTIL = 40

        /** Salt for the replicas' shared logical id, so it is seed-derived and not random. */
        const val ORMAP_SALT = 0x24L

        /** The single edge of the [HeldCollector] graph — the park fault's target name. */
        const val INTO_COLLECTOR = "into-collector"

        /** Salt for the collector's logical id, seed-derived for the same reason as [ORMAP_SALT]. */
        const val COLLECTOR_SALT = 0x25L
    }

    /** The dynamic-proxy view of a replica's write inlet — no KSP involved. */
    interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    /** The dynamic-proxy view of [CollectorCell]'s inlet — no KSP involved. */
    interface CollectorProxy {
        val inlet: Use<Consumer<Int>>
    }

    /**
     * A plain inlet that records what it receives, in order — the fixture `RelocationTest` and
     * `RepartitionHoldTest` use to pin the registry's park/drain ordering, reused here because
     * the property under test is the same one, driven through the rig instead of by hand.
     */
    class CollectorCell(override val ref: CellRef) : Cell {
        val received = mutableListOf<Int>()

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    received += input
                }
            })
        }
    }

    // -------------------------------------------------------------------------------------
    // the graph
    // -------------------------------------------------------------------------------------

    /**
     * Two loopback-peered OR-map replicas, their frame plane routed through two named edges.
     *
     * Holds the replicas so a test can read both views after the run: they are per-build
     * state, so one instance drives exactly one [DstRun.execute].
     */
    private class ReplicatedPair(id: String, private val writes: Int = WRITES) {

        lateinit var replicas: List<OrMapCell<String, String>>
            private set

        val spec: GraphSpec = GraphSpec(id) { world -> build(world) }

        /** Each replica's `(membership, value-per-key)` view — the convergence subject. */
        fun views(): List<Map<String, String?>> =
            replicas.map { replica -> replica.membership().associateWith { replica.value(it) } }

        fun converged(): Boolean = views().toSet().size == 1

        private fun build(world: DstWorld) {
            val registryA = world.registry
            val registryB = LocationRegistry()

            val hostA = world.hosts.declare("peerA") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = registryA)
            }
            val bridgeA = world.hosts.declare("peerA-bridge") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = registryA)
            }
            val hostB = world.hosts.declare("peerB") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = registryB)
            }
            val bridgeB = world.hosts.declare("peerB-bridge") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = registryB)
            }

            // Declared before the peering, because the interposers below deliver through them.
            world.edges.declare(A_TO_B, from = "peerA", to = "peerB")
            world.edges.declare(B_TO_A, from = "peerB", to = "peerA")

            val loop = Peering.loopback(
                Peering.Side(registryA, bridgeA.host),
                Peering.Side(registryB, bridgeB.host),
                interposeAToB = { frame -> world.edges.deliver(A_TO_B, frame) },
                interposeBToA = { frame -> world.edges.deliver(B_TO_A, frame) },
            )
            // One control, declared under both edge names, and its scope says why: severing a
            // loopback is bidirectional even though an edge is not ([CHA1-12] labelling).
            val severing = LinkControl.severing(loop)
            LinkControls.declare(world, A_TO_B, severing)
            LinkControls.declare(world, B_TO_A, severing)

            // Seed-derived so the dot source — and every (counter, sourceId) tie-break — is
            // reproducible per seed, exactly as OrMapConvergenceTest derives it.
            val logicalId = UUID(world.seed, ORMAP_SALT)
            val a = OrMapCell<String, String>(CellRef(logicalId, 0L))
            val b = OrMapCell<String, String>(CellRef(logicalId, 1L))
            replicas = listOf(a, b)
            Replication(registryA).replicate(a, hostA.host)
            Replication(registryB).replicate(b, hostB.host)
            world.cells.declare("replicaA", a.ref)
            world.cells.declare("replicaB", b.ref)

            // Settle the peering before the rig's own loop starts: the announcement handshake
            // is setup, not workload, and counting it as steps would make every window index
            // depend on how many steps a handshake happens to take.
            world.controller.runToIdle()

            val ops = listOf(
                (HostedCellProxy.create(a.ref, registryA, OrMapInletProxy::class.java) as OrMapInletProxy).inlet.call,
                (HostedCellProxy.create(b.ref, registryB, OrMapInletProxy::class.java) as OrMapInletProxy).inlet.call,
            )
            var issued = 0
            world.steps.onStep { _, _ ->
                if (issued < writes) {
                    val who = issued % 2
                    ops[who].put("k$issued", "w$who")
                    issued++
                }
            }
        }
    }

    /**
     * One host, one [CollectorCell], and a park control that is
     * `LocationRegistry.hold`/`release` on that cell's ref — the *other* [LinkControl], the
     * one whose heal genuinely drains a buffer.
     *
     * ## Why this graph is not the peered pair above
     *
     * `holding` parks deliveries at a registry, per ref. It needs no wire and no second peer:
     * a single hosted cell fed one invocation per step is the smallest graph in which "held
     * during the window, drained in park order at the heal" is observable at all, and the
     * observation is the cell's own `received` list rather than an edge trace — frames are not
     * what a registry hold stops.
     *
     * ## The keepalive is load-bearing
     *
     * Parked invocations are not scheduler work, so during the hold this graph would have
     * *nothing* to run: `controller.step()` would return false and the run would quiesce
     * mid-window, ending still held, with the heal never reached (exactly the hazard
     * [PartitionFault]'s "What it costs when the window never closes" describes). One trivial
     * task per step keeps the controller busy so the run reaches [UNTIL]. The peered graph
     * above needs no such thing because a peer's local writes are work in their own right.
     */
    private class HeldCollector(id: String, private val writes: Int = WRITES) {

        lateinit var cell: CollectorCell
            private set

        /** `received`, and whether the ref was held, as they stood at the start of each step. */
        val receivedAtStepStart = mutableMapOf<Int, List<Int>>()
        val heldAtStepStart = mutableMapOf<Int, Boolean>()

        val spec: GraphSpec = GraphSpec(id) { world -> build(world) }

        private fun build(world: DstWorld) {
            val registry = world.registry
            lateinit var scheduler: HostScheduler
            val host = world.hosts.declare("solo") { ctx ->
                scheduler = ctx.scheduler
                ManagedHost(scheduler = ctx.scheduler, registry = registry)
            }

            val collector = CollectorCell(CellRef(UUID(world.seed, COLLECTOR_SALT), 0L))
            cell = collector
            host.host.managementInlet.call.spawn(collector)
            world.cells.declare("collector", collector.ref)

            // The edge exists so the fault has a declared target to name; what the control
            // actually stops is the ref, which is precisely why LinkControl carries a scope.
            world.edges.declare(INTO_COLLECTOR, from = "solo", to = "solo")
            LinkControls.declare(world, INTO_COLLECTOR, LinkControl.holding(registry, collector.ref))

            // Settle the spawn before the rig's clock starts, as ReplicatedPair settles the
            // peering handshake, so window indices do not depend on setup length.
            world.controller.runToIdle()

            val inlet = (
                HostedCellProxy.create(collector.ref, registry, CollectorProxy::class.java) as CollectorProxy
                ).inlet.call
            var issued = 0
            world.steps.onStep { _, step ->
                receivedAtStepStart[step] = collector.received.toList()
                heldAtStepStart[step] = registry.isHeld(collector.ref)
                if (issued < writes) {
                    inlet.provide(issued++)
                    // The keepalive; see this class's kdoc. Inside the guard, so the run stops
                    // producing work once the schedule is exhausted and can actually quiesce.
                    scheduler.submit(10) { world.trace.emit(host = "solo", cell = "collector", port = "tick") }
                }
            }
        }
    }

    private fun run(seed: Long, vararg faults: Fault): Pair<DstReport, ReplicatedPair> {
        val graph = ReplicatedPair("dst-partition-ormap-pair")
        val report = DstRun(graph.spec, FaultPlan(seed, faults.toList()), budget = 20_000).execute()
        assertEquals(DstOutcome.PASSED, report.outcome, "run did not quiesce: ${report.summary()}")
        return report to graph
    }

    /** Frames that traversed [edge] at all — [Edge.deliver] emits one untagged event per frame. */
    private fun DstReport.framesOn(edge: String): List<TraceEvent> =
        trace.filter { it.port == edge && it.faultTag == null }

    /** Frames the fault fired on, i.e. frames it destroyed. */
    private fun DstReport.firingsOn(edge: String, faultId: String): List<TraceEvent> =
        trace.filter { it.port == edge && it.faultTag == faultId }

    // -------------------------------------------------------------------------------------
    // [CHA1-11] — the partition stops traffic on the edge, for the window, and only then
    // -------------------------------------------------------------------------------------

    @Test
    fun `a drop partition destroys every frame on the edge inside its window and none outside it`() {
        val (report, graph) = run(7L, PartitionFault.drop("cut", A_TO_B, from = FROM, until = UNTIL))

        val firings = report.firingsOn(A_TO_B, "cut")
        assertTrue(firings.isNotEmpty(), "the fault never fired — nothing was tested: ${report.summary()}")
        assertTrue(
            firings.all { it.step in FROM until UNTIL },
            "fault fired outside its window: ${firings.map { it.step }.filterNot { it in FROM until UNTIL }}",
        )

        // Every frame that crossed during the window was destroyed: the counts match, so no
        // frame slipped through a partition that claims to be total for its window.
        val crossedInWindow = report.framesOn(A_TO_B).count { it.step in FROM until UNTIL }
        assertEquals(crossedInWindow, firings.size, "some frames survived the partition window")

        // …and the edge carried traffic again after the healing step, so the window closed.
        assertTrue(
            report.framesOn(A_TO_B).any { it.step >= UNTIL },
            "no frame crossed after the heal — the window's end is untested",
        )

        // The trace alone would still read like this if the interposer counted frames and
        // delivered them anyway (measured: mutating FrameInterposers.drop to return the frame
        // leaves every assertion above green). What only destruction can produce is a peerB
        // that never learned what peerA wrote into the window.
        val (viewA, viewB) = graph.views()
        assertTrue(viewB.keys.size < viewA.keys.size, "nothing was actually lost: $viewB")
    }

    @Test
    fun `the fault is reported as fired, with its activation steps, and is never inert`() {
        val (report, _) = run(7L, PartitionFault.drop("cut", A_TO_B, from = FROM, until = UNTIL))

        val applied = report.appliedFaults.single()
        assertEquals("cut", applied.id)
        assertTrue(applied.fired > 0, "a partition that never fired must not read as applied")
        assertTrue(report.inertFaults.isEmpty(), "reported inert: ${report.inertFaults}")
        assertTrue(
            applied.activationSteps.all { it in FROM until UNTIL },
            "activation steps outside the window: ${applied.activationSteps}",
        )
    }

    // -------------------------------------------------------------------------------------
    // [CHA1-13] / BS-5 — one-way
    // -------------------------------------------------------------------------------------

    @Test
    fun `BS-5 - a one-way partition suppresses one direction and leaves the other delivering`() {
        val (report, graph) = run(7L, PartitionFault.drop("cut", A_TO_B, from = FROM, until = UNTIL))

        // nothing fired on the untargeted edge…
        assertTrue(
            report.firingsOn(B_TO_A, "cut").isEmpty(),
            "the fault touched the direction it does not target",
        )
        // …and that direction genuinely carried frames while the other one was cut.
        assertTrue(
            report.framesOn(B_TO_A).any { it.step in FROM until UNTIL },
            "peerB->peerA carried nothing during the window, so 'one-way' is untested here",
        )

        // The asymmetry is visible in the data, not only in the trace: peerA received
        // everything peerB wrote, while peerB is missing what peerA wrote into the window.
        val (viewA, viewB) = graph.views()
        val written = (0 until WRITES).map { "k$it" }.toSet()
        assertEquals(written, viewA.keys, "peerA should have every write — its inbound edge was open")
        assertTrue(viewB.keys.size < written.size, "peerB lost nothing, so the drop was not directional")
        assertTrue(viewA.keys.containsAll(viewB.keys), "peerB holds a key peerA never saw")
    }

    // -------------------------------------------------------------------------------------
    // [CHA1-12] — park and drop are different faults, and the report says which fired
    // -------------------------------------------------------------------------------------

    @Test
    fun `the report labels park and drop distinctly, including the park control's real reach`() {
        val (dropped, _) = run(7L, PartitionFault.drop("cut", A_TO_B, from = FROM, until = UNTIL))
        val (parked, _) = run(7L, PartitionFault.park("cut", A_TO_B, from = FROM, until = UNTIL))

        val dropLabel = dropped.appliedFaults.single().description
        val parkLabel = parked.appliedFaults.single().description
        assertNotEquals(dropLabel, parkLabel)
        assertTrue("DROP" in dropLabel && "destroyed" in dropLabel, dropLabel)
        assertTrue("PARK" in parkLabel && "without being destroyed" in parkLabel, parkLabel)
        // the control's scope is wider than the edge, and the label admits it
        assertTrue("both directions" in parkLabel, parkLabel)
        // …and the label reports what THIS control's heal does, rather than repeating
        // PartitionMode.PARK's spec sentence. A severing control replays nothing: it opens a
        // fresh connection and re-announces. The label used to claim "traffic parks and
        // replays on heal" for it, which was false of every park run this suite has ever
        // executed (computenet-cstu).
        assertTrue("re-announces" in parkLabel, parkLabel)
    }

    /**
     * The other park control, and the only one whose heal literally replays what the window
     * held — so it is the one that has to prove the claim its [LinkControl.scope] makes
     * ([CHA1-12] labelling, and the acceptance of computenet-cstu).
     *
     * `LinkControl.holding` had no caller and no test in the repo, while every park run in
     * this suite used `severing`. That is why the report's old "replays on heal" wording was
     * never caught by a test: nothing exercised a control that replays.
     */
    @Test
    fun `a park backed by LocationRegistry hold withholds the window's traffic and drains it in park order`() {
        val graph = HeldCollector("dst-partition-held-collector")
        val report = DstRun(
            graph.spec,
            FaultPlan.of(7L, PartitionFault.park("cut", INTO_COLLECTOR, from = FROM, until = UNTIL)),
            budget = 20_000,
        ).execute()
        assertEquals(DstOutcome.PASSED, report.outcome, "run did not quiesce: ${report.summary()}")

        // Both endpoints fired: without the release, the assertions below would be about a run
        // that ended still held, and "drained on heal" would be untested.
        assertEquals(listOf(FROM, UNTIL), report.appliedFaults.single().activationSteps)

        // The control was engaged for the whole window. It starts at the step AFTER `from`
        // because `Fault.onStep` is registered after the graph builder's hooks, so at step
        // `from` this snapshot is taken (and that step's write issued) before the park lands.
        assertTrue(
            (FROM + 1..UNTIL).all { graph.heldAtStepStart[it] == true },
            "the ref was not held across the window: ${graph.heldAtStepStart}",
        )

        // Nothing issued inside the window arrived while it was held. Values are the issue
        // index, which equals the step, so the 30 values FROM+1..UNTIL are exactly the writes
        // the hold was covering — none of them is in the cell yet at the healing step.
        // A no-op control leaves this list running up to UNTIL-1 and fails here, which is
        // what makes this an assertion about the control rather than about the trace.
        val atHeal = graph.receivedAtStepStart.getValue(UNTIL)
        assertTrue(
            atHeal.all { it <= FROM },
            "values issued inside the park window arrived before the heal: $atHeal",
        )

        // …and the release drained every one of them, in park order, on top of a prefix that
        // was never parked: the whole schedule, in issue order, with nothing lost or reordered.
        // This is the property `severing` cannot support and `holding` exists for.
        assertEquals((0 until WRITES).toList(), graph.cell.received)
    }

    @Test
    fun `the report labels a hold-backed park by what the hold does, not by the mode's spec sentence`() {
        val graph = HeldCollector("dst-partition-held-collector")
        val report = DstRun(
            graph.spec,
            FaultPlan.of(7L, PartitionFault.park("cut", INTO_COLLECTOR, from = FROM, until = UNTIL)),
            budget = 20_000,
        ).execute()

        val label = report.appliedFaults.single().description
        assertTrue("PARK" in label && "without being destroyed" in label, label)
        // The replay claim belongs to this control and appears only for it.
        assertTrue("in park order" in label, label)
        assertTrue("both directions" !in label, label)
    }

    @Test
    fun `a park partition parks at the opening step and replays at the healing step`() {
        val (report, graph) = run(7L, PartitionFault.park("cut", A_TO_B, from = FROM, until = UNTIL))

        // Both endpoints fired: without the second, the run ended partitioned and any
        // convergence claim below would be about a graph that was never healed.
        assertEquals(listOf(FROM, UNTIL), report.appliedFaults.single().activationSteps)
        assertEquals(2, report.appliedFaults.single().fired)
        assertTrue(graph.converged(), "park + heal did not converge: ${graph.views()}")

        // [CHA1-11] for PARK: the control actually stopped the traffic. Everything above is
        // also true of a park control that does nothing at all — the activation steps come
        // from the trace, which `onStep` emits either way, and a graph that was never
        // partitioned converges trivially. Measured during review: replacing
        // `LinkControl.severing`'s park/heal with no-ops leaves all eleven tests in this
        // class green without these three assertions.
        //
        // Exactly zero, not "fewer": `Fault.onStep` runs before the controller drives the
        // step, so nothing is in flight across the seam when the sever lands. Both directions,
        // because severing a loopback is bidirectional — which is what the control's `scope`
        // claims and what [CHA1-12]'s label reports.
        assertEquals(
            0,
            report.framesOn(A_TO_B).count { it.step in FROM until UNTIL },
            "frames crossed peerA->peerB while the partition was parked",
        )
        assertEquals(
            0,
            report.framesOn(B_TO_A).count { it.step in FROM until UNTIL },
            "frames crossed peerB->peerA while parked, so the control's stated bidirectional " +
                "scope is not what it did",
        )
        assertTrue(
            report.framesOn(A_TO_B).any { it.step >= UNTIL },
            "no frame crossed after the heal — the replay half of park is untested",
        )
    }

    // -------------------------------------------------------------------------------------
    // [CHA1-62] / [CHA1-63] / BS-4 — the diverging control
    // -------------------------------------------------------------------------------------

    @Test
    fun `BS-4 - the fault-free control converges, so divergence below is the fault's doing`() {
        forEachSeed(0L until 8L) { seed ->
            val (report, graph) = run(seed)
            assertTrue(report.appliedFaults.isEmpty())
            assertTrue(graph.converged(), "seed $seed did not converge with no fault at all: ${graph.views()}")
        }
    }

    @Test
    fun `BS-4 - park partition plus heal converges on every seed`() {
        forEachSeed(0L until 8L) { seed ->
            val (report, graph) = run(seed, PartitionFault.park("cut", A_TO_B, from = FROM, until = UNTIL))
            assertEquals(
                listOf(FROM, UNTIL),
                report.appliedFaults.single().activationSteps,
                "seed $seed never healed",
            )
            assertTrue(graph.converged(), "seed $seed diverged after a park and heal: ${graph.views()}")
        }
    }

    @Test
    fun `BS-4 - the drop control provably diverges, which is what makes the park run mean something`() {
        val diverged = mutableListOf<Long>()
        for (seed in 0L until 8L) {
            val (report, graph) = run(seed, PartitionFault.drop("cut", A_TO_B, from = FROM, until = UNTIL))
            assertTrue(report.appliedFaults.single().fired > 0, "seed $seed dropped nothing")
            if (!graph.converged()) diverged += seed
        }
        // If this fails, the rig's own self-test fails: a control that cannot be made to
        // diverge cannot certify that the converging run converged for a reason.
        assertTrue(
            diverged.isNotEmpty(),
            "no seed diverged under a drop partition — the control is inert and proves nothing",
        )
    }

    // -------------------------------------------------------------------------------------
    // configuration errors fail loudly, before the run
    // -------------------------------------------------------------------------------------

    @Test
    fun `park on an edge with no declared control fails at install, naming what was declared`() {
        val graph = GraphSpec("dst-partition-no-control") { world ->
            world.edges.declare("lonely")
            world.hosts.declare("h") { ctx -> ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry) }
        }
        val error = assertFailsWith<IllegalStateException> {
            DstRun(graph, FaultPlan.of(1L, PartitionFault.park("cut", "lonely", from = 1, until = 2))).execute()
        }
        assertTrue("lonely" in error.message!! && "LinkControls.declare" in error.message!!, error.message!!)
    }

    @Test
    fun `an unknown edge fails target validation before anything is installed`() {
        val graph = GraphSpec("dst-partition-unknown-edge") { world ->
            world.edges.declare("real")
            world.hosts.declare("h") { ctx -> ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry) }
        }
        val error = assertFailsWith<UnknownFaultTargetException> {
            DstRun(graph, FaultPlan.of(1L, PartitionFault.drop("cut", "typo", from = 0, until = 1))).execute()
        }
        assertEquals("cut", error.faultId)
        assertEquals(setOf("real"), error.known)
    }

    @Test
    fun `an empty window is refused at construction rather than reported inert`() {
        assertFailsWith<IllegalArgumentException> { StepWindow(10, 10) }
        assertFailsWith<IllegalArgumentException> { PartitionFault.drop("cut", A_TO_B, from = 5, until = 3) }
    }
}
