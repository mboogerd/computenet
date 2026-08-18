package civictech.oracle.run

import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.testkit.SimWorld
import kotlin.random.Random

/**
 * The bring-your-own entry point `[ORA1-DIFF-11]`: run a **caller-supplied** graph against a
 * **caller-supplied** [Script] and a **caller-supplied** reference, and report the
 * disagreement — if any — through the shared [RunOutcome] taxonomy.
 *
 * This is the API KE1/QRY1/BB-TPCH consume, and it is deliberately the *same* comparison,
 * reporting and taxonomy code the generated-case path (a later task in computenet-4ru.8) will
 * reuse: there is no BYO-only shortcut here. A generated case differs from a BYO one only in
 * who supplies [check]'s arguments — the generator renders its `GraphSpec` into
 * [caseMarker] and resolves its catalog ids into a `buildGraph` lambda, and everything from
 * driving to reporting is this object's.
 *
 * ## Driving
 *
 * Script events are injected interleaved with **partial drains** — the
 * `repeat(rnd.nextInt(4)) { controller.step() }` idiom from
 * `kernel/src/test/kotlin/civictech/cell/verify/GenerativeGraphTest.kt` — so a case exercises
 * the incremental path rather than a single batch settle at the end. The interleaving is
 * random across sources and **order-preserving within** a source, which is exactly [Script]'s
 * own contract: a source cell is one serialization point, while two sources are genuinely
 * concurrent. All randomness derives from [check]'s `seed`, so a run replays exactly.
 *
 * The step budget is this runner's **own counted step loop**, never
 * [SimWorld.runToIdle]'s `check(...)`: a budget exhaustion is a *verdict*
 * ([RunOutcome.NonQuiescence]) that the caller matches on, and catching an
 * `IllegalStateException` as control flow would make the taxonomy depend on a message string
 * — the thing [RunOutcome] is sealed to prevent `[ORA1-DIFF-07]`.
 *
 * ## Kind precedence
 *
 * Several conditions can hold at once. When they do, the outcome is the **first** of:
 *
 * 1. [RunOutcome.NonQuiescence] — the run never settled, so every terminal's fold is a
 *    partial reading of an unfinished computation. Comparing it to the model would report a
 *    "mismatch" that is really an unfinished run.
 * 2. [RunOutcome.DeadLetterFailure] — a message was lost, so the folds are readings of a
 *    graph that did not receive its whole input `[ORA1-DIFF-04]`. Values *may* still agree
 *    (they do in this file's own precedence test), and that agreement is luck, not evidence.
 * 3. [RunOutcome.ModelEvaluationFailure] — the reference itself threw, so there is no
 *    expected value to compare against at all. Reported as a broken *oracle*, never as a
 *    broken kernel (epic design D10, `[ORA1-DIFF-08]`).
 * 4. [RunOutcome.Mismatch] — the only kind that is evidence about the kernel, and therefore
 *    the only one reachable once the three above are excluded.
 *
 * The rationale generalizes to one sentence, worth keeping: **an earlier kind invalidates the
 * comparison behind the later ones**, so reporting a later kind while an earlier one holds
 * would attribute to the kernel a disagreement that nothing about the kernel caused.
 *
 * ## Writer semantics in this task's scope
 *
 * [ScriptEvent.Observe] maps to **no kernel injection**: it is a model-only causality
 * statement (see `civictech.oracle.model.Membership`), and there is no kernel call that means
 * "this writer has now seen everything". [ScriptEvent.Add] and [ScriptEvent.Remove] map to
 * [SetOps.add]/[SetOps.remove]. The keyed and counter events are rejected loudly rather than
 * silently skipped — the set family is all the BYO entry point claims today, and a silently
 * dropped `Increment` would show up as a mismatch blamed on the kernel.
 */
object DifferentialRunner {

    /**
     * The default step budget, matching `SimWorld.runToIdle`'s own default. A case that needs
     * more than this has either livelocked or is not a unit-scale case; both are worth a
     * verdict rather than a hang.
     */
    const val DEFAULT_STEP_BUDGET: Int = 200_000

    /**
     * Run one differential case and report what it concluded.
     *
     * @param seed the case's seed. Drives the simulation's host-choice randomness and the
     *   injection interleaving, and is carried by every failure kind so a counterexample
     *   replays.
     * @param caseMarker how this case is identified in a [RunOutcome.Mismatch] report — the
     *   caller's own marker on this path; the rendered `GraphSpec` on the generated one.
     * @param script the model input. Injected into the kernel graph AND handed to
     *   [reference]; that both sides read the same script is what makes the comparison a
     *   differential test rather than two unrelated computations.
     * @param reference the substitutable oracle. `ReferenceModel::eval` fits it directly, and
     *   so does a plain Kotlin fold, or a deliberately wrong fold — which is how the controls
     *   feature substitutes a divergent reference with no change to this signature.
     * @param stepBudget the run's total simulation-step budget, spent across the partial
     *   drains and the final drain alike.
     * @param buildGraph spawns the case's cells on the supplied [SimWorld], links them, and
     *   returns the [CaseGraph] naming its terminals and its script sources.
     */
    fun check(
        seed: Long,
        caseMarker: String,
        script: Script,
        reference: Reference,
        stepBudget: Int = DEFAULT_STEP_BUDGET,
        buildGraph: (SimWorld) -> CaseGraph,
    ): RunOutcome = execute(seed, caseMarker, script, reference, stepBudget, buildGraph) { driving ->
        val rnd = driving.rnd
        val cursors = script.slices.map { Cursor(it.source, it.events) }.filter { it.remaining() }.toMutableList()
        while (cursors.isNotEmpty()) {
            val cursor = cursors[rnd.nextInt(cursors.size)]
            driving.inject(cursor.source, cursor.next())
            if (!cursor.remaining()) cursors.remove(cursor)
            driving.partialDrain()
        }
    }

    /**
     * The **generated** path of `[ORA1-DIFF-01]`: apply [case]'s `GraphSpec`, drive its
     * [civictech.oracle.gen.CaseScript] in TOTAL order honoring every
     * [civictech.oracle.gen.CaseStep.Barrier] as a quiesce point, fold every (non-late)
     * terminal, and compare each fold to the **catalog-resolved** reference model.
     *
     * Everything after driving — dead-letter accounting, the step budget, the kind
     * precedence, the `[ORA1-DIFF-02]` report — is [check]'s own code, reached through the
     * same private core. The only difference between the two entry points is who supplies the
     * graph, the script and the reference.
     *
     * ## Catalog resolution
     *
     * The runner is the component that resolves catalog ids: `civictech.oracle.model`
     * deliberately does not import `civictech.oracle.bind` (a package cycle, and the
     * independence that makes the model a check rather than a second copy). See
     * [CaseExecution.referenceModelFor] for the mapping and for what an unresolvable or
     * wrongly-typed id does — it fails loudly NAMING the id, never a silent skip.
     *
     * ## Driving
     *
     * Total order, because a [civictech.oracle.gen.CaseScript] *is* a total order — the model
     * needs none (sources are independent) but a replay against a live kernel does. Per-source
     * arrival order equals script order because injection order is submission order and a
     * host's (priority, submission) order is inviolable (`SimulationController`'s KDoc), so no
     * extra sequencing is needed to make a source's slice match what the model folded.
     *
     * Sources are driven through a **hosted proxy**, never a direct inlet call. A direct call
     * on a co-hosted graph dispatches inline and costs ZERO scheduler steps, which makes any
     * budget or partial-drain reasoning vacuous — confirmed on computenet-4ru.8.2, whose first
     * BS-10 attempt returned `Success` instead of `NonQuiescence` for exactly this reason. See
     * [CaseExecution.applyCase].
     *
     * @param case the case to run — hand-built or generated; this runner never invokes the
     *   generator.
     * @param reference the substitutable oracle. `null` (the default) resolves it from the
     *   catalog through [CaseExecution.referenceModelFor]; the controls feature substitutes a
     *   deliberately wrong one here, with no change to this signature.
     * @param onBarrier called at every [civictech.oracle.gen.CaseStep.Barrier], after the
     *   graph has quiesced, with every terminal's fold as of that point — the observation the
     *   barrier exists to make possible. Not called if the budget ran out first.
     */
    fun run(
        case: GeneratedCase,
        reference: Reference? = null,
        stepBudget: Int = DEFAULT_STEP_BUDGET,
        onBarrier: (Map<String, ModelState>) -> Unit = {},
    ): RunOutcome {
        val model = CaseExecution.referenceModelFor(case.topology)
        val oracle = reference ?: Reference(model::eval)
        val marker = CaseExecution.renderSpec(case.spec, case.topology.nodes.associate { it.handle to it.catalogId })
        return execute(
            seed = case.seed,
            caseMarker = marker,
            script = case.script.toScript(),
            reference = oracle,
            stepBudget = stepBudget,
            buildGraph = { world -> CaseExecution.applyCase(case, world) },
        ) { driving ->
            for (step in case.script.steps) {
                if (driving.exhausted) break
                when (step) {
                    is CaseStep.Op -> {
                        driving.inject(step.source, step.event)
                        driving.partialDrain()
                    }

                    CaseStep.Barrier -> {
                        driving.drainToIdle()
                        if (!driving.exhausted) onBarrier(driving.readTerminals())
                    }
                }
            }
        }
    }

    /**
     * The shared core both entry points reach: build, subscribe, drive (however [drive] says),
     * settle, then apply the kind precedence and the comparison.
     */
    private fun execute(
        seed: Long,
        caseMarker: String,
        script: Script,
        reference: Reference,
        stepBudget: Int,
        buildGraph: (SimWorld) -> CaseGraph,
        drive: (Driving) -> Unit,
    ): RunOutcome {
        val world = SimWorld(seed = seed)
        val graph = buildGraph(world)

        // Subscribed before any event is injected, and to EVERY host the case names: a dead
        // letter raised by a host nobody watched is indistinguishable from no dead letter,
        // which is exactly the silent-loss failure [ORA1-DIFF-04] exists to catch.
        val letters = mutableListOf<DeadLetter>()
        (listOf(world.host) + graph.extraHosts).distinct().forEach { host ->
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

        val driving = Driving(world, graph, Random(seed), stepBudget)
        drive(driving)
        driving.drainToIdle()

        // One probe beyond the budget: `controller.step()` is the only observation of "is
        // there work left", and spending one extra step to answer it is cheaper than
        // reporting a mismatch for a run that had simply not finished.
        if (driving.exhausted && world.controller.step()) {
            return RunOutcome.NonQuiescence(seed, stepBudget)
        }
        if (letters.isNotEmpty()) return RunOutcome.DeadLetterFailure(seed, letters.toList())

        val expectedStates = try {
            reference.evaluate(script)
        } catch (cause: Throwable) {
            return RunOutcome.ModelEvaluationFailure(seed, cause)
        }

        graph.terminals.forEach { (name, fold) ->
            val expected = expectedStates[name]
                ?: error(
                    "The reference produced no state for terminal '$name'; it named " +
                        "${expectedStates.keys.sorted()}. A terminal the reference cannot " +
                        "evaluate is a wiring bug in the case, not an oracle finding.",
                )
            val actual = fold.current()
            if (expected != actual) {
                return RunOutcome.Mismatch(
                    seed = seed,
                    terminal = name,
                    renderedGraphSpec = caseMarker,
                    script = script,
                    expected = expected,
                    actual = actual,
                    difference = StateDifference.between(expected, actual),
                )
            }
        }
        return RunOutcome.Success
    }

    /**
     * The live run, as a driving strategy sees it: inject an event, spend some steps, ask
     * whether the budget is gone, read the terminals.
     *
     * The step budget is this runner's **own counted step loop**, never
     * [SimWorld.runToIdle]'s `check(...)`: a budget exhaustion is a *verdict*
     * ([RunOutcome.NonQuiescence]) that the caller matches on, and catching an
     * `IllegalStateException` as control flow would make the taxonomy depend on a message
     * string `[ORA1-DIFF-07]`.
     */
    class Driving internal constructor(
        private val world: SimWorld,
        private val graph: CaseGraph,
        /** The run's own random stream, derived from the seed, so a run replays exactly. */
        val rnd: Random,
        private val stepBudget: Int,
    ) {
        private var steps = 0

        /** Whether the budget is spent — a driving strategy stops rather than piling on work. */
        val exhausted: Boolean get() = steps >= stepBudget

        private fun stepOnce(): Boolean {
            if (steps >= stepBudget) return false
            return world.controller.step().also { if (it) steps++ }
        }

        /**
         * A partial drain — `repeat(rnd.nextInt(4)) { step() }`, the idiom from
         * `kernel/src/test/kotlin/civictech/cell/verify/GenerativeGraphTest.kt`, so a case
         * exercises the incremental path rather than one batch settle at the end.
         */
        fun partialDrain() {
            repeat(rnd.nextInt(4)) { stepOnce() }
        }

        /** Steps until nothing is left to do, or until the budget is spent. */
        fun drainToIdle() {
            while (stepOnce()) { /* still on the same budget */ }
        }

        /** Every terminal's fold as of now. */
        fun readTerminals(): Map<String, ModelState> = graph.terminals.mapValues { (_, fold) -> fold.current() }

        /**
         * One script event onto its source cell. [ScriptEvent.Observe] injects nothing — see
         * [DifferentialRunner]'s "Writer semantics" KDoc — and is handled before the source is
         * even resolved, so a script may carry `Observe` events for a source the graph does
         * not bind.
         */
        fun inject(source: SourceId, event: ScriptEvent) {
            if (event is ScriptEvent.Observe) return
            val sink = graph.sources[source]
                ?: error(
                    "Script drives source '${source.id}', which the case graph does not bind; " +
                        "it binds ${graph.sources.keys.map { it.id }.sorted()}.",
                )
            when (event) {
                is ScriptEvent.Add -> sink.add(event.element)
                is ScriptEvent.Remove -> sink.remove(event.element)
                is ScriptEvent.Put -> sink.put(event.key, event.element)
                is ScriptEvent.RemoveKey -> sink.removeKey(event.key)
                is ScriptEvent.Increment -> sink.increment(event.amount)
                is ScriptEvent.Decrement -> sink.decrement(event.amount)
                is ScriptEvent.Observe -> Unit // unreachable: handled above
            }
        }
    }

    /** One source's log plus its position in it — the per-source order the interleaving preserves. */
    private class Cursor(val source: SourceId, private val events: List<ScriptEvent>) {
        private var index = 0
        fun remaining(): Boolean = index < events.size
        fun next(): ScriptEvent = events[index++]
    }
}

/**
 * The substitutable oracle a differential run compares against: script in, one [ModelState]
 * per terminal name out.
 *
 * A `fun interface` rather than a bare typealias so a callable reference converts directly —
 * `civictech.oracle.model.ReferenceModel::eval` has exactly this shape — while a hand-written
 * fold, or a deliberately wrong one, is just as valid an argument. That substitutability is
 * what keeps the controls feature (a divergent reference must yield
 * [RunOutcome.Mismatch]) expressible with no change to [DifferentialRunner.check]'s signature.
 */
fun interface Reference {
    /** Every terminal's expected state, from [script] alone. */
    fun evaluate(script: Script): Map<String, ModelState>
}

/**
 * What a caller's `buildGraph` hands back: the observation points to fold and the source cells
 * to drive.
 *
 * @property terminals terminal name → its [TerminalFold]. The names are the keys
 *   [Reference.evaluate] must answer for; a name the reference does not know is a wiring bug
 *   in the case and fails loudly rather than being reported as a disagreement.
 * @property sources script [SourceId] → the kernel source cell to drive. See
 *   [SetOps.asScriptSource].
 * @property extraHosts any host the case created **besides** the [SimWorld]'s own — a
 *   two-host placement, say. Every one is dead-letter-subscribed; an unnamed host is an
 *   unwatched one.
 */
class CaseGraph(
    val terminals: Map<String, TerminalFold>,
    val sources: Map<SourceId, ScriptSource>,
    val extraHosts: List<ManagedHost> = emptyList(),
)

/**
 * A script source bound to a kernel source cell. The runner decides *what* a script event
 * means (see [DifferentialRunner]'s writer semantics); this is only the binding to the cell
 * that receives it.
 */
interface ScriptSource {
    fun add(element: Any?): Unit = unsupported("add")
    fun remove(element: Any?): Unit = unsupported("remove")
    fun put(key: Any?, element: Any?): Unit = unsupported("put")
    fun removeKey(key: Any?): Unit = unsupported("removeKey")
    fun increment(amount: Long): Unit = unsupported("increment")
    fun decrement(amount: Long): Unit = unsupported("decrement")

    /**
     * Every operation defaults to a loud NAMED refusal rather than a no-op: a binding that
     * silently dropped an event it cannot carry would surface later as a value mismatch
     * blamed on the kernel, which is the one failure mode this epic exists to avoid.
     */
    private fun unsupported(operation: String): Nothing = error(
        "This script source does not implement '$operation'; a script event it cannot carry " +
            "is a wiring bug in the case, not an oracle finding.",
    )
}

/**
 * This [SetOps] as a [ScriptSource].
 *
 * A script's payloads are `Any?` by design (`civictech.oracle.model.ScriptEvent`'s KDoc: a
 * script says "add this value", not "add this `String`"), while a kernel `SetCell<E>` is
 * typed, so the widening happens here in one place. The cast is unchecked and cannot be
 * otherwise: only the case's author knows the element domain matches the cell. A wrong domain
 * surfaces as a `ClassCastException` from the injection, which is a loud caller error rather
 * than a mismatch blamed on the kernel.
 */
@Suppress("UNCHECKED_CAST")
fun <E> SetOps<E>.asScriptSource(): ScriptSource = object : ScriptSource {
    override fun add(element: Any?) = this@asScriptSource.add(element as E)
    override fun remove(element: Any?) = this@asScriptSource.remove(element as E)
}
