package civictech.testkit.dst

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.MapOps
import civictech.cell.data.OrMapCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.InvocationSink
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
 * [DuplicateFault]'s contract: [CHA1-16] (a byte-identical copy is re-delivered the configured
 * number of times, on either plane) and [CHA1-62]/[CHA1-63]/BS-6 (a tagged set over a bridge
 * absorbs duplicated frames on every seed, while a control that counts instead of folding
 * double-counts and is caught).
 *
 * ## Why a real bridge, and why a tagged set
 *
 * The property BS-6 is about — idempotence under redelivery — is a property of the *fold*, and
 * only a graph that actually folds can exhibit it. Two loopback-peered `OrMapCell` replicas are
 * that graph: an OR-map is a tagged set, its deltas carry dots, and re-applying a dot it has
 * already seen is defined to be a no-op. A synthetic edge graph would have been far cheaper and
 * would have proved nothing.
 *
 * The frames are duplicated **without inspecting their type** — data and protocol frames alike.
 * That is a strictly stronger adversary than the epic's "duplicate every protocol frame", and
 * it keeps [FrameInterposers.duplicating] free of any dependency on the wire codec.
 *
 * ## The controls, and why the bookkeeping is not one
 *
 * A duplicate fault that fired *n* times proves only that the interposer ran. Every assertion
 * here that matters is therefore about a consequence the graph produced:
 *
 *  - **The duplication is real**: the edge hands the peer strictly more frames than the graph
 *    put into it ([framesDelivered] vs [framesOffered], both counted by the graph builder, on
 *    the far side of the interposer chain). This fails if [FrameInterposers.duplicating] is
 *    replaced by the identity.
 *  - **The diverging control** ([CHA1-63]) is exactly that count: a consumer that counted
 *    arrivals instead of folding them would be wrong by the surplus. The converging run is the
 *    OR-map fold absorbing the same surplus.
 *  - **The invocation plane's control** is a [CountingCell], which sums what it is handed and
 *    therefore double-counts a duplicated invocation, next to an OR-map on the same plane that
 *    does not.
 */
class DuplicateFaultTest {

    private companion object {
        const val A_TO_B = "peerA->peerB"
        const val B_TO_A = "peerB->peerA"

        /** Writes issued one per controller step, alternating peers. */
        const val WRITES = 24

        /** BS-6's sweep. 100 seeds, as [CHA1-62] asks for. */
        val SWEEP = 0L until 100L

        /** Salt for the replicas' shared logical id, so it is seed-derived and not random. */
        const val ORMAP_SALT = 0x66L
    }

    /** The dynamic-proxy view of a replica's write inlet — no KSP involved. */
    interface OrMapInletProxy {
        val inlet: Use<MapOps<String, String>>
    }

    interface IntInlet {
        val inlet: Use<Propagate<Int>>
    }

    /**
     * The diverging control in cell form: it *counts* what arrives instead of folding it, so a
     * duplicated delivery is a wrong answer rather than a no-op.
     */
    class CountingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val seen = mutableListOf<Int>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())

        init {
            inlet.onEach { n -> seen += n }
        }
    }

    // -------------------------------------------------------------------------------------
    // the frame-plane graph: two loopback-peered OR-map replicas
    // -------------------------------------------------------------------------------------

    /** Two loopback-peered OR-map replicas, their frame plane routed through two named edges. */
    private class ReplicatedPair(id: String, private val writes: Int = WRITES) {

        lateinit var replicas: List<OrMapCell<String, String>>
            private set

        /** Frames the graph handed to the interposer chain on [A_TO_B]. */
        var framesOffered: Int = 0
            private set

        /** Frames the chain handed back, i.e. what peerB was actually asked to decode. */
        var framesDelivered: Int = 0
            private set

        val spec: GraphSpec = GraphSpec(id) { world -> build(world) }

        fun views(): List<Map<String, String?>> =
            replicas.map { replica -> replica.membership().associateWith { replica.value(it) } }

        fun converged(): Boolean = views().toSet().size == 1

        /** The batch fold of the workload: every key written once, by the peer whose turn it was. */
        fun batchFold(): Map<String, String?> = (0 until writes).associate { "k$it" to "w${it % 2}" }

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

            world.edges.declare(A_TO_B, from = "peerA", to = "peerB")
            world.edges.declare(B_TO_A, from = "peerB", to = "peerA")

            Peering.loopback(
                Peering.Side(registryA, bridgeA.host),
                Peering.Side(registryB, bridgeB.host),
                interposeAToB = { frame ->
                    // Counted only while the rig is driving a step. The peering handshake below
                    // runs at build time, before any fault is installed, so counting its frames
                    // would put a fixed unduplicated offset into every ratio asserted here.
                    val counted = world.step >= 0
                    if (counted) framesOffered++
                    world.edges.deliver(A_TO_B, frame).also { if (counted) framesDelivered += it.size }
                },
                interposeBToA = { frame -> world.edges.deliver(B_TO_A, frame) },
            )

            val logicalId = UUID(world.seed, ORMAP_SALT)
            val a = OrMapCell<String, String>(CellRef(logicalId, 0L))
            val b = OrMapCell<String, String>(CellRef(logicalId, 1L))
            replicas = listOf(a, b)
            Replication(registryA).replicate(a, hostA.host)
            Replication(registryB).replicate(b, hostB.host)
            world.cells.declare("replicaA", a.ref)
            world.cells.declare("replicaB", b.ref)

            // The announcement handshake is setup, not workload: counting it as steps would make
            // every window index depend on how long a handshake happens to take.
            world.controller.runToIdle()

            val ops = listOf(
                (HostedCellProxy.create(a.ref, registryA, OrMapInletProxy::class.java) as OrMapInletProxy).inlet.call,
                (HostedCellProxy.create(b.ref, registryB, OrMapInletProxy::class.java) as OrMapInletProxy).inlet.call,
            )
            var issued = 0
            world.steps.onStep { _, _ ->
                if (issued < writes) {
                    ops[issued % 2].put("k$issued", "w${issued % 2}")
                    issued++
                }
            }
        }
    }

    private fun run(seed: Long, vararg faults: Fault): Pair<DstReport, ReplicatedPair> {
        val graph = ReplicatedPair("dst-duplicate-ormap-pair")
        val report = DstRun(graph.spec, FaultPlan(seed, faults.toList()), budget = 20_000).execute()
        assertEquals(DstOutcome.PASSED, report.outcome, "run did not quiesce: ${report.summary()}")
        return report to graph
    }

    // -------------------------------------------------------------------------------------
    // [CHA1-16] — the copy is byte-identical, and there are `copies` of it
    // -------------------------------------------------------------------------------------

    @Test
    fun `the frame interposer re-delivers byte-identical copies, original first, distinct arrays`() {
        val frame = byteArrayOf(1, 2, 3)
        val out = FrameInterposers.duplicating(copies = 2, rng = java.util.Random(1L)).apply(frame, 0)

        assertEquals(3, out.size)
        assertTrue(out[0] === frame, "the original frame must be delivered first, unchanged")
        out.drop(1).forEach { copy ->
            assertTrue(copy.contentEquals(frame), "a duplicate must be byte-identical: ${copy.toList()}")
            assertTrue(copy !== frame, "a duplicate must be a distinct array — a decoder may mutate it")
        }
    }

    @Test
    fun `duplication is confined to the activation window and consumes no randomness outside it`() {
        val interposer = FrameInterposers.duplicating(
            copies = 1,
            probability = 1.0,
            rng = java.util.Random(1L),
            window = StepWindow(2, 4),
        )
        assertEquals(1, interposer.apply(byteArrayOf(0), 1).size, "duplicated before the window opened")
        assertEquals(2, interposer.apply(byteArrayOf(0), 2).size)
        assertEquals(2, interposer.apply(byteArrayOf(0), 3).size)
        assertEquals(1, interposer.apply(byteArrayOf(0), 4).size, "duplicated at the healing step")
    }

    @Test
    fun `a duplicate fault actually multiplies the frames the peer is asked to decode`() {
        val (report, graph) = run(7L, DuplicateFault.frames("dup", A_TO_B, copies = 1))

        assertTrue(graph.framesOffered > 0, "no frame crossed the edge — nothing was tested")
        // The observable consequence, not the bookkeeping: replacing the primitive with the
        // identity leaves the trace and the fired count intact and fails exactly this.
        assertTrue(
            graph.framesDelivered > graph.framesOffered,
            "the edge delivered no more than it was offered (${graph.framesDelivered} vs " +
                "${graph.framesOffered}), so nothing was duplicated",
        )
        assertEquals(2 * graph.framesOffered, graph.framesDelivered, "copies=1 must double the traffic")
        assertTrue(report.appliedFaults.single().fired > 0)
        assertTrue(report.inertFaults.isEmpty(), "reported inert: ${report.inertFaults}")
    }

    @Test
    fun `copies is the number of extra deliveries, not the total`() {
        val (_, graph) = run(7L, DuplicateFault.frames("dup", A_TO_B, copies = 3))
        assertEquals(4 * graph.framesOffered, graph.framesDelivered)
    }

    @Test
    fun `the report labels the plane, the target, the probability and the copy count`() {
        val (frames, _) = run(7L, DuplicateFault.frames("dup", A_TO_B, copies = 2, probability = 0.5))
        val label = frames.appliedFaults.single().description
        assertTrue("FRAMES" in label && A_TO_B in label && "p=0.5" in label && "2 more" in label, label)
    }

    // -------------------------------------------------------------------------------------
    // BS-6 — [CHA1-62] / [CHA1-63] over 100 seeds
    // -------------------------------------------------------------------------------------

    @Test
    fun `BS-6 - the fault-free control converges to the batch fold, so divergence below is the fault's`() {
        forEachSeed(SWEEP) { seed ->
            val (report, graph) = run(seed)
            assertTrue(report.appliedFaults.isEmpty())
            assertEquals(graph.batchFold(), graph.views()[0], "peerA does not hold the batch fold with no fault")
            assertTrue(graph.converged(), "did not converge with no fault at all")
        }
    }

    @Test
    fun `BS-6 - every frame duplicated - both views equal the batch fold on every one of 100 seeds`() {
        forEachSeed(SWEEP) { seed ->
            val (report, graph) = run(seed, DuplicateFault.frames("dup", A_TO_B, copies = 1))

            assertTrue(report.appliedFaults.single().fired > 0, "nothing was duplicated")
            assertTrue(graph.framesDelivered > graph.framesOffered, "no surplus frames were delivered")

            // The property: the tagged set absorbed every redelivery. Both replicas, because a
            // duplicate that corrupted only the receiving side would leave the sender right.
            val fold = graph.batchFold()
            val (viewA, viewB) = graph.views()
            assertEquals(fold, viewA, "peerA diverged from the batch fold under duplication")
            assertEquals(fold, viewB, "peerB diverged from the batch fold under duplication")
        }
    }

    @Test
    fun `BS-6 - probabilistic duplication over 100 seeds is absorbed, and does fire on every seed`() {
        forEachSeed(SWEEP) { seed ->
            val (report, graph) = run(seed, DuplicateFault.frames("dup", A_TO_B, probability = 0.5))
            assertTrue(report.appliedFaults.single().fired > 0, "p=0.5 duplicated nothing at all")
            assertEquals(graph.batchFold(), graph.views()[1], "peerB diverged under probabilistic duplication")
            assertTrue(graph.converged(), "the replicas diverged under probabilistic duplication")
        }
    }

    @Test
    fun `BS-6 - the counting control diverges, which is what makes the fold above mean something`() {
        // A consumer that counted arrivals instead of folding them would be wrong by exactly
        // the surplus the fold absorbed. If this ever stops being true the rig's own self-test
        // fails: a duplicate fault that cannot make a counter wrong is inert.
        val diverged = mutableListOf<Long>()
        for (seed in 0L until 100L) {
            val (_, graph) = run(seed, DuplicateFault.frames("dup", A_TO_B, copies = 1))
            if (graph.framesDelivered != graph.framesOffered) diverged += seed
        }
        assertTrue(
            diverged.isNotEmpty(),
            "no seed delivered a surplus frame — the duplicate control is inert and proves nothing",
        )
    }

    // -------------------------------------------------------------------------------------
    // the invocation plane
    // -------------------------------------------------------------------------------------

    /**
     * One host, one cell, and its proxy's sink routed through a declared [InvocationPoint]: the
     * shape of a graph with no frames at all, where a frame-plane-only duplicate fault would
     * report itself applied and fire zero times.
     */
    private class SingleHostPoint(id: String, val counting: Boolean, private val writes: Int = 12) {

        lateinit var counter: CountingCell
            private set
        lateinit var map: OrMapCell<String, String>
            private set

        val spec: GraphSpec = GraphSpec(id) { world -> build(world) }

        fun batchFold(): Map<String, String?> = (0 until writes).associate { "k$it" to "v$it" }

        private fun build(world: DstWorld) {
            val registry = world.registry
            val host = world.hosts.declare("solo") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = registry)
            }
            val point = InvocationPoints.declare(world, "solo-inlet", InvocationSink(registry::deliver))

            if (counting) {
                val cell = CountingCell()
                counter = cell
                host.host.managementInlet.call.spawn(cell)
                world.cells.declare("counter", cell.ref)
                world.controller.runToIdle()
                val proxy = HostedCellProxy.create(cell.ref, point, IntInlet::class.java) as IntInlet
                var issued = 0
                world.steps.onStep { _, _ ->
                    if (issued < writes) proxy.inlet.call.propagate(issued++)
                }
            } else {
                val cell = OrMapCell<String, String>(CellRef(UUID(world.seed, 0x9EL), 0L))
                map = cell
                Replication(registry).replicate(cell, host.host)
                world.cells.declare("map", cell.ref)
                world.controller.runToIdle()
                val proxy = HostedCellProxy.create(cell.ref, point, OrMapInletProxy::class.java) as OrMapInletProxy
                var issued = 0
                world.steps.onStep { _, _ ->
                    if (issued < writes) {
                        proxy.inlet.call.put("k$issued", "v$issued")
                        issued++
                    }
                }
            }
        }
    }

    private fun runPoint(seed: Long, counting: Boolean, vararg faults: Fault): Pair<DstReport, SingleHostPoint> {
        val graph = SingleHostPoint("dst-duplicate-invocation-${if (counting) "counter" else "ormap"}", counting)
        val report = DstRun(graph.spec, FaultPlan(seed, faults.toList()), budget = 20_000).execute()
        assertEquals(DstOutcome.PASSED, report.outcome, "run did not quiesce: ${report.summary()}")
        return report to graph
    }

    @Test
    fun `an invocation-plane duplicate re-delivers the same invocation, and a counting consumer double-counts`() {
        val (clean, control) = runPoint(3L, counting = true)
        assertEquals((0 until 12).toList(), control.counter.seen, "the fault-free run already miscounted")

        val (report, graph) = runPoint(3L, counting = true, DuplicateFault.invocations("dup", "solo-inlet"))
        assertTrue(report.appliedFaults.single().fired > 0, "the invocation-plane fault never fired")
        assertNotEquals(
            clean.appliedFaults.size,
            report.appliedFaults.size,
            "the two runs must differ in their plans, or the comparison below is vacuous",
        )
        // The observable consequence: every value arrived twice, in place.
        assertEquals((0 until 12).flatMap { listOf(it, it) }, graph.counter.seen)
    }

    @Test
    fun `the same invocation-plane duplication is absorbed by a tagged set on every seed`() {
        forEachSeed(SWEEP) { seed ->
            val (report, graph) = runPoint(
                seed,
                counting = false,
                DuplicateFault.invocations("dup", "solo-inlet", copies = 2),
            )
            assertTrue(report.appliedFaults.single().fired > 0, "the invocation-plane fault never fired")
            val view = graph.map.membership().associateWith { graph.map.value(it) }
            assertEquals(graph.batchFold(), view, "a tagged set failed to absorb a duplicated put")
        }
    }

    @Test
    fun `an invocation-plane fault naming no declared point fails at install, listing what was declared`() {
        val graph = GraphSpec("dst-duplicate-no-point") { world ->
            world.hosts.declare("h") { ctx -> ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry) }
            InvocationPoints.declare(world, "real", InvocationSink { })
        }
        val error = assertFailsWith<IllegalStateException> {
            DstRun(graph, FaultPlan.of(1L, DuplicateFault.invocations("dup", "typo"))).execute()
        }
        assertTrue("typo" in error.message!! && "InvocationPoints.declare" in error.message!!, error.message!!)
        assertTrue("[real]" in error.message!!, error.message!!)
    }

    // -------------------------------------------------------------------------------------
    // [CHA1-31] — the codec, and configuration errors
    // -------------------------------------------------------------------------------------

    @Test
    fun `a duplicate fault round-trips through its registered codec`() {
        val fault = DuplicateFault(
            "dup",
            A_TO_B,
            DuplicatePlane.INVOCATIONS,
            copies = 3,
            probability = 0.25,
            activation = StepWindow(4, 9),
        )
        val record = FaultCodecs.encode(fault)
        assertEquals("duplicate", record.kind)
        assertEquals(fault, FaultCodecs.decode(record))
        // Flat numeric params, so the shrinker's only parameter-reducing strategy can reach them.
        assertTrue(setOf("copies", "probability", "from", "until").all { it in record.params.keys }, "${record.params}")
    }

    @Test
    fun `an impossible configuration is refused at construction rather than reported inert`() {
        assertFailsWith<IllegalArgumentException> { DuplicateFault.frames("dup", A_TO_B, copies = 0) }
        assertFailsWith<IllegalArgumentException> { DuplicateFault.frames("dup", A_TO_B, probability = 0.0) }
        assertFailsWith<IllegalArgumentException> { DuplicateFault.frames("dup", A_TO_B, probability = 1.5) }
    }

    @Test
    fun `an unknown edge fails target validation before anything is installed`() {
        val graph = GraphSpec("dst-duplicate-unknown-edge") { world ->
            world.edges.declare("real")
            world.hosts.declare("h") { ctx -> ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry) }
        }
        val error = assertFailsWith<UnknownFaultTargetException> {
            DstRun(graph, FaultPlan.of(1L, DuplicateFault.frames("dup", "typo"))).execute()
        }
        assertEquals("dup", error.faultId)
        assertEquals(setOf("real"), error.known)
    }
}
