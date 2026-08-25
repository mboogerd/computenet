package civictech.testkit.dst

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.host.DeadLetter
import civictech.cell.host.HostColor
import civictech.cell.host.HostScheduler
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import java.util.Random

/**
 * The fault-aware simulation world ([CHA1-01]): [controller] + [registry] + one or more
 * [ManagedHost]s, plus the seams a [Fault] hooks and the observation a [TraceDigest] is made
 * of.
 *
 * It is [civictech.testkit.SimWorld]'s adversarial sibling, not its replacement: the same
 * controller/registry/host triple, the same budgeted drain, with the *controller exposed
 * unchanged* so a consumer can still do anything it did before. What is added is a set of
 * declaration points — every host, edge, journal and cell the graph builds gets a **name** —
 * and six seams that let a fault reach those names without the world knowing what the fault
 * is.
 *
 * ## The six seams
 *
 * A fault class is written against these and nothing else. **Adding a fault class must never
 * edit this file.**
 *
 *  1. [edges] — named-edge frame interposers. Register by edge name; interposers chain.
 *  2. [hosts] — per-host slots, each holding the caller-supplied deterministic rebuild
 *     function a crash fault needs.
 *  3. [journals] — per-journal decoration, composed over `ManagedHost(journalFor = ...)`,
 *     resolved per call so a decoration installed mid-run takes effect without re-wiring.
 *  4. [steps] — a hook fired before each controller step, with that step's index.
 *  5. [trace] — trace and fault-tag emission (which is also the per-fault firing count).
 *  6. [deadLetters] — raw dead-letter capture. Classification is a later task's job; this is
 *     the unfiltered record.
 *
 * ## What this world does not do
 *
 * It does not *drive* itself: [DstRun] owns the loop, the budget and the outcome, so that the
 * identical loop can be run with an empty plan to produce the [CHA1-04] baseline. And it owns
 * no fault classes; a plan of `ScriptedFault`s is all the rig itself ever builds.
 */
class DstWorld(val seed: Long) {

    /** The kernel controller, exposed unchanged ([CHA1-01]). Seeded from [seed] ([CHA1-30]). */
    val controller: SimulationController = SimulationController(seed)

    val registry: LocationRegistry = LocationRegistry()

    private val fanout = SeedFanout(seed)
    private val recorder = TraceRecorder()
    private val letters = mutableListOf<DeadLetter>()
    private val activity = linkedMapOf<String, MutableFaultActivity>()

    /**
     * The step index currently being driven, i.e. the stamp on every [TraceEvent] emitted
     * right now. `-1` before the first step and after the run ends.
     */
    var step: Int = -1
        private set

    /**
     * Every source of randomness in a run derives from [seed], by [purpose] ([CHA1-30]).
     * Repeated calls with the same purpose return the same generator; different purposes are
     * independent, so adding a fault does not re-roll a graph builder's workload.
     */
    fun rng(purpose: String): Random = fanout.rng(purpose)

    // ---------------------------------------------------------------- seam 5: trace

    /** **Seam 5 of 6.** See [TraceSink]; the step index is stamped from [step]. */
    val trace: TraceSink = object : TraceSink {
        override fun emit(host: String?, cell: String?, port: String?) {
            recorder.record(TraceEvent(step, host, cell, port, null))
        }

        override fun fault(faultId: String, host: String?, cell: String?, port: String?) {
            recorder.record(TraceEvent(step, host, cell, port, faultId))
            activity.getOrPut(faultId) { MutableFaultActivity() }.fire(step)
        }
    }

    /** The trace so far, in observation order ([CHA1-05]). */
    fun traceEvents(): List<TraceEvent> = recorder.events()

    /** The digest of the trace so far ([CHA1-05]). */
    fun traceDigest(): TraceDigest = recorder.digest()

    /** Convenience: emit a [CellRef] event under the cell's declared name, if it has one. */
    fun traceCell(ref: CellRef, port: String? = null, host: String? = null) =
        trace.emit(host = host, cell = cells.nameOf(ref), port = port)

    // ---------------------------------------------------------------- seam 6: dead letters

    /**
     * **Seam 6 of 6.** Every [DeadLetter] any declared host emitted, in arrival order, raw
     * and unclassified — subscribing to `ManagedHost.deadLetterOutlet` costs no simulation
     * step (the host submits the emission task whether or not anyone listens), so this
     * observation cannot perturb scheduling.
     *
     * Deliberately *not* traced: a classifier and a "no unexplained dead letter" check are
     * [CHA1-52]'s, a later task. Keeping them out of the trace also keeps the digest a
     * property of scheduling rather than of a classification policy that will change.
     */
    val deadLetters: List<DeadLetter> get() = letters.toList()

    private fun captureDeadLetters(host: ManagedHost) {
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        letters += value
                    }
                },
                PortRef.generate(),
            ),
        )
    }

    // ---------------------------------------------------------------- seam 4: step hooks

    /** **Seam 4 of 6.** See [StepHooks]. */
    val steps: StepHooks = StepHooks()

    /** Called by [DstRun] before it drives step [index]; not part of the consumer API. */
    internal fun beginStep(index: Int) {
        step = index
        steps.fire(this, index)
    }

    /** Called by [DstRun] when the run ends, so late observation is not stamped with a step. */
    internal fun endRun() {
        step = -1
    }

    // ---------------------------------------------------------------- seam 1: edges

    /** **Seam 1 of 6.** See [Edges]. */
    val edges: Edges = Edges(this)

    // ---------------------------------------------------------------- seam 2: hosts

    /** **Seam 2 of 6.** See [HostSlots]. */
    val hosts: HostSlots = HostSlots(this, ::captureDeadLetters)

    // ---------------------------------------------------------------- seam 3: journals

    /** **Seam 3 of 6.** See [Journals]. */
    val journals: Journals = Journals()

    // ---------------------------------------------------------------- cell names

    /**
     * Declared cell names. Not a seam — a *vocabulary*: it gives [FaultTarget.Cell] something
     * to validate against and gives the digest a stable name for a cell whose `CellRef` is a
     * fresh random UUID per construction. See [TraceEvent].
     */
    val cells: Cells = Cells()

    // ---------------------------------------------------------------- fault accounting

    /** Per-fault firing record ([CHA1-24]); a fault absent here, or with `fired == 0`, is inert. */
    fun faultActivity(): Map<String, FaultActivity> =
        activity.mapValues { (_, v) -> FaultActivity(v.fired, v.activationSteps.toList()) }

    /** Declares a fault to the accounting so it is reported inert rather than missing. */
    internal fun declareFault(faultId: String) {
        activity.getOrPut(faultId) { MutableFaultActivity() }
    }

    private class MutableFaultActivity {
        var fired: Int = 0
        val activationSteps = mutableListOf<Int>()
        fun fire(step: Int) {
            fired++
            if (activationSteps.lastOrNull() != step) activationSteps += step
        }
    }
}

/** How often a fault actually fired, and at which step indices ([CHA1-24]). */
data class FaultActivity(val fired: Int, val activationSteps: List<Int>) {
    /** A fault that never fired: configured, applied to something real, and yet inert. */
    val inert: Boolean get() = fired == 0
}

// -------------------------------------------------------------------------------------------
// Seam 1 — named-edge frame interposers
// -------------------------------------------------------------------------------------------

/**
 * Transforms a frame in flight on one edge. Returns the frames to deliver in its place:
 * the frame unchanged (pass), **empty** (drop), the same frame twice (duplicate), a mutated
 * copy (corrupt), or a frame held back and released on a **later call of this same method**
 * (delay/reorder — hold it in the interposer's own state and prepend it to what a subsequent
 * frame produces).
 *
 * **Known limit — an edge is a transform, not a transport.** [Edge.deliver] hands its result
 * back to the *graph builder*, which is what actually delivers; this seam owns no sink. So a
 * held frame can only leave the buffer when another frame traverses the same edge, and a
 * [StepHooks] hook has **no** way to release one: nothing a hook can call reaches the target
 * host. A reorder or delay fault must therefore release on a window-full condition rather than
 * on a step deadline, and frames still held when traffic stops are stranded for the rest of
 * the run. Closing that gap means giving [Edges.declare] a delivery sink and adding an
 * injection call — a change to this file, which the six-seam contract otherwise forbids, so it
 * belongs to whoever owns the seam rather than to a fault class working around it.
 *
 * @param step the controller step during which the frame is in flight, so an interposer can
 *   decide by step index without keeping a clock of its own ([CHA1-02]).
 */
fun interface FrameInterposer {
    fun apply(frame: ByteArray, step: Int): List<ByteArray>
}

/**
 * **Seam 1 of 6 — the named-edge interposer registry.** The frame plane's single hook point:
 * partition, drop, duplicate, corrupt, delay and cross-link reorder are all interposers on a
 * named edge.
 *
 * **An edge is one direction.** A bidirectional wire is declared as two edges (`a->b` and
 * `b->a`), which is exactly what makes a one-way partition ([CHA1-13]) expressible without
 * any notion of direction in this API: the fault targets one of the two names.
 *
 * Interposers **chain** in registration order, innermost first: each is handed what the
 * previous one produced, and one interposer's duplicate is the next one's two frames. Every
 * registration returns an [AutoCloseable] that removes it, which is how a windowed fault
 * heals at the end of its window.
 *
 * A graph builder wires the edge by routing its frames through [Edge.deliver]; with no
 * interposers registered that is the identity, plus one trace event per frame — so the same
 * builder produces a trace under a bare controller run and under the rig ([CHA1-04]).
 */
class Edges internal constructor(private val world: DstWorld) {

    private val declared = linkedMapOf<String, Edge>()

    /**
     * Declare an edge the graph carries frames on. [from]/[to] are declared host names used
     * for the trace and the report; they are documentation, not routing.
     */
    fun declare(name: String, from: String? = null, to: String? = null): Edge {
        require(name !in declared) { "edge \"$name\" is already declared" }
        return Edge(name, from, to, world).also { declared[name] = it }
    }

    fun names(): Set<String> = declared.keys.toSet()

    fun find(name: String): Edge? = declared[name]

    fun require(name: String): Edge =
        declared[name] ?: throw IllegalArgumentException("unknown edge \"$name\"; known edges: ${names().sorted()}")

    /** Register an interposer on an edge. Returns a handle that removes it again. */
    fun intercept(name: String, interposer: FrameInterposer): AutoCloseable = require(name).intercept(interposer)

    /** Route a frame through the named edge's interposer chain. */
    fun deliver(name: String, frame: ByteArray): List<ByteArray> = require(name).deliver(frame)
}

/** One declared, unidirectional edge. See [Edges]. */
class Edge internal constructor(
    val name: String,
    val from: String?,
    val to: String?,
    private val world: DstWorld,
) {
    private val interposers = mutableListOf<FrameInterposer>()

    fun intercept(interposer: FrameInterposer): AutoCloseable {
        interposers += interposer
        return AutoCloseable { interposers -= interposer }
    }

    /** True while anything is intercepting this edge — for a report, never for control flow. */
    val intercepted: Boolean get() = interposers.isNotEmpty()

    /**
     * Apply the chain to [frame] and return what should actually be delivered. The graph
     * builder calls this from its wire wiring; the frames it returns are the graph's problem
     * to deliver, which is what keeps this seam transport-neutral.
     */
    fun deliver(frame: ByteArray): List<ByteArray> {
        world.trace.emit(host = from, port = name)
        var frames = listOf(frame)
        interposers.forEach { interposer ->
            frames = frames.flatMap { interposer.apply(it, world.step) }
        }
        return frames
    }
}

// -------------------------------------------------------------------------------------------
// Seam 2 — host slots with a caller-supplied deterministic rebuild
// -------------------------------------------------------------------------------------------

/**
 * Everything a host build needs, handed to the caller's rebuild function on every generation.
 *
 * The scheduler is **fresh per generation** — a crashed host's queued and suspended work must
 * not come back — while [registry] and the journals are the same objects, because surviving
 * the crash is precisely their job.
 */
class HostBuildContext(
    val name: String,
    val generation: Int,
    val scheduler: HostScheduler,
    val registry: LocationRegistry,
    val journals: Journals,
    val world: DstWorld,
)

/**
 * **Seam 2 of 6 — per-host slots holding the caller-supplied deterministic rebuild function.**
 *
 * A crash fault does not know how to rebuild a consumer's graph and must not guess: rebuilding
 * a host means re-spawning its cells with the *same* `CellRef`s, and the rig cannot derive
 * those for an arbitrary graph (epic §9 risk 5). So the graph builder supplies the rebuild
 * function when it declares the host, and a crash fault is reduced to calling [HostSlot.crash].
 *
 * The honest form of the limitation, stated where a builder will read it: **crash faults
 * require the consumer to supply a deterministic rebuild function.** A rebuild that derives
 * fresh refs produces a different graph after the crash, and the run that follows is testing
 * something nobody asked about.
 */
class HostSlots internal constructor(
    private val world: DstWorld,
    private val afterBuild: (ManagedHost) -> Unit,
) {
    private val slots = linkedMapOf<String, HostSlot>()

    /**
     * Declare a host under [name], building generation 0 immediately.
     *
     * @param build must be deterministic given its [HostBuildContext]: same context, same
     *   cells, same `CellRef`s, same links, in the same order.
     */
    fun declare(
        name: String,
        color: HostColor = HostColor.BLOCKING,
        build: (HostBuildContext) -> ManagedHost,
    ): HostSlot {
        require(name !in slots) { "host \"$name\" is already declared" }
        return HostSlot(name, color, build, world, afterBuild).also { slots[name] = it }
    }

    fun names(): Set<String> = slots.keys.toSet()

    fun find(name: String): HostSlot? = slots[name]

    fun require(name: String): HostSlot =
        slots[name] ?: throw IllegalArgumentException("unknown host \"$name\"; known hosts: ${names().sorted()}")

    /** The live host behind each declared name, current generation. */
    fun all(): List<ManagedHost> = slots.values.map { it.host }
}

/** One declared host and its rebuild function. See [HostSlots]. */
class HostSlot internal constructor(
    val name: String,
    private val color: HostColor,
    private val build: (HostBuildContext) -> ManagedHost,
    private val world: DstWorld,
    private val afterBuild: (ManagedHost) -> Unit,
) {
    /** How many times this host has been rebuilt; 0 is the original. */
    var generation: Int = 0
        private set

    private var currentScheduler: HostScheduler = world.controller.scheduler(color)

    /** The live host. Changes identity across a [crash]; re-read it, never cache it. */
    var host: ManagedHost = newHost()
        private set

    private fun newHost(): ManagedHost =
        build(HostBuildContext(name, generation, currentScheduler, world.registry, world.journals, world))
            .also(afterBuild)

    /**
     * Discard this host and rebuild it: the current scheduler is shut down (queued work and
     * any suspended task go with it — that is the crash), a fresh scheduler is registered with
     * the controller, and the caller's build function runs again at the next [generation].
     *
     * Recovery is *not* done here. `recoverFrom(journal)` is the rebuild function's business,
     * or the fault's after this returns, because which journal a rebuilt host recovers from —
     * and from which prefix — is exactly what a journal or frontier fault varies.
     *
     * @param faultId when a fault is doing the crashing, its id: the crash is then traced and
     *   counted as that fault firing ([CHA1-24]). Null for a consumer-driven rebuild.
     */
    fun crash(faultId: String? = null): ManagedHost {
        currentScheduler.shutdown()
        generation++
        currentScheduler = world.controller.scheduler(color)
        host = newHost()
        if (faultId != null) world.trace.fault(faultId, host = name) else world.trace.emit(host = name)
        return host
    }
}

// -------------------------------------------------------------------------------------------
// Seam 3 — per-journal decoration
// -------------------------------------------------------------------------------------------

/**
 * **Seam 3 of 6 — per-cell journal decoration, composed over `ManagedHost(journalFor = ...)`.**
 *
 * A graph builder declares a named journal and hands the returned [Journal] to the host's
 * `journalFor` selector. The returned object is a stable *view*: every call resolves the
 * current decoration chain, so a journal fault installed at step 400 takes effect immediately,
 * on the journal the host already holds, with no re-wiring and no host rebuild.
 *
 * Decorations compose innermost-first in registration order over [base]; each registration
 * returns an [AutoCloseable] that removes it, so a windowed mutation heals.
 */
class Journals {

    private val bases = linkedMapOf<String, Journal>()
    private val decorations = linkedMapOf<String, MutableList<(Journal) -> Journal>>()
    private val views = linkedMapOf<String, Journal>()

    /**
     * Declare a named journal over [base] and return the decorated view to hand to
     * `ManagedHost(journalFor = ...)`. The view survives host rebuilds; [base] is the real
     * store, and it is what a crash must *not* discard.
     */
    fun declare(name: String, base: Journal = InMemoryJournal()): Journal {
        require(name !in bases) { "journal \"$name\" is already declared" }
        bases[name] = base
        decorations[name] = mutableListOf()
        return DecoratedJournal(name, this).also { views[name] = it }
    }

    fun names(): Set<String> = bases.keys.toSet()

    /** The undecorated journal — what a mutation reads to compute its own effect. */
    fun base(name: String): Journal =
        bases[name] ?: throw IllegalArgumentException("unknown journal \"$name\"; known journals: ${names().sorted()}")

    /** The decorated view handed to the host, for a fault that needs to re-hand it. */
    fun view(name: String): Journal =
        views[name] ?: throw IllegalArgumentException("unknown journal \"$name\"; known journals: ${names().sorted()}")

    /** Compose [decoration] over the named journal until the returned handle is closed. */
    fun decorate(name: String, decoration: (Journal) -> Journal): AutoCloseable {
        val chain = decorations[name]
            ?: throw IllegalArgumentException("unknown journal \"$name\"; known journals: ${names().sorted()}")
        chain += decoration
        return AutoCloseable { chain -= decoration }
    }

    /** True while anything decorates this journal — for a report, never for control flow. */
    fun decorated(name: String): Boolean = decorations[name]?.isNotEmpty() ?: false

    private fun resolve(name: String): Journal =
        decorations.getValue(name).fold(bases.getValue(name)) { inner, decorate -> decorate(inner) }

    private class DecoratedJournal(private val name: String, private val journals: Journals) : Journal {
        override val formatVersion: Int get() = journals.resolve(name).formatVersion
        override fun append(record: ByteArray) = journals.resolve(name).append(record)
        override fun replay(): List<ByteArray> = journals.resolve(name).replay()
        override fun reset(records: List<ByteArray>) = journals.resolve(name).reset(records)
        override fun toString(): String = "Journal($name)"
    }
}

// -------------------------------------------------------------------------------------------
// Seam 4 — step hooks
// -------------------------------------------------------------------------------------------

/** Fired before controller step [step] runs. See [StepHooks]. */
fun interface StepHook {
    fun at(world: DstWorld, step: Int)
}

/**
 * **Seam 4 of 6 — the step hook.** Fired *before* each controller step, with the index of the
 * step about to run, which makes step indices the rig's only activation clock ([CHA1-02]).
 *
 * A hook may inject work (that is what a fault firing at step N means); the driving loop
 * re-checks for work after every hook, so a fault can legitimately revive a simulation that
 * had gone quiet. A hook must not itself call [SimulationController.step] — driving is
 * [DstRun]'s, and a nested step would break the step/trace correspondence.
 *
 * Hooks fire in registration order; each registration returns an [AutoCloseable] that removes
 * it, so a fault that has finished its window stops paying for a hook.
 */
class StepHooks {
    private val hooks = mutableListOf<StepHook>()

    fun onStep(hook: StepHook): AutoCloseable {
        hooks += hook
        return AutoCloseable { hooks -= hook }
    }

    internal fun fire(world: DstWorld, step: Int) {
        // snapshot: a hook may install or remove hooks (a window opening or healing)
        hooks.toList().forEach { it.at(world, step) }
    }
}

// -------------------------------------------------------------------------------------------
// Cell names
// -------------------------------------------------------------------------------------------

/**
 * The graph's declared cell names: a target vocabulary for [FaultTarget.Cell] and the stable
 * identity the trace digest uses in place of a random `CellRef` UUID. See [TraceEvent].
 */
class Cells {
    private val byName = linkedMapOf<String, CellRef>()
    private val byRef = linkedMapOf<CellRef, String>()

    fun declare(name: String, ref: CellRef): CellRef {
        require(name !in byName) { "cell \"$name\" is already declared" }
        byName[name] = ref
        byRef[ref] = name
        return ref
    }

    /** Re-point a name at a new ref — a rebuilt host re-declaring its cells after a crash. */
    fun redeclare(name: String, ref: CellRef): CellRef {
        byName[name]?.let { byRef -= it }
        byName[name] = ref
        byRef[ref] = name
        return ref
    }

    fun names(): Set<String> = byName.keys.toSet()

    fun nameOf(ref: CellRef): String = byRef[ref] ?: ref.id.toString()

    fun find(name: String): CellRef? = byName[name]

    fun require(name: String): CellRef =
        byName[name] ?: throw IllegalArgumentException("unknown cell \"$name\"; known cells: ${names().sorted()}")
}
