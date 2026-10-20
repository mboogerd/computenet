package civictech.demo.studio

import civictech.cell.*
import java.nio.file.Files
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.evolve.*
import civictech.cell.durability.*
import civictech.cell.graph.*
import civictech.cell.host.*
import civictech.cell.link.*
import civictech.cell.port.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.Serializable
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.*

/** Kernel boundaries retained from the design spike; application recovery is tested separately. */
@Timeout(30)
class KernelAssumptionsTest {
    @TempDir lateinit var temp: Path

    companion object {
        val instances = ConcurrentHashMap<CellRef, Cell>()
        val effects = CopyOnWriteArrayList<String>()
    }

    @BeforeEach fun reset() { instances.clear(); effects.clear() }

    data class Factory(val kind: String = "sync") : CellFactory {
        override fun create(ref: CellRef): Cell = when (kind) {
            "source-baseline" -> Source(ref)
            else -> Effect(ref, kind)
        }.also { instances[ref] = it }
    }

    class Source(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<String>>())
        var held = ""
        init {
            inlet.serve(consumer { held = it; outlet.call.provide(it) })
            // Consumer-shaped equivalent of catchUpOnLinked (which is Propagate-specific).
            outlet.linking.onLinked = { link ->
                if (held.isNotEmpty()) {
                    outlet.baselineTo(link.to, TagFrontier(emptyMap())) { provide(held) }
                }
            }
        }
        override fun snapshot(): Serializable = held
        override fun restore(state: Serializable) { held = state as String }
    }

    class Effect(override val ref: CellRef, val kind: String) : Cell, Effectful, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())
        var output = ""
        init {
            inlet.serve(consumer {
                effects += it
                if (kind == "fail" && effects.size == 1) error("external act landed; handler did not finish")
                output = it
            })
        }
        override fun snapshot(): Serializable = output
        override fun restore(state: Serializable) { output = state as String }
    }

    interface Input { val inlet: Use<Consumer<String>> }

    class Rig(val journal: Journal) {
        val simulation = SimulationController(71)
        val scheduler = simulation.scheduler()
        val host = ManagedHost(scheduler = scheduler, journal = journal)
        val context = ApplyContext(host, journals = mapOf("j" to journal), topology = journal)
        fun apply(vararg steps: GraphStep) { GraphSpec(steps.toList()).apply(context); idle() }
        fun idle() { simulation.runToIdle() }
        fun recover() { val recovery = context.recover(journal); idle(); recovery.awaitApplied(5_000) }
        fun input(ref: CellRef) = (HostedCellProxy.create(ref, host, Input::class.java) as Input).inlet.call
        fun spawn(handle: String, kind: String = "sync"): CellRef {
            apply(SpawnStep(handle, Factory(kind), journalId = "j"))
            return context.handles.getValue(handle)
        }
    }

    private fun disk(name: String) = FileJournal(temp.resolve(name).toFile())
    private fun effect(r: Rig, ref: CellRef, value: String, actor: ActorIngress = ActorIngress(UUID.randomUUID())) {
        actor.drive { r.input(ref).provide(value) }; r.idle()
    }

    // Effectful frontiers are not a durable job ledger or equal-value dedupe.
    @Test fun completedEffectIsSuppressedButItsUncheckpointedStateIsNotRebuilt() {
        val r = Rig(disk("effect")); val ref = r.spawn("effect", "sync")
        val actor = ActorIngress(UUID.randomUUID())
        // Contextless direct calls are refused, even though this is a local host.
        r.input(ref).provide("refused"); r.idle(); assertTrue(effects.isEmpty())
        effect(r, ref, "completed", actor)
        assertEquals(listOf("completed"), effects)
        r.scheduler.shutdown()
        val recovered = Rig(disk("effect")); recovered.recover()
        assertEquals(listOf("completed"), effects)
        assertEquals("", (instances[ref] as Effect).output)
        // A NEW position with identical content is a new effect, not equality dedupe.
        effect(recovered, ref, "completed", actor)
        assertEquals(2, effects.size)
        recovered.host.checkpoint(recovered.journal); recovered.scheduler.shutdown()
        val compacted = Rig(disk("effect")); compacted.recover()
        assertEquals("completed", (instances[ref] as Effect).output)
        assertEquals(2, effects.size)
        compacted.scheduler.shutdown()
    }

    // External effects can repeat when a handler fails before frontier advance.
    @Test fun externalActBeforeFailedHandlerFrontierCanRunAgain() {
        val r = Rig(disk("failure")); val ref = r.spawn("effect", "fail")
        effect(r, ref, "charged")
        assertEquals(1, effects.size); r.scheduler.shutdown()
        val recovered = Rig(disk("failure")); recovered.recover()
        assertEquals(listOf("charged", "charged"), effects)
        recovered.scheduler.shutdown()
    }

    // Fresh checkpoint baselines can re-fire equal values (computenet-n2jwi).
    @Test fun checkpointRelinkWithFreshBaselineRefiresAnAlreadyCompletedEffect() {
        val r = Rig(disk("baseline")); val source = r.spawn("source", "source-baseline"); r.spawn("effect", "sync")
        r.apply(ConnectStep("source", "outlet", "effect", "inlet", LinkOptions(staged = true)))
        r.input(source).provide("old value"); r.idle(); assertEquals(1, effects.size)
        r.host.checkpoint(r.journal); r.scheduler.shutdown()
        val recovered = Rig(disk("baseline")); recovered.recover()
        assertEquals(listOf("old value", "old value"), effects)
        recovered.scheduler.shutdown()
    }
    open class Python(override val ref: CellRef, val script: Path) : Cell, Stateful, StateMigrating {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())
        var runs = 0
        var latest = 0
        init {
            inlet.serve(consumer { value ->
                runs++
                val process = ProcessBuilder("python3", script.toString(), value.toString()).start()
                check(process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); "python timeout" }
                check(process.exitValue() == 0) { process.errorStream.bufferedReader().readText() }
                latest = process.inputStream.bufferedReader().readText().trim().toInt()
                outlet.call.provide(latest)
            })
        }
        override fun snapshot(): Serializable = latest
        override fun restore(state: Serializable) { latest = state as Int }
        override fun importFrom(prior: Serializable) { restore(prior) }
    }

    class EffectPython(ref: CellRef, script: Path) : Python(ref, script), Effectful
    class Collector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val values = mutableListOf<Int>()
        init { inlet.serve(consumer { values += it }) }
    }
    class Wrong(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())
    }
    interface GateInput { val dataInlet: Use<Consumer<Int>> }

    inner class EvolutionRig {
        val sim = SimulationController(21)
        val scheduler = sim.scheduler()
        val host = ManagedHost(scheduler = scheduler)
        val gate = TrafficLightCell.create<Consumer<Int>>()
        val live = Collector()
        val preview = Collector()
        init { listOf(gate, live, preview).forEach { host.managementInlet.call.spawn(it) }; sim.runToIdle() }
        fun connect(from: Cell, outlet: String, to: Cell, inlet: String, role: LinkRole = LinkRole.Consume) {
            assertTrue(host.managementInlet.call.connect(from.ref, outlet, to.ref, inlet,
                LinkOptions(role, staged = true)) is LinkResult.Connected)
        }
        fun install(incumbent: Python, candidate: Python) {
            host.managementInlet.call.spawn(incumbent)
            Shadow.spawn(host, candidate); sim.runToIdle()
            connect(gate, "dataOutlet", incumbent, "inlet")
            connect(gate, "dataOutlet", candidate, "inlet")
            connect(incumbent, "outlet", live, "inlet")
            connect(candidate, "outlet", preview, "inlet", LinkRole.Observe)
            gate.controlInlet.call.setGreen()
        }
        fun input(n: Int) {
            (HostedCellProxy.create(gate.ref, host, GateInput::class.java) as GateInput).dataInlet.call.provide(n)
            sim.runToIdle()
        }
    }

    private fun script(name: String, factor: Int, extra: String = ""): Path = temp.resolve(name).also {
        Files.writeString(it, "import sys\n$extra\nprint(int(sys.argv[1]) * $factor)\n")
    }

    // Part B needs separate observation and a compatible candidate behind a traffic gate.
    @Test fun purePythonShadowHasSeparateObservableOutputAndPromotionReplaysBufferedInput() {
        val r = EvolutionRig(); val logical = UUID.randomUUID()
        val old = Python(CellRef(logical, 1), script("old.py", 2))
        val next = Python(CellRef(logical, 2), script("next.py", 3))
        try {
            r.install(old, next); r.input(5)
            assertEquals(listOf(10), r.live.values)
            assertEquals(listOf(15), r.preview.values)
            assertEquals(1, next.runs)
            // The gate carries a real pending input through COMMIT, without a judge or invariant.
            r.gate.controlInlet.call.setRed(); r.input(7)
            assertEquals(1, old.runs); assertEquals(1, next.runs)
            Promotion.promote(r.host, r.gate, old, next, "outlet", listOf(r.live.inlet))
            r.sim.runToIdle()
            assertNull(r.host.portAt(old.ref, "inlet"))
            assertEquals(listOf(10, 21), r.live.values)
            assertEquals(listOf(15, 21), r.preview.values)
            assertEquals(1, old.runs); assertEquals(2, next.runs)
            r.input(4)
            assertEquals(listOf(10, 21, 12), r.live.values)
        } finally { r.scheduler.shutdown() }
    }

    // NoOp suppresses the whole Effectful handler, including after promotion.
    @Test fun effectfulPythonIsNoOpInShadowAndPromotionDoesNotRestoreItsHandler() {
        val r = EvolutionRig(); val logical = UUID.randomUUID()
        val old = Python(CellRef(logical, 1), script("old.py", 2))
        val next = EffectPython(CellRef(logical, 2), script("next.py", 3))
        try {
            r.install(old, next); r.input(5)
            assertEquals(listOf(10), r.live.values)
            assertEquals(0, next.runs); assertTrue(r.preview.values.isEmpty())
            Promotion.promote(r.host, r.gate, old, next, "outlet", listOf(r.live.inlet))
            r.sim.runToIdle(); r.input(7)
            assertEquals(0, next.runs)
            assertEquals(listOf(10), r.live.values)
        } finally { r.scheduler.shutdown() }
    }

    // Ordinary subprocess handlers have no kernel sandbox.
    @Test fun unclassifiedSubprocessReallyExecutesAndHasNoKernelSandbox() {
        val r = EvolutionRig(); val marker = temp.resolve("written-by-shadow")
        val old = Python(CellRef(UUID.randomUUID()), script("old.py", 2))
        val next = Python(CellRef(UUID.randomUUID()), script("writes.py", 3,
            "open(${JsonString(marker.toString())}, 'w').write('effect')"))
        try {
            r.install(old, next); r.input(2)
            assertEquals(listOf(6), r.preview.values)
            assertEquals("effect", Files.readString(marker))
        } finally { r.scheduler.shutdown() }
    }

    // Promotion refuses an incompatible selected outlet before retiring the incumbent.
    @Test fun mismatchedOutletContractAbortsBeforeTouchingIncumbent() {
        val r = EvolutionRig()
        val old = Python(CellRef(UUID.randomUUID()), script("old.py", 2))
        val next = Python(CellRef(UUID.randomUUID()), script("next.py", 3))
        try {
            r.install(old, next); val wrong = Wrong(); r.host.managementInlet.call.spawn(wrong)
            assertThrows(Promotion.PromotionAborted::class.java) {
                Promotion.promote(r.host, r.gate, old, wrong, "outlet", listOf(r.live.inlet))
            }
            r.input(8)
            assertEquals(listOf(16), r.live.values)
            assertNotNull(r.host.portAt(old.ref, "inlet"))
        } finally { r.scheduler.shutdown() }
    }

    // Part B must fence detached jobs itself before promotion.
    @Test fun promotionDoesNotWaitForAWorkerDetachedFromTheIncumbentHandler() {
        val r = EvolutionRig()
        val old = Python(CellRef(UUID.randomUUID()), script("old.py", 2))
        val next = Python(CellRef(UUID.randomUUID()), script("next.py", 3))
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val completed = CountDownLatch(1)
        old.inlet.serve(consumer { worker.submit {
            started.countDown()
            try { release.await(5, TimeUnit.SECONDS) } finally { completed.countDown() }
        }; Unit })
        try {
            r.install(old, next); r.input(5)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            Promotion.promote(r.host, r.gate, old, next, "outlet", listOf(r.live.inlet))
            r.sim.runToIdle()
            assertEquals(1L, completed.count) // external work is still in flight after RETIRE
            assertNull(r.host.portAt(old.ref, "inlet"))
        } finally { release.countDown(); worker.shutdownNow(); r.scheduler.shutdown() }
    }

    private fun JsonString(value: String) = kotlinx.serialization.json.Json.encodeToString(value)
}

internal fun <T> consumer(block: (T) -> Unit): Consumer<T> = object : Consumer<T> {
    override fun provide(input: T) = block(input)
}
