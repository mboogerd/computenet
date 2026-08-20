package civictech.oracle.shrink

import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.RemoveRecord
import civictech.oracle.gen.TerminalSpec
import civictech.oracle.gen.TopologyNode
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import civictech.oracle.run.RunOutcome

/**
 * The rendering [Counterexample.renderKotlin] delegates to — `[ORA1-SHRINK-04]`.
 *
 * ## Render from [CaseTopology] + [CaseScript], never [GeneratedCase.spec]
 *
 * A [GeneratedCase.spec] is a `civictech.cell.graph.GraphSpec`, and its `SpawnStep`s carry
 * `CellFactory` lambdas — opaque at runtime, not printable as source. [CaseTopology] names
 * every node by its `civictech.oracle.bind.OperatorCatalog` id instead, which a lookup turns
 * back into a factory, so this file walks [CaseTopology] and [CaseScript] only and re-lowers
 * with `civictech.oracle.gen.GraphGenerator.lower` — the identical call every [Shrinker]
 * candidate re-lowers through, so a pasted-and-run snippet builds the same spec the shrink
 * itself last executed.
 *
 * ## No runtime state
 *
 * Every value emitted below — the seed, every [TopologyNode]/[TerminalSpec] field, every
 * [CaseStep], every [RemoveRecord] — is a literal already held by the counterexample's
 * [GeneratedCase]. Nothing is captured from the failing sweep: no live
 * `civictech.testkit.SimWorld`, no reference closure, no catalog snapshot. A caller-substituted
 * `civictech.oracle.run.Reference` (the seam a test uses to manufacture a failure without
 * touching the kernel) is exactly such runtime state — it is arbitrary Kotlin the shrink was
 * merely handed, not data [GeneratedCase] carries — so the rendered replay always asserts
 * against the catalog-resolved reference (`DifferentialRunner.run(case, ...)`, no `reference`
 * argument), which is also the shape a *real* kernel-vs-model disagreement takes.
 *
 * ## `removeAudit` is rendered as literals, never re-derived (computenet-p5qy defect 1)
 *
 * An earlier version of this renderer emitted
 * `removeAudit = civictech.oracle.shrink.Shrinker.auditFor(script)` — a call back into an
 * `internal` member of an `internal`-adjacent object. That compiles inside `:oracle`'s own test
 * source set, where the snippet's containment test lives, but fails everywhere else a module can
 * consume `:oracle` (measured: `:kernel:compileTestKotlin` refuses it with "it is internal in
 * 'civictech/oracle/shrink/Shrinker'" when the identical snippet is pasted into
 * `kernel/src/test/kotlin/civictech/cell/oracle/`). [GeneratedCase.removeAudit] is already the
 * exact data a re-derivation would recompute — [Shrinker] keeps it current through every
 * reduction pass (see `Shrinker.withScript`/`withElements`/`without`) — so the snippet renders
 * [Counterexample.case]'s own `removeAudit` as a literal [RemoveRecord] list instead of calling
 * anything. This was chosen over widening `Shrinker.auditFor` to `public`: it keeps `:oracle`'s
 * API surface unchanged, and a snippet with no call back into the shrinker at all is closer to
 * "pasteable standalone test" than one that depends on a shrinker-internal helper, even a public
 * one.
 *
 * ## The wave-prefix option is rendered for a [RunOutcome.WavePrefixViolation] (defect 2)
 *
 * A [RunOutcome.WavePrefixViolation] is only detectable while `civictech.oracle.run.WavePrefixOracle`
 * is actively checking, and whether it checks a given case is a `civictech.oracle.run.WavePrefixOption`
 * decision the caller makes per run — it is not carried by [GeneratedCase] or by the outcome
 * itself. Rendering the replay with no `wavePrefix` argument leaves it at
 * `civictech.oracle.run.WavePrefixOption.DEFAULT` (a 0.25 selection fraction), so whether the
 * violation reproduces depends on whether `DEFAULT.selects(case.seed)` happens to be `true` —
 * measured over `WavePrefixTest`'s four `SEAM_SEEDS`, exactly half do and half report a plain
 * `Mismatch` instead.
 *
 * The fix does not require knowing which [civictech.oracle.run.WavePrefixOption] the shrink was
 * actually given — `civictech.oracle.run.WavePrefixOption.selects` is a pure predicate over the
 * seed that decides only *whether* the check runs; the violation it finds once running is
 * unaffected by which fraction triggered it. `civictech.oracle.run.WavePrefixOption.ALWAYS`
 * (fraction `1.0`) selects every seed unconditionally, so re-running with `ALWAYS` reproduces
 * whatever violation any option that selected this case's seed found. So a
 * [RunOutcome.WavePrefixViolation] counterexample always renders
 * `DifferentialRunner.run(case, wavePrefix = civictech.oracle.run.WavePrefixOption.ALWAYS)`; every
 * other outcome renders the plain `DifferentialRunner.run(case)`, unchanged, so as not to force
 * prefix-checking onto a replay that never needed it.
 *
 * ## `check`, not a test-framework assertion
 *
 * The emitted replay uses the Kotlin standard library's `check(...)`, so the snippet depends on
 * nothing beyond Kotlin itself and the `civictech.oracle` types it names — no JUnit, no kotest.
 */
internal fun renderCounterexample(counterexample: Counterexample): String {
    val case = counterexample.case
    val kind = counterexample.outcome::class.simpleName
        ?: error("Counterexample.outcome's runtime class has no simpleName: ${counterexample.outcome}")
    check(counterexample.outcome != RunOutcome.Success) {
        "renderKotlin() was called on a Counterexample whose outcome is Success; Shrinker.run " +
            "never returns one, so there is nothing to render a replay assertion for."
    }
    val terminal = failureTerminal(counterexample.outcome)

    return buildString {
        appendLine("// Rendered by Counterexample.renderKotlin() (ORA1-SHRINK-04).")
        appendLine("// Rebuilt from CaseTopology + CaseScript via catalog ids; the lowered GraphSpec is")
        appendLine("// re-derived by GraphGenerator.lower, never printed — see RenderKotlin.kt's KDoc.")
        appendLine("// NOTE: the check() below asserts against the catalog-resolved reference")
        appendLine("// (DifferentialRunner.run(case, ...), no `reference` argument). If the counterexample")
        appendLine("// this was rendered from was found under a CALLER-SUBSTITUTED reference (e.g. a")
        appendLine("// test injecting a mutant model via DifferentialRunner.run's `reference` seam,")
        appendLine("// rather than an actual kernel bug), this replay will NOT reproduce that failure —")
        appendLine("// a substituted reference is runtime state, not case data, and cannot be rendered.")
        appendLine("val seed = ${case.seed}L")
        appendLine()
        appendLine("val topology = civictech.oracle.gen.CaseTopology(")
        appendLine("    nodes = listOf(")
        case.topology.nodes.forEach { node -> appendLine("        ${renderNode(node)},") }
        appendLine("    ),")
        appendLine("    terminals = listOf(")
        case.topology.terminals.forEach { terminalSpec -> appendLine("        ${renderTerminal(terminalSpec)},") }
        appendLine("    ),")
        appendLine("    placement = mapOf(")
        case.topology.placement.forEach { (handle, host) -> appendLine("        ${literal(handle)} to $host,") }
        appendLine("    ),")
        appendLine(")")
        appendLine()
        appendLine("val script = civictech.oracle.gen.CaseScript(")
        appendLine("    steps = listOf(")
        case.script.steps.forEach { step -> appendLine("        ${renderStep(step)},") }
        appendLine("    ),")
        appendLine(")")
        appendLine()
        appendLine("val case = civictech.oracle.gen.GeneratedCase(")
        appendLine("    seed = seed,")
        appendLine("    topology = topology,")
        appendLine("    spec = civictech.oracle.gen.GraphGenerator.lower(topology),")
        appendLine("    script = script,")
        appendLine("    removeAudit = listOf(")
        case.removeAudit.forEach { record -> appendLine("        ${renderRemoveRecord(record)},") }
        appendLine("    ),")
        appendLine(")")
        appendLine()
        val wavePrefixArg = if (counterexample.outcome is RunOutcome.WavePrefixViolation) {
            ", wavePrefix = civictech.oracle.run.WavePrefixOption.ALWAYS"
        } else {
            ""
        }
        appendLine("val outcome = civictech.oracle.run.DifferentialRunner.run(case$wavePrefixArg)")
        val terminalCheck = if (terminal == null) "" else " && outcome.terminal == ${literal(terminal)}"
        appendLine("check(outcome is civictech.oracle.run.RunOutcome.$kind$terminalCheck) {")
        val terminalDescription = if (terminal == null) "" else " on '$terminal'"
        appendLine("    \"expected $kind$terminalDescription, got \$outcome\"")
        append("}")
    }
}

/** The terminal [outcome] was reported on, or `null` for a variant that names none. */
private fun failureTerminal(outcome: RunOutcome): String? = when (outcome) {
    is RunOutcome.Mismatch -> outcome.terminal
    is RunOutcome.WavePrefixViolation -> outcome.terminal
    else -> null
}

private fun renderNode(node: TopologyNode): String {
    val source = node.source?.let { "civictech.oracle.model.SourceId(${literal(it.id)})" } ?: "null"
    val inputs = node.inputs.joinToString(", ") { literal(it) }
    return "civictech.oracle.gen.TopologyNode(" +
        "handle = ${literal(node.handle)}, " +
        "catalogId = ${literal(node.catalogId)}, " +
        "inputs = listOf($inputs), " +
        "source = $source)"
}

/**
 * A literal [RemoveRecord] — `stepIndex` and `observed` are an `Int` and a `Boolean`, so both
 * render directly. Rendered rather than re-derived through `Shrinker.auditFor(script)`: see this
 * file's "removeAudit is rendered as literals" KDoc.
 */
private fun renderRemoveRecord(record: RemoveRecord): String =
    "civictech.oracle.gen.RemoveRecord(stepIndex = ${record.stepIndex}, observed = ${record.observed})"

private fun renderTerminal(terminal: TerminalSpec): String =
    "civictech.oracle.gen.TerminalSpec(" +
        "name = ${literal(terminal.name)}, " +
        "handle = ${literal(terminal.handle)}, " +
        "late = ${terminal.late})"

private fun renderStep(step: CaseStep): String = when (step) {
    is CaseStep.Op -> "civictech.oracle.gen.CaseStep.Op(${renderSourceId(step.source)}, ${renderEvent(step.event)})"
    CaseStep.Barrier -> "civictech.oracle.gen.CaseStep.Barrier"
}

private fun renderSourceId(id: SourceId): String = "civictech.oracle.model.SourceId(${literal(id.id)})"

private fun renderWriterId(id: WriterId): String = "civictech.oracle.model.WriterId(${literal(id.id)})"

private fun renderEvent(event: ScriptEvent): String = when (event) {
    is ScriptEvent.Add ->
        "civictech.oracle.model.ScriptEvent.Add(${renderWriterId(event.writer)}, ${literal(event.element)})"

    is ScriptEvent.Remove ->
        "civictech.oracle.model.ScriptEvent.Remove(${renderWriterId(event.writer)}, ${literal(event.element)})"

    is ScriptEvent.Observe ->
        "civictech.oracle.model.ScriptEvent.Observe(${renderWriterId(event.writer)})"

    is ScriptEvent.Put ->
        "civictech.oracle.model.ScriptEvent.Put(${renderWriterId(event.writer)}, ${literal(event.key)}, " +
            "${literal(event.element)})"

    is ScriptEvent.RemoveKey ->
        "civictech.oracle.model.ScriptEvent.RemoveKey(${renderWriterId(event.writer)}, ${literal(event.key)})"

    is ScriptEvent.Increment ->
        "civictech.oracle.model.ScriptEvent.Increment(${renderWriterId(event.writer)}, ${event.amount}L)"

    is ScriptEvent.Decrement ->
        "civictech.oracle.model.ScriptEvent.Decrement(${renderWriterId(event.writer)}, ${event.amount}L)"
}

/**
 * A Kotlin literal for [value]. [civictech.oracle.gen.ElementDomains] only ever produces
 * `String` elements/keys and `Long` amounts, so those two plus `Int`/`Boolean`/`null` — the
 * shapes a hand-built [GeneratedCase] might reasonably use too — cover every payload this
 * renderer is expected to see. An unrecognized payload type fails loudly, naming the type,
 * rather than silently emitting something that does not compile.
 */
internal fun literal(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    is Long -> "${value}L"
    is Int -> value.toString()
    is Boolean -> value.toString()
    else -> error(
        "renderKotlin cannot render a literal of type ${value::class}: $value — extend " +
            "RenderKotlin.kt's literal() when a new element/key domain type is introduced.",
    )
}
