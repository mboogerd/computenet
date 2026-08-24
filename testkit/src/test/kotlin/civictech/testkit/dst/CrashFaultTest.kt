package civictech.testkit.dst

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostScheduler
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * [CrashFault]'s contract: [CHA1-17] (the crash discards the target host's in-flight tasks,
 * queues and links, rebuilds it at the **same `CellRef`s** through the graph's own
 * deterministic rebuild function, and recovers from the surviving journal), [CHA1-18]
 * (`MID_WAVE` fires only with a wave partially delivered and fails the run otherwise), and
 * [CHA1-62]/[CHA1-63] for the crash class as BS-8: crash mid-wave, recover, and the data view
 * equals the batch fold of the accepted ops while the `Effectful` sink fires exactly once per
 * key — against a control that provably loses data without the journal.
 *
 * ## The graphs, and why they are real hosted cells
 *
 * [SelfTestGraphs] builds its graphs from bare schedulers and named edges, because the
 * properties it tests are the controller's. Nothing here can be: a crash is only interesting
 * against cells whose state has to come back, so these graphs spawn real hand-written cells on
 * a real [ManagedHost] with a real write-ahead journal, and reach them through
 * [HostedCellProxy] — the same construction `EffectfulRecoveryTest` and `CrashRecoveryTest`
 * use in `:kernel`, which is available here because it needs no KSP-generated descriptor.
 *
 * ## Why the BS-8 activation step is *found* rather than written down
 *
 * A `MID_WAVE` crash has to land while one arm of a fan-out has been applied and another has
 * not, and which controller step that is depends on the seeded interleaving. So each BS-8 seed
 * runs twice: a **probe** run with an observe-only [ScriptedFault] that records the graph's own
 * `partialWaves()` at every step, then the real run with the crash pinned to the first step the
 * probe found. That is sound because a fault hook submits no work — the rig's own [CHA1-04]
 * claim — so both runs schedule identically up to the crash, and it is *better* than a
 * hand-picked constant: a constant that stopped being mid-wave would silently start testing
 * `AT_QUIESCENCE` under a `MID_WAVE` label, which is exactly the failure [CHA1-18] names.
 */
class CrashFaultTest {

    // ------------------------------------------------------------------ cells under test

    /** Root emitter, unhosted: one `provide` per key is one wave to every subscriber. */
    private class Source(override val ref: CellRef = CellRef(java.util.UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<String>>())
        fun emit(key: String) = outlet.call.provide(key)
    }

    /**
     * The data subgraph: an add-only key fold whose state lives **in the instance**, so a
     * crashed instance's fold is genuinely gone and only journal replay can rebuild it. Not
     * `Effectful`, so frame replay is its entire recovery path.
     */
    private class KeyFold(override val ref: CellRef, private val onApply: (String) -> Unit) : Cell {
        val keys: MutableSet<String> = linkedSetOf()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    keys += input
                    onApply(input)
                }
            })
        }
    }

    /**
     * The effect boundary: every firing lands on [fires], which is **external to the instance**
     * and therefore survives the crash. That is the only way a true double-fire is observable —
     * the pre-crash instance already acted, and the recovered one must not act again.
     */
    private class KeySink(override val ref: CellRef, private val fires: MutableList<String>) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    fires += input
                }
            })
        }
    }

    private interface KeyConsumerProxy {
        val inlet: Use<Consumer<String>>
    }

    // ------------------------------------------------------------------ [CHA1-17]

    /**
     * The crash's three destructive claims, each asserted rather than assumed: queued work on
     * the target host is **discarded** (not merely delayed), the rebuilt host is a **different
     * instance**, and its cells carry the **same `CellRef`s**.
     *
     * Also the probe the bead asked for first: `SimulationController.schedulers` is private with
     * no removal API, so the only discard available from `:testkit` is abandoning the old
     * scheduler/host pair — the shape the existing kernel crash tests already use. `ran == 1`
     * below is the evidence that abandoning is enough: the four tasks still queued at the crash
     * never run, in this or any later step.
     */
    @Test
    fun `crash discards the target host's queued work and rebuilds it at the same CellRefs`() {
        val refs = HostRebuild.refs("durable")
        val instances = mutableListOf<KeyFold>()
        var ran = 0
        val queued = 5

        val graph = GraphSpec("dst-crash-discard") { world ->
            val journal = world.journals.declare("wal")
            lateinit var scheduler: HostScheduler
            val slot = world.hosts.declare("durable") { ctx ->
                scheduler = ctx.scheduler
                val host = ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry, journalFor = { journal })
                val fold = KeyFold(refs.declaredIn(world, "fold")) { }
                instances += fold
                host.managementInlet.call.spawn(fold)
                host
            }
            CrashWitnesses.declare(world, "durable", CrashWitness.pendingWork { queued - ran })
            world.steps.onStep { _, step ->
                if (step == 0) repeat(queued) { scheduler.submit(50) { ran++ } }
            }
            check(slot.generation == 0)
        }

        // step 0 submits the five tasks, so at step 1 exactly one of them has run.
        val report = DstRun(graph, FaultPlan.of(7L, CrashFault.midDrain("boom", "durable", atStep = 1, journal = "wal")))
            .execute()

        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        assertEquals(1, ran, "queued work survived the crash — the host was not discarded")
        assertEquals(2, instances.size, "the rebuild function did not run exactly once after the crash")
        assertNotSame(instances[0], instances[1], "the crashed instance came back — nothing was discarded")
        assertEquals(instances[0].ref, instances[1].ref, "[CHA1-17]: the rebuild must reuse the same CellRef")
        assertEquals(refs.ref("fold"), instances[1].ref, "the ref is not derived deterministically")

        val applied = report.appliedFaults.single()
        assertEquals(1, applied.fired, "a crash must be counted exactly once: ${report.summary()}")
        assertEquals(listOf(1), applied.activationSteps)
        assertTrue(report.inertFaults.isEmpty(), "reported inert: ${report.inertFaults}")
        assertTrue(
            applied.description.contains("same CellRefs"),
            "the report must say what the crash did: ${applied.description}",
        )
    }

    // ------------------------------------------------------------------ [CHA1-18]

    /** A `MID_WAVE` plan the graph gave no way to check fails at install, not at the crash. */
    @Test
    fun `MID_WAVE without a declared witness fails run setup`() {
        val graph = GraphSpec("dst-crash-unwitnessed") { world ->
            world.journals.declare("wal")
            world.hosts.declare("durable") { ctx ->
                ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
            }
        }
        val failure = assertFailsWith<MissingCrashWitness> {
            DstRun(graph, FaultPlan.of(1L, CrashFault.midWave("boom", "durable", atStep = 3))).execute()
        }
        assertEquals("boom", failure.faultId)
        assertTrue(
            failure.message!!.contains("CrashWitnesses.declare"),
            "the failure must name the fix: ${failure.message}",
        )
    }

    /**
     * [CHA1-18]'s core: a `MID_WAVE` crash aimed at a step with no partially delivered wave
     * **aborts the run**. It must not fire and report `PASSED` — that is a run which tested
     * `AT_QUIESCENCE` while claiming to test mid-wave.
     */
    @Test
    fun `MID_WAVE fails the run when no wave was partially delivered at the activation step`() {
        var crashes = 0
        val graph = GraphSpec("dst-crash-no-wave") { world ->
            world.journals.declare("wal")
            world.hosts.declare("durable") { ctx ->
                crashes++
                ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
            }
            // an idle graph: nothing is ever in flight, so no wave is ever partial
            CrashWitnesses.declare(world, "durable", CrashWitness.partialWaves { 0 })
        }
        val failure = assertFailsWith<CrashPreconditionUnmet> {
            DstRun(graph, FaultPlan.of(2L, CrashFault.midWave("boom", "durable", atStep = 0))).execute()
        }
        assertEquals(CrashMode.MID_WAVE, failure.mode)
        assertEquals(0, failure.step)
        assertEquals(1, crashes, "the host was rebuilt anyway — the precondition ran too late")
    }

    /** The mirror: `AT_QUIESCENCE` must not fire onto a host that is still draining. */
    @Test
    fun `AT_QUIESCENCE fails the run when the host still has pending work`() {
        val graph = GraphSpec("dst-crash-not-quiesced") { world ->
            world.journals.declare("wal")
            lateinit var scheduler: HostScheduler
            world.hosts.declare("durable") { ctx ->
                scheduler = ctx.scheduler
                ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
            }
            var ran = 0
            CrashWitnesses.declare(world, "durable", CrashWitness.pendingWork { 3 - ran })
            world.steps.onStep { _, step -> if (step == 0) repeat(3) { scheduler.submit(50) { ran++ } } }
        }
        val failure = assertFailsWith<CrashPreconditionUnmet> {
            DstRun(graph, FaultPlan.of(3L, CrashFault.atQuiescence("boom", "durable", atStep = 1))).execute()
        }
        assertEquals(CrashMode.AT_QUIESCENCE, failure.mode)
        assertTrue(failure.message!!.contains("pending work"), failure.message!!)
    }

    // ------------------------------------------------------------------ BS-8

    /**
     * One BS-8 world: a durable host carrying a journaled data fold **and** a journaled
     * `Effectful` sink, both fed by one root outlet, so every emitted key is a two-armed wave
     * on one host and a crash between the arms is a crash mid-wave.
     *
     * @param recover false builds the [CHA1-63] control — identical graph, identical seed,
     *   identical crash step, rebuilt without recovering.
     */
    private class Bs8(val seed: Long, val keys: Int = 8) {
        val emitted = linkedSetOf<String>()
        val fires = mutableListOf<String>()
        private val armsApplied = linkedMapOf<String, Int>()
        private val refs = HostRebuild.refs("bs8-durable")

        /** The live fold instance — replaced by every rebuild, which is the point. */
        var fold: KeyFold? = null
            private set

        /** Waves with one arm applied and not the other: the [CrashMode.MID_WAVE] witness. */
        fun partialWaves(): Int = armsApplied.values.count { it == 1 }

        private fun arm(key: String) {
            armsApplied[key] = (armsApplied[key] ?: 0) + 1
        }

        fun graph(): GraphSpec = GraphSpec("dst-crash-bs8-$seed-$keys") { world ->
            val journal = world.journals.declare("wal")
            val source = Source()
            var link: PortRef? = null

            world.hosts.declare("durable") { ctx ->
                val host = ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry, journalFor = { journal })
                val newFold = KeyFold(refs.declaredIn(world, "fold")) { arm(it) }
                fold = newFold
                host.managementInlet.call.spawn(newFold)
                host.managementInlet.call.spawn(KeySink(refs.declaredIn(world, "sink"), fires))

                // Re-wire the root outlet onto the rebuilt host's cells: the links died with the
                // host, and a proxy is resolved against the registry, which survived.
                link?.let { source.outlet.unsubscribe(it) }
                val port = PortRef.generate()
                source.outlet.subscribe(Use.fixed(proxy(newFold.ref, ctx), port))
                source.outlet.subscribe(Use.fixed(proxy(refs.ref("sink"), ctx), PortRef.generate()))
                link = port
                host
            }
            CrashWitnesses.declare(world, "durable", CrashWitness.partialWaves(::partialWaves))

            // The whole workload rides the first `keys` steps, so every op is accepted (and
            // therefore journaled) before any plausible crash step — which keeps the oracle
            // "the fold equals every emitted key" a statement about recovery rather than about
            // which ops made it in.
            world.steps.onStep { _, step ->
                if (step < keys) "k$step".let { key -> emitted += key; source.emit(key) }
            }
        }

        private fun proxy(ref: CellRef, ctx: HostBuildContext): Consumer<String> =
            (HostedCellProxy.create(ref, ctx.registry, KeyConsumerProxy::class.java) as KeyConsumerProxy).inlet.call

        /** The batch oracle: the fold of every accepted op, recomputed outside the graph. */
        fun batchFold(): Set<String> = emitted.toSet()
    }

    /**
     * The step the crash has to land on is a property of the run, so it is measured: an
     * observe-only fault records `partialWaves()` before every step, and the first step with a
     * partially delivered wave is where the [CrashMode.MID_WAVE] crash goes.
     */
    private fun firstMidWaveStep(seed: Long): Int {
        val probe = Bs8(seed)
        val partial = mutableMapOf<Int, Int>()
        val watch = ScriptedFault(
            id = "probe",
            atStep = { _, step -> partial[step] = probe.partialWaves() },
        )
        val report = DstRun(probe.graph(), FaultPlan.of(seed, watch)).execute()
        assertEquals(DstOutcome.PASSED, report.outcome, report.summary())
        // Not before the workload has finished being emitted: crashing while the step hook is
        // still injecting would make "every emitted key" ambiguous about the crash step itself.
        return partial.entries
            .filter { (step, count) -> step >= probe.keys && count > 0 }
            .minOf { it.key }
    }

    /**
     * BS-8 ([CHA1-62]): crash mid-wave on a durable host, rebuild at the same refs, recover
     * from the surviving journal — and then **both** halves of the property hold at once. The
     * data view equals the batch fold of the accepted ops (nothing lost), and the `Effectful`
     * sink fired exactly once per key (nothing re-fired). One without the other is easy: a
     * recovery that never replays keeps the sink honest and loses the fold, and one that
     * replays without a processed frontier rebuilds the fold and double-fires the sink.
     */
    @Test
    fun `BS-8 - a crash mid-wave recovers the data view and fires the effectful sink once per key`() {
        for (seed in 0L until 5L) {
            val atStep = firstMidWaveStep(seed)
            val world = Bs8(seed)
            val crash = CrashFault.midWave("crash", "durable", atStep = atStep, journal = "wal")
            // Listed first, so its hook runs before the crash's: what the sink had already
            // fired at the instant of the crash. Without this the once-per-key assertion could
            // be satisfied vacuously by a crash that landed before the sink ever fired, leaving
            // replay nothing to double-fire.
            var firesAtCrash = -1
            val watch = ScriptedFault(
                id = "watch",
                atStep = { _, step -> if (step == atStep) firesAtCrash = world.fires.size },
            )
            val report = DstRun(world.graph(), FaultPlan(seed, listOf(watch, crash))).execute()

            assertEquals(DstOutcome.PASSED, report.outcome, "seed $seed: ${report.summary()}")
            assertEquals(1, report.appliedFaults.single { it.id == "crash" }.fired, "seed $seed: the crash never fired")
            assertTrue(
                firesAtCrash in 1 until world.emitted.size,
                "seed $seed: the crash landed with $firesAtCrash of ${world.emitted.size} sink firings done — " +
                    "the no-double-fire property is only tested when some had fired and some had not",
            )

            assertEquals(
                world.batchFold(),
                world.fold!!.keys,
                "seed $seed: the recovered data view is not the batch fold of the accepted ops",
            )
            assertEquals(
                world.emitted.toList().sorted(),
                world.fires.sorted(),
                "seed $seed: the effectful sink did not fire exactly once per key (fires=${world.fires})",
            )
            assertEquals(
                world.fires.size,
                world.fires.toSet().size,
                "seed $seed: the sink double-fired across recovery: ${world.fires}",
            )
        }
    }

    /**
     * The [CHA1-63] control, one field apart from BS-8: `journal = null`, so the host is rebuilt
     * and never recovers. Everything the journal was holding is lost — and the assertion is
     * that it IS lost, on at least one seed, because a control that quietly passed would mean
     * the BS-8 test above proves nothing about the journal.
     */
    @Test
    fun `control - without recovery the crash loses accepted data on at least one seed`() {
        val foldLosses = mutableListOf<Pair<Long, Int>>()
        val sinkLosses = mutableListOf<Pair<Long, Int>>()
        for (seed in 0L until 5L) {
            val atStep = firstMidWaveStep(seed)
            val world = Bs8(seed)
            val control = CrashFault.midWave("crash", "durable", atStep = atStep, journal = null)
            val report = DstRun(world.graph(), FaultPlan.of(seed, control)).execute()

            assertEquals(DstOutcome.PASSED, report.outcome, "seed $seed: ${report.summary()}")
            (world.batchFold() - world.fold!!.keys).let { if (it.isNotEmpty()) foldLosses += seed to it.size }
            (world.emitted - world.fires.toSet()).let { if (it.isNotEmpty()) sinkLosses += seed to it.size }
        }
        assertTrue(
            foldLosses.isNotEmpty(),
            "the control lost no fold data on any seed, so BS-8 does not show the journal is what saved it",
        )
        // The other half of the same argument: the sink's "exactly once per key" is only a
        // property worth asserting if recovery is what supplies the firings that were still in
        // flight. Without recovery they are simply missing.
        assertTrue(
            sinkLosses.isNotEmpty(),
            "the control fired the sink for every key without recovering, so BS-8's once-per-key " +
                "assertion is not evidence that replay re-fired the in-flight arms exactly once",
        )
    }
}
