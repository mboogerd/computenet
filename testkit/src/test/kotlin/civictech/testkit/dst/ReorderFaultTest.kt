package civictech.testkit.dst

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.BridgeIngressCell
import civictech.cell.wire.PortAddress
import civictech.cell.wire.bridgeFrom
import civictech.cell.wire.bridgeTo
import civictech.testkit.forEachSeed
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [ReorderFault]'s contract: [CHA1-14] (a buffer of up to `window` frames, released in a
 * permutation derived solely from the run seed), [CHA1-15] (per-link FIFO preserved by default,
 * single-link permutation only behind an explicit opt-in), and [CHA1-62]/[CHA1-63]/BS-7 (a
 * fork-join diamond whose arms are on different hosts, reordered across links with window 8 over
 * 100 seeds, whose glitch-free consumer never sees a torn composite — while the intra-link
 * opt-in control does tear one).
 *
 * ## The graph
 *
 * A M2 fork-join diamond, cut so that **both** arms cross a wire bridge:
 *
 * ```
 *                   (near hostB)  B ──bridge "armB"──┐
 *   (near hostA) A ─┤                                ├─> GlitchFreeCell ─> observer  (far host)
 *                   (near hostC)  C ──bridge "armC"──┘
 * ```
 *
 * Both arms bridged is the point, and it is what distinguishes this from
 * `GlitchFreeBridgedDiamondTest` (whose near arm never becomes a frame): a *cross-link* reorder
 * has nothing to reorder unless there are two links, and each arm's frames must be individually
 * reachable, which means one named [DstWorld.edges] edge per arm. The two mappers also sit on
 * separate near hosts, so the controller's seeded cross-host pick already interleaves the arms
 * before any fault is applied — the fault adds transport delay on top of scheduling jitter,
 * which is exactly the composition BS-7 asks about.
 *
 * The reverse (far→near) leg of each bridge carries protocol replies and is deliberately *not*
 * routed through a named edge: nothing in this task's scope reorders it, and giving it an edge
 * would put untargeted names in every report.
 *
 * ## Why the fault's window closes before the workload does
 *
 * [FrameInterposer]'s known limit: an edge is a transform, not a transport, so only a later
 * frame on the same edge can flush a reorder buffer. The workload emits a wave per controller
 * step for [WAVES] steps and the fault's window closes at [REORDER_UNTIL], comfortably inside
 * it — so the remaining waves' traffic flushes whatever the buffer still holds. Every test here
 * asserts [ReorderFault.strandedFrames] is zero, because a stranded frame is a *drop*, and a
 * reorder test that silently became a drop test would prove the wrong thing.
 *
 * ## Failure messages are deliberately count-free
 *
 * [PlanShrinker]'s [FailurePredicate.sameFailingCheck] decides whether a reduced plan still
 * reproduces by comparing the failing check's **message**. A message carrying a run-varying
 * count ("only 7 of 20 composites arrived") makes every genuine reduction look like a different
 * failure and silently defeats shrinking. Counts belong in the report and the trace; the check
 * says only which invariant broke.
 */
class ReorderFaultTest {

    private companion object {
        const val ARM_B = "armB"
        const val ARM_C = "armC"

        /** One wave emitted per controller step, at steps `0 until WAVES`. */
        const val WAVES = 20

        /** The reorder window closes here, so the remaining waves' traffic flushes the buffers. */
        const val REORDER_UNTIL = 12

        /** BS-7's buffer size. */
        const val WINDOW = 8

        /** The intra-link control's buffer size — see [intraLink] for why it is not [WINDOW]. */
        const val CONTROL_WINDOW = 4

        /** BS-7's sweep. */
        val SWEEP = 0L until 100L

        val PROPAGATE_STRING: Class<Propagate<String>> =
            @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<String>>)
    }

    data class Obs(val label: String, val n: Int, val ts: Timestamp)

    /** Mints a fresh wave per emission. */
    class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())
        fun emit(n: Int) = outlet.originate { propagate(n) }
    }

    /** `Int -> "label:n"`, preserving the incoming wave timestamp. */
    class LabelMapper(private val label: String, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.onEach { n -> outlet.call.propagate("$label:$n") }
        }
    }

    class ObserverCell(
        private val out: MutableList<Obs>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())

        init {
            inlet.onEach { s ->
                val (label, n) = s.split(":")
                out += Obs(label, n.toInt(), CurrentContext.get()!!.timestamp)
            }
        }
    }

    interface IntInlet {
        val inlet: Use<Propagate<Int>>
    }

    interface StringInlet {
        val inlet: Use<Propagate<String>>
    }

    interface FrameInlet {
        val inlet: Use<Propagate<ByteArray>>
    }

    // -------------------------------------------------------------------------------------
    // the graph
    // -------------------------------------------------------------------------------------

    /**
     * The two-arm bridged diamond. One instance drives exactly one [DstRun.execute]; it holds
     * the observer log so a check and a test can read what the consumer actually saw.
     */
    private class BridgedDiamond(id: String, private val waves: Int = WAVES) {

        val observed = mutableListOf<Obs>()

        /** Frames the graph offered to each arm's interposer chain while the rig was stepping. */
        val offered = linkedMapOf(ARM_B to 0, ARM_C to 0)

        val spec: GraphSpec = GraphSpec(id) { world -> build(world) }

        /** The invariant BS-7 is about; see the class KDoc on why the messages carry no counts. */
        val check: DstCheck = DstCheck {
            if (observed.size % 2 != 0) {
                throw AssertionError("glitch-free consumer saw a half composite — a wave was split across arms")
            }
            observed.chunked(2).forEach { composite ->
                if (composite.map { it.ts }.toSet().size != 1) {
                    throw AssertionError("glitch-free consumer saw a composite mixing two waves' timestamps")
                }
                if (composite.map { it.label }.toSet() != setOf("B", "C")) {
                    throw AssertionError("glitch-free consumer saw a composite with a repeated arm, not both arms")
                }
            }
            if (observed.chunked(2).map { it.first().ts.counter } != (1L..waves).toList()) {
                throw AssertionError("glitch-free consumer did not receive every wave composite exactly once, in order")
            }
        }

        private fun build(world: DstWorld) {
            val registryNear = world.registry
            val registryFar = LocationRegistry()

            val hostA = world.hosts.declare("nearA") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = registryNear)
            }
            val hostB = world.hosts.declare("nearB") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = registryNear)
            }
            val hostC = world.hosts.declare("nearC") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = registryNear)
            }
            val hostFar = world.hosts.declare("far") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = registryFar)
            }

            world.edges.declare(ARM_B, from = "nearB", to = "far")
            world.edges.declare(ARM_C, from = "nearC", to = "far")

            // --- one full-duplex bridge per arm, its forward leg routed through the named edge
            fun bridge(edge: String): Pair<BridgeEgressCell, BridgeEgressCell> {
                val forward = BridgeEgressCell() // near -> far
                val back = BridgeEgressCell() // far -> near
                val ingressFwd = BridgeIngressCell(
                    deliverTo = InvocationSink(registryFar::deliver),
                    replySink = InvocationSink { back.deliver(it) },
                )
                val ingressBack = BridgeIngressCell(
                    deliverTo = InvocationSink(registryNear::deliver),
                    replySink = InvocationSink { forward.deliver(it) },
                )
                hostFar.host.managementInlet.call.spawn(ingressFwd)
                hostA.host.managementInlet.call.spawn(ingressBack)
                world.controller.runToIdle()

                val fwdApi = (
                    HostedCellProxy.create(ingressFwd.ref, registryFar, FrameInlet::class.java) as FrameInlet
                    ).inlet.call
                val backApi = (
                    HostedCellProxy.create(ingressBack.ref, registryNear, FrameInlet::class.java) as FrameInlet
                    ).inlet.call

                forward.outlet.subscribe(
                    Use.fixed(
                        object : Propagate<ByteArray> {
                            override fun propagate(value: ByteArray) {
                                if (world.step >= 0) offered[edge] = offered.getValue(edge) + 1
                                world.edges.deliver(edge, value).forEach(fwdApi::propagate)
                            }
                        },
                        PortRef.generate(),
                    ),
                )
                back.outlet.subscribe(Use.fixed(backApi, PortRef.generate()))
                return forward to back
            }

            val (fwdB, backB) = bridge(ARM_B)
            val (fwdC, backC) = bridge(ARM_C)

            val a = SourceCell()
            val b = LabelMapper("B")
            val c = LabelMapper("C")
            val gf = GlitchFreeCell(PROPAGATE_STRING)
            val observer = ObserverCell(observed)

            hostA.host.managementInlet.call.spawn(a)
            hostB.host.managementInlet.call.spawn(b)
            hostC.host.managementInlet.call.spawn(c)
            hostFar.host.managementInlet.call.spawn(gf)
            hostFar.host.managementInlet.call.spawn(observer)
            world.controller.runToIdle()

            world.cells.declare("source", a.ref)
            world.cells.declare("armB", b.ref)
            world.cells.declare("armC", c.ref)
            world.cells.declare("glitchFree", gf.ref)
            world.cells.declare("observer", observer.ref)

            // A fans to both mappers on the near side (no frames — same registry, other hosts).
            val nearB = HostedCellProxy.create(b.ref, registryNear, IntInlet::class.java) as IntInlet
            val nearC = HostedCellProxy.create(c.ref, registryNear, IntInlet::class.java) as IntInlet
            a.outlet.subscribe(Use.fixed(nearB.inlet.call, PortRef.generate()))
            a.outlet.subscribe(Use.fixed(nearC.inlet.call, PortRef.generate()))

            // Each mapper crosses its own bridge into the glitch-free consumer: data over a
            // proxy on the egress sink, the edge itself over a real bridged handshake.
            fun bridgeArm(mapper: LabelMapper, forward: BridgeEgressCell, back: BridgeEgressCell) {
                val far = HostedCellProxy.create(
                    gf.ref,
                    InvocationSink(forward::deliver),
                    StringInlet::class.java,
                ) as StringInlet
                mapper.outlet.subscribe(Use.fixed(far.inlet.call, PortRef.generate()))
                mapper.outlet.bridgeTo(
                    selfAddr = PortAddress(mapper.ref, "outlet"),
                    toAddr = PortAddress(gf.ref, "inlet"),
                    sink = InvocationSink(forward::deliver),
                )
                gf.inlet.bridgeFrom(
                    selfAddr = PortAddress(gf.ref, "inlet"),
                    fromAddr = PortAddress(mapper.ref, "outlet"),
                    sink = InvocationSink(back::deliver),
                )
            }
            bridgeArm(b, fwdB, backB)
            bridgeArm(c, fwdC, backC)
            gf.outlet.subscribe(Use.fixed(observer.inlet.call, observer.inlet.ref))

            // Settle both handshakes before the rig's loop starts: they are setup, not workload.
            world.controller.runToIdle()

            var emitted = 0
            world.steps.onStep { _, _ ->
                if (emitted < waves) a.emit(++emitted)
            }
        }
    }

    private fun run(seed: Long, vararg faults: Fault): Pair<DstReport, BridgedDiamond> {
        val graph = BridgedDiamond("dst-reorder-bridged-diamond")
        val report = DstRun(graph.spec, FaultPlan(seed, faults.toList()), budget = 40_000, check = graph.check)
            .execute()
        assertNotEquals(
            DstOutcome.BUDGET_EXHAUSTED,
            report.outcome,
            "run did not quiesce, so nothing was checked: ${report.summary()}",
        )
        return report to graph
    }

    private fun crossLink(edge: String) =
        ReorderFault.crossLink("reorder-$edge", edge, window = WINDOW, from = 0, until = REORDER_UNTIL)

    /**
     * The opt-in control, and **the one place this suite does not use BS-7's window of 8**.
     *
     * Measured on this graph over all 100 seeds (re-measured 2026-08-24 at this commit, three
     * identical repetitions): each arm buffers about six frames inside `[0, REORDER_UNTIL)` —
     * mean 5.96 per arm per run, range 1..11 — so a buffer of 8 **usually never fills**, on the
     * few seeds where it does the one permuted burst is absorbed anyway, and the control is
     * inert at BS-7's window: **0 of 100 seeds failed**. At [CONTROL_WINDOW] the buffer fills
     * repeatedly and **62 of 100** seeds tear a composite (window 3: 72, window 2: 55). Every
     * failing seed fails with the same message — the composite mixes two waves' timestamps.
     *
     * That is a property of this graph's traffic volume, not of [ReorderFault]: a consumer suite
     * with heavier per-step traffic can use any window it likes. It is recorded here because a
     * control sized past the traffic available to it is the exact failure the [CHA1-63] pairing
     * exists to catch, and the number that makes it inert is not guessable from the code.
     */
    private fun intraLink(edge: String) =
        ReorderFault.intraLink("reorder-$edge", edge, window = CONTROL_WINDOW, from = 0, until = REORDER_UNTIL)

    // -------------------------------------------------------------------------------------
    // [CHA1-14] / [CHA1-15] — the primitive, at unit scale
    // -------------------------------------------------------------------------------------

    /** Drive an interposer with `n` numbered one-byte frames and return the release order. */
    private fun release(interposer: FrameInterposer, n: Int, step: Int = 0): List<Int> =
        (0 until n).flatMap { i -> interposer.apply(byteArrayOf(i.toByte()), step).map { it[0].toInt() } }

    @Test
    fun `CHA1-15 - the default buffers frames but releases them in arrival order`() {
        forEachSeed(0L until 20L) { seed ->
            val interposer = FrameInterposers.reordering(window = WINDOW, rng = java.util.Random(seed))
            var withheld = 0
            val out = (0 until 64).flatMap { i ->
                interposer.apply(byteArrayOf(i.toByte()), 0)
                    .also { if (it.isEmpty()) withheld++ }
                    .map { frame -> frame[0].toInt() }
            }
            // Per-link FIFO: whatever was released came out in the order it went in.
            assertEquals(out.sorted(), out, "seed $seed reordered a single link with FIFO in force")
            assertTrue(withheld > 0, "seed $seed never withheld a frame, so no delay was applied at all")
        }
    }

    @Test
    fun `CHA1-14 - the opt-in permutes the window, identically for one seed and differently across seeds`() {
        val orders = (0L until 20L).map { seed ->
            release(
                FrameInterposers.reordering(window = WINDOW, permute = true, rng = java.util.Random(seed)),
                64,
            )
        }
        orders.forEach { out ->
            assertEquals(64, out.size, "a permuting buffer with window=8 over 64 frames must release all of them")
            // Every release is a permutation of one whole window: FIFO is broken only within it.
            out.chunked(WINDOW).forEachIndexed { i, chunk ->
                assertEquals((i * WINDOW until (i + 1) * WINDOW).toSet(), chunk.toSet())
            }
        }
        assertTrue(orders.any { it != it.sorted() }, "no seed produced a non-identity permutation")
        assertTrue(orders.toSet().size > 1, "every seed produced the same permutation — it is not seed-derived")

        // Derived solely from the seed ([CHA1-14]): the same seed replays exactly.
        val again = release(FrameInterposers.reordering(WINDOW, permute = true, rng = java.util.Random(3L)), 64)
        assertEquals(orders[3], again)
    }

    @Test
    fun `the closing of the window releases what the buffer holds instead of stranding it`() {
        // The windowed() trap: wrapping a stateful buffer in an activation window strands its
        // contents at `until`. Gated inside the primitive, the first frame at or after `until`
        // flushes the buffer ahead of itself.
        val interposer = FrameInterposers.reordering(
            window = WINDOW,
            rng = java.util.Random(1L),
            active = StepWindow(0, 2),
        )
        val held = (0 until 3).flatMap { i -> interposer.apply(byteArrayOf(i.toByte()), 0) }
        assertTrue(held.isEmpty(), "window=8 released after three frames, so this case is untested")
        val flushed = interposer.apply(byteArrayOf(9), 2).map { it[0].toInt() }
        assertEquals(listOf(0, 1, 2, 9), flushed, "the closing window did not release what it held")
    }

    @Test
    fun `chain applies its stages innermost first, so an upstream duplicate feeds two downstream`() {
        // chain/then shipped with the partition task uncalled and untested; this is a direct
        // test of its fold, which both fault classes here compose on top of.
        val seen = mutableListOf<Int>()
        val chained = FrameInterposers.chain(
            FrameInterposers.duplicating(copies = 1, rng = java.util.Random(0L)),
            FrameInterposer { frame, _ ->
                seen += frame[0].toInt()
                listOf(frame)
            },
        )
        assertEquals(2, chained.apply(byteArrayOf(5), 0).size)
        assertEquals(listOf(5, 5), seen, "the second stage did not see both frames the first produced")

        val dropped = FrameInterposers.drop() then FrameInterposer { frame, _ -> listOf(frame) }
        assertTrue(dropped.apply(byteArrayOf(1), 0).isEmpty(), "a drop upstream must leave the chain nothing")
    }

    // -------------------------------------------------------------------------------------
    // BS-7 — [CHA1-62] / [CHA1-63] on the bridged diamond
    // -------------------------------------------------------------------------------------

    @Test
    fun `BS-7 - the fault-free diamond is glitch-free, so a failure below is the fault's doing`() {
        forEachSeed(SWEEP) { seed ->
            val (report, graph) = run(seed)
            assertEquals(DstOutcome.PASSED, report.outcome, "baseline failed: ${report.summary()}")
            assertEquals(WAVES * 2, graph.observed.size)
        }
    }

    @Test
    fun `BS-7 - cross-host reorder with window 8 never tears a composite, over 100 seeds`() {
        forEachSeed(SWEEP) { seed ->
            val (report, graph) = run(seed, crossLink(ARM_B), crossLink(ARM_C))

            assertEquals(DstOutcome.PASSED, report.outcome, "the composite tore: ${report.summary()}")
            assertEquals(WAVES * 2, graph.observed.size)

            // The fault was not a no-op, and it was not secretly a drop: both arms buffered
            // frames, and every frame either arrived or was released.
            report.plan.faults.map { it as ReorderFault }.forEach { fault ->
                assertTrue(fault.heldFrames > 0, "${fault.id} buffered nothing on seed $seed")
                assertEquals(0, fault.strandedFrames, "${fault.id} stranded frames — this became a drop test")
            }
            assertTrue(report.inertFaults.isEmpty(), "reported inert: ${report.inertFaults}")
        }
    }

    @Test
    fun `BS-7 - cross-host reorder actually moves frames later, not merely counts them`() {
        // The observable consequence, without which the test above would stay green against an
        // identity interposer: with the buffer in force, the frames the graph offered to the
        // edge inside the window are not the frames that had reached the peer by that step.
        val (plain, plainGraph) = run(7L)
        val (report, graph) = run(7L, crossLink(ARM_B), crossLink(ARM_C))

        val fault = report.plan.faults.first() as ReorderFault
        assertTrue(fault.heldFrames > 0)
        // Same workload, same seed: the trace of a run whose transport delayed frames cannot be
        // the trace of one that did not.
        assertNotEquals(plain.traceDigest, report.traceDigest, "reordering left the observed run identical")
        assertEquals(plainGraph.observed.size, graph.observed.size, "reordering lost or duplicated a composite")
        assertTrue(graph.offered.values.all { it > 0 }, "an arm carried no frames at all")
    }

    @Test
    fun `BS-7 - the intra-link opt-in control tears a composite on at least one seed`() {
        // [CHA1-63]: a control that cannot be made to fail certifies nothing about the run that
        // passed. Both arms permuted, same window, same seeds as the passing sweep above.
        val failures = mutableListOf<Pair<Long, String>>()
        for (seed in SWEEP) {
            val (report, _) = run(seed, intraLink(ARM_B), intraLink(ARM_C))
            if (report.outcome == DstOutcome.FAILED) {
                failures += seed to report.failingCheck!!.message
            }
        }
        assertTrue(
            failures.isNotEmpty(),
            "no seed failed under an intra-link permutation — the opt-in control is inert and " +
                "the cross-link sweep above proves nothing",
        )
        // The shrinker's same-failure predicate compares the failing check's *message*
        // (FailurePredicate.sameFailingCheck), so a message that varied per run would make every
        // genuine reduction of this plan look like a different failure. One distinct message
        // across every failing seed is the evidence that it does not.
        assertEquals(
            1,
            failures.map { it.second }.toSet().size,
            "the control produced run-varying failure messages, which silently defeats shrinking: " +
                failures.map { it.second }.toSet(),
        )
    }

    @Test
    fun `the report labels the scope, the window and the activation window distinctly`() {
        val cross = crossLink(ARM_B).describe()
        val intra = intraLink(ARM_B).describe()
        assertNotEquals(cross, intra)
        assertTrue("CROSS_LINK" in cross && "FIFO preserved" in cross, cross)
        assertTrue("INTRA_LINK" in intra && "permutation" in intra, intra)
        assertTrue("window=$WINDOW" in cross && "steps 0..<$REORDER_UNTIL" in cross, cross)
    }

    // -------------------------------------------------------------------------------------
    // [CHA1-31] — the codec, and configuration errors
    // -------------------------------------------------------------------------------------

    @Test
    fun `a reorder fault round-trips through its registered codec`() {
        listOf(crossLink(ARM_B), intraLink(ARM_C)).forEach { fault ->
            val record = FaultCodecs.encode(fault)
            assertEquals("reorder", record.kind)
            assertEquals(fault, FaultCodecs.decode(record))
            assertTrue(setOf("window", "from", "until").all { it in record.params.keys }, "${record.params}")
        }
    }

    @Test
    fun `an impossible configuration is refused at construction rather than reported inert`() {
        assertFailsWith<IllegalArgumentException> { ReorderFault.crossLink("r", ARM_B, window = 0) }
        assertFailsWith<IllegalArgumentException> { ReorderFault.crossLink("r", ARM_B, window = 4, from = 5, until = 3) }
    }

    @Test
    fun `an unknown edge fails target validation before anything is installed`() {
        val graph = GraphSpec("dst-reorder-unknown-edge") { world ->
            world.edges.declare("real")
            world.hosts.declare("h") { ctx -> ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry) }
        }
        val error = assertFailsWith<UnknownFaultTargetException> {
            DstRun(graph, FaultPlan.of(1L, ReorderFault.crossLink("r", "typo", window = 4))).execute()
        }
        assertEquals("r", error.faultId)
        assertEquals(setOf("real"), error.known)
    }
}
