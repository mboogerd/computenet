package civictech.demo.tiering

import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.link.LinkResult
import civictech.oracle.model.Membership
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.CaseExecution
import civictech.oracle.run.CaseGraph
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.MapTerminalFold
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.ScriptSource
import civictech.oracle.run.TerminalFold
import civictech.query.ref.BatchEvaluator
import civictech.query.ref.RelationValue
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `[QRY1-ORA-10]` for tiering (feature computenet-cab.7, task .8; decisions cab.7-D10/D11):
 * the hand-wired [TierPipeline] and [TieringQuery]'s compiled form agree extensionally with
 * each other's reference on every seed of `0 until 50`, through `:oracle`'s
 * [DifferentialRunner]. Compared at the PRE-FUSION boundary — `tierAvg`, `prefAvg` (the
 * boundary arithmetic `(wins − losses) / (wins + losses)`) and `fused` — never `board`,
 * `manual`, `manualEffective`, `items` or `contribs`; see [TieringQuery]'s KDoc for why those
 * have no relational twin.
 *
 * This is `civictech.query.run.QueryCase`'s adapter, hand-mirrored (cab.7-D9): `:query`'s
 * `ModuleDependencyTest` forbids a `:demo:*` fingerprint on its own classpath even in test
 * scope, so that class — living in `:query`'s test source set — is unreachable from here, and
 * a demo query's compile-through-QueryCompiler / apply-and-fold / batch-reference machinery is
 * rewritten per demo rather than shared.
 */
class TieringQueryAgreementTest {

    private val writer = WriterId("w")
    private val agents = listOf("ada", "bo", "cy")
    private val items = listOf("pizza", "sushi", "taco", "ramen")

    // ------------------------------------------------------------------ churn generation
    //
    // TieringPipelineTest's own churn (100 steps, 3 agents, 4 items, valuation put/re-put/
    // retract and preference add/remove), recorded as data so it can be replayed twice: once
    // encoded as [Valuation]/[Pref] for the hand-wired case, once as [Row] for the compiled
    // one. The two scripts below are therefore driven by the identical logical event sequence.

    private sealed interface Churn {
        data class SetVal(val valuation: Valuation) : Churn
        data class RetractVal(val agent: String, val item: String) : Churn
        data class AddPref(val pref: Pref) : Churn
        data class RemovePref(val pref: Pref) : Churn
    }

    private fun churn(seed: Long, steps: Int = 100): List<Churn> {
        val rnd = Random(seed)
        val heldVals = mutableMapOf<Pair<String, String>, Valuation>()
        val heldPrefs = mutableSetOf<Pref>()
        val out = mutableListOf<Churn>()
        repeat(steps) {
            val agent = agents[rnd.nextInt(agents.size)]
            if (rnd.nextInt(2) == 0) {
                val item = items[rnd.nextInt(items.size)]
                val key = agent to item
                val old = heldVals[key]
                if (old != null && rnd.nextInt(10) < 3) {
                    out += Churn.RetractVal(agent, item)
                    heldVals.remove(key)
                } else {
                    val v = Valuation(agent, item, rnd.nextInt(7).toLong())
                    out += Churn.SetVal(v)
                    heldVals[key] = v
                }
            } else {
                val winner = items[rnd.nextInt(items.size)]
                val loser = items[rnd.nextInt(items.size)]
                if (winner != loser) {
                    val p = Pref(agent, winner, loser)
                    if (p in heldPrefs && rnd.nextInt(10) < 4) {
                        out += Churn.RemovePref(p)
                        heldPrefs -= p
                    } else {
                        out += Churn.AddPref(p)
                        heldPrefs += p
                    }
                }
            }
        }
        return out
    }

    /**
     * [events] as a hand-wired [Script]: `vals` carries [Valuation]s, `prefs` carries [Pref]s.
     * A re-put of a held `(agent, item)` — KeyedSetCell semantics — is `Remove(old)` then
     * `Add(new)`, so [Membership.live] of the `vals` slice is exactly the cell's live set.
     */
    private fun handWiredScript(events: List<Churn>): Script {
        val held = mutableMapOf<Pair<String, String>, Valuation>()
        val valEvents = mutableListOf<ScriptEvent>()
        val prefEvents = mutableListOf<ScriptEvent>()
        events.forEach { c ->
            when (c) {
                is Churn.SetVal -> {
                    val key = c.valuation.agent to c.valuation.item
                    held[key]?.let { old -> valEvents += ScriptEvent.Remove(writer, old) }
                    valEvents += ScriptEvent.Add(writer, c.valuation)
                    held[key] = c.valuation
                }

                is Churn.RetractVal -> {
                    held.remove(c.agent to c.item)?.let { old -> valEvents += ScriptEvent.Remove(writer, old) }
                }

                is Churn.AddPref -> prefEvents += ScriptEvent.Add(writer, c.pref)
                is Churn.RemovePref -> prefEvents += ScriptEvent.Remove(writer, c.pref)
            }
        }
        return Script(listOf(SourceScript(SourceId("vals"), valEvents), SourceScript(SourceId("prefs"), prefEvents)))
    }

    /** The same [events], encoded as [Row]s for the compiled query's EDB sources. */
    private fun compiledScript(events: List<Churn>): Script {
        val held = mutableMapOf<Pair<String, String>, Row>()
        val valEvents = mutableListOf<ScriptEvent>()
        val prefEvents = mutableListOf<ScriptEvent>()
        events.forEach { c ->
            when (c) {
                is Churn.SetVal -> {
                    val key = c.valuation.agent to c.valuation.item
                    val row = Row(listOf(c.valuation.agent, c.valuation.item, c.valuation.score))
                    held[key]?.let { old -> valEvents += ScriptEvent.Remove(writer, old) }
                    valEvents += ScriptEvent.Add(writer, row)
                    held[key] = row
                }

                is Churn.RetractVal -> {
                    held.remove(c.agent to c.item)?.let { old -> valEvents += ScriptEvent.Remove(writer, old) }
                }

                is Churn.AddPref ->
                    prefEvents += ScriptEvent.Add(writer, Row(listOf(c.pref.agent, c.pref.winner, c.pref.loser)))

                is Churn.RemovePref ->
                    prefEvents += ScriptEvent.Remove(writer, Row(listOf(c.pref.agent, c.pref.winner, c.pref.loser)))
            }
        }
        return Script(listOf(SourceScript(SourceId("vals"), valEvents), SourceScript(SourceId("prefs"), prefEvents)))
    }

    // ------------------------------------------------------------------ case (i): hand-wired

    private fun buildHandWired(world: SimWorld): CaseGraph {
        val host = world.host
        val refs = TierPipeline.build(host)
        val mgmt = host.managementInlet.call

        val tierAvgFold = MapTerminalFold<String, Double>()
        val prefAvgFold = MapTerminalFold<String, Double>()
        val fusedFold = MapTerminalFold<String, Tiered>()
        listOf<TerminalFold>(tierAvgFold, prefAvgFold, fusedFold).forEach { mgmt.spawn(it) }

        fun connect(from: TypedRef<*>, fold: TerminalFold) {
            val result = mgmt.connect(from.ref, "outlet", fold.ref, "inlet")
            check(result !is LinkResult.Rejected) {
                "linking ${from.ref} to its fold was rejected: ${(result as LinkResult.Rejected).reason}"
            }
        }
        connect(refs.tierAvg, tierAvgFold)
        connect(refs.prefAvg, prefAvgFold)
        connect(refs.fused, fusedFold)

        val valOps = host.lookup(refs.vals)!!.inlet.call
        val prefOps = host.lookup(refs.prefs)!!.inlet.call
        val sources = mapOf(
            SourceId("vals") to object : ScriptSource {
                override fun add(element: Any?) {
                    val v = element as Valuation
                    valOps.put(v.agent to v.item, v)
                }

                override fun remove(element: Any?) {
                    val v = element as Valuation
                    valOps.remove(v.agent to v.item)
                }
            },
            SourceId("prefs") to object : ScriptSource {
                override fun add(element: Any?) = prefOps.add(element as Pref)
                override fun remove(element: Any?) = prefOps.remove(element as Pref)
            },
        )
        return CaseGraph(
            terminals = mapOf("tierAvg" to tierAvgFold, "prefAvg" to prefAvgFold, "fused" to fusedFold),
            sources = sources,
        )
    }

    /** [Row]-keyed `RelationValue.Groups` decoded to the single group column's own value. */
    private fun decodeGroups(value: RelationValue): Map<String, Any?> {
        val groups = (value as RelationValue.Groups).entries
        return groups.entries.associate { (key, v) -> (key as Row).values.single() as String to v }
    }

    /**
     * The pre-fusion boundary reference (cab.7-D11): `tierAvg` straight from the query,
     * `prefAvg = (wins − losses) / (wins + losses)` over the union of both counters' keys
     * (absent = 0), `fused = Tiering.fuse(tierAvg, prefAvg)` over the union of THEIR keys.
     * [flipPrefAvgSign] is the negative control's only knob.
     */
    @Suppress("UNCHECKED_CAST")
    private fun handWiredReference(flipPrefAvgSign: Boolean = false): Reference = Reference { script ->
        val db = mapOf(
            "vals" to Membership.live(script.slice(SourceId("vals")))
                .mapTo(LinkedHashSet()) { (it as Valuation).let { v -> Row(listOf(v.agent, v.item, v.score)) } },
            "prefs" to Membership.live(script.slice(SourceId("prefs")))
                .mapTo(LinkedHashSet()) { (it as Pref).let { p -> Row(listOf(p.agent, p.winner, p.loser)) } },
        )
        val evaluated = BatchEvaluator.evaluate(TieringQuery.compiled.plan, db)
        val tierAvg = decodeGroups(evaluated.getValue("tierAvg")).mapValues { it.value as Double }
        val wins = decodeGroups(evaluated.getValue("wins")).mapValues { it.value as Long }
        val losses = decodeGroups(evaluated.getValue("losses")).mapValues { it.value as Long }
        val prefAvg = (wins.keys + losses.keys).associateWith { item ->
            val w = wins[item] ?: 0L
            val l = losses[item] ?: 0L
            val signed = if (flipPrefAvgSign) (l - w) else (w - l)
            signed.toDouble() / (w + l)
        }
        val fused = (tierAvg.keys + prefAvg.keys).associateWith { item ->
            Tiering.fuse(tierAvg[item], prefAvg[item])
                ?: error("fuse($item) got neither signal, but $item came from tierAvg/prefAvg's own keys")
        }
        mapOf(
            "tierAvg" to ModelState.MapState(tierAvg as Map<Any?, Any?>),
            "prefAvg" to ModelState.MapState(prefAvg as Map<Any?, Any?>),
            "fused" to ModelState.MapState(fused as Map<Any?, Any?>),
        )
    }

    // ------------------------------------------------------------------ case (ii): compiled

    private fun buildCompiled(world: SimWorld): CaseGraph {
        val host = world.host
        val applied = TieringQuery.compiled.applyTo(host.managementInlet)
        val mgmt = host.managementInlet.call

        val terminals = TieringQuery.compiled.outputShapes.keys.associateWith { root ->
            val fold = MapTerminalFold<Any?, Any?>()
            mgmt.spawn(fold)
            val outputRef = applied.outputs[root]?.ref
                ?: error("Root '$root' has an OutputShape but no applied output; outputs=${applied.outputs.keys.sorted()}")
            val result = mgmt.connect(outputRef, "outlet", fold.ref, "inlet")
            check(result !is LinkResult.Rejected) {
                "linking root '$root' was rejected: ${(result as LinkResult.Rejected).reason}"
            }
            fold as TerminalFold
        }

        val sources = applied.sources.entries.associate { (relation, ref) ->
            val ops = host.lookup(ref)!!.inlet.call
            SourceId(relation) to object : ScriptSource {
                override fun add(element: Any?) = ops.add(element as Row)
                override fun remove(element: Any?) = ops.remove(element as Row)
            }
        }
        return CaseGraph(terminals = terminals, sources = sources)
    }

    /** The un-boundaried reference: [BatchEvaluator] over the compiled plan, both sides Row-keyed. */
    private fun compiledReference(): Reference = Reference { script ->
        val db = TieringQuery.compiled.sourceHandles.keys.associateWith { relation ->
            Membership.live(script.slice(SourceId(relation))).mapTo(LinkedHashSet()) { it as Row }
        }
        BatchEvaluator.evaluate(TieringQuery.compiled.plan, db).mapValues { (_, value) ->
            when (value) {
                is RelationValue.Rows -> ModelState.SetState(value.rows)
                is RelationValue.Groups -> ModelState.MapState(value.entries)
                is RelationValue.Count -> ModelState.ScalarState(value.count)
            }
        }
    }

    private fun marker(): String = buildString {
        appendLine("-- tiering query source")
        appendLine(TieringQuery.SOURCE.trimEnd())
        appendLine("-- plan")
        appendLine(TieringQuery.compiled.plan.toString())
        appendLine("-- spec")
        append(CaseExecution.renderSpec(TieringQuery.compiled.spec))
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `the hand-wired pipeline agrees with the boundary reference on every seed`() {
        for (seed in 0L until 50L) {
            val script = handWiredScript(churn(seed))
            val outcome = DifferentialRunner.check(seed, marker(), script, handWiredReference(), buildGraph = ::buildHandWired)
            assertEquals(RunOutcome.Success, outcome) { "seed=$seed: $outcome" }
        }
    }

    @Test
    fun `the compiled query agrees with BatchEvaluator on every seed`() {
        for (seed in 0L until 50L) {
            val events = churn(seed)
            val script = compiledScript(events)
            val outcome = DifferentialRunner.check(seed, marker(), script, compiledReference(), buildGraph = ::buildCompiled)
            assertEquals(RunOutcome.Success, outcome) { "seed=$seed: $outcome" }
        }
    }

    /** Negative control (cab.7-D10): a reference with the boundary sign flipped must diverge. */
    @Test
    fun `a sign-flipped prefAvg reference mismatches on at least one seed`() {
        val mismatches = (0L until 50L).mapNotNull { seed ->
            val script = handWiredScript(churn(seed))
            val outcome = DifferentialRunner.check(seed, marker(), script, handWiredReference(flipPrefAvgSign = true), buildGraph = ::buildHandWired)
            (outcome as? RunOutcome.Mismatch)?.takeIf { it.terminal == "prefAvg" }
        }
        assertTrue(mismatches.isNotEmpty()) { "expected at least one seed to mismatch on 'prefAvg' with a sign-flipped reference" }
    }

    /**
     * The boundary arithmetic is shown equal to the demo's own sign-mean fold
     * (`TieringPipelineTest`'s batch computation), on one seed — not merely assumed.
     */
    @Test
    fun `the boundary prefAvg equals the demo's own sign-mean fold`() {
        val events = churn(seed = 3L)
        val script = handWiredScript(events)

        val heldPrefs = mutableSetOf<Pref>()
        events.forEach { c ->
            when (c) {
                is Churn.AddPref -> heldPrefs += c.pref
                is Churn.RemovePref -> heldPrefs -= c.pref
                else -> Unit
            }
        }
        val contribs = heldPrefs.flatMap {
            listOf(
                Contribution(it.winner, it.agent, it.loser, +1),
                Contribution(it.loser, it.agent, it.winner, -1),
            )
        }
        val demoSignMean = contribs.groupBy { it.item }.mapValues { (_, cs) -> cs.sumOf { it.sign }.toDouble() / cs.size }

        val referencePrefAvg = (handWiredReference().evaluate(script).getValue("prefAvg") as ModelState.MapState)
            .entries.mapKeys { it.key as String }.mapValues { it.value as Double }

        assertEquals(demoSignMean, referencePrefAvg)
    }

    /**
     * Non-vacuity (cab.7-D10): across the seed range, some seed's reference reaches an item
     * carrying both signals (so `Tiering.fuse`'s blended branch is exercised), and the churn
     * achieves both adds and removes on both `vals` and `prefs`.
     */
    @Test
    fun `some seed exercises both signals on one item, with real churn on both sources`() {
        var bothSignalsSeen = false
        var valAdds = 0
        var valRemoves = 0
        var prefAdds = 0
        var prefRemoves = 0
        for (seed in 0L until 50L) {
            val script = handWiredScript(churn(seed))
            val state = handWiredReference().evaluate(script)
            val tierAvg = (state.getValue("tierAvg") as ModelState.MapState).entries.keys
            val prefAvg = (state.getValue("prefAvg") as ModelState.MapState).entries.keys
            if ((tierAvg intersect prefAvg).isNotEmpty()) bothSignalsSeen = true
            script.slice(SourceId("vals")).events.forEach {
                when (it) {
                    is ScriptEvent.Add -> valAdds++
                    is ScriptEvent.Remove -> valRemoves++
                    else -> Unit
                }
            }
            script.slice(SourceId("prefs")).events.forEach {
                when (it) {
                    is ScriptEvent.Add -> prefAdds++
                    is ScriptEvent.Remove -> prefRemoves++
                    else -> Unit
                }
            }
        }
        assertTrue(bothSignalsSeen) { "expected at least one seed to reach an item present in both tierAvg and prefAvg" }
        assertTrue(valAdds > 0 && valRemoves > 0) { "vals churn was not both add and remove: adds=$valAdds removes=$valRemoves" }
        assertTrue(prefAdds > 0 && prefRemoves > 0) { "prefs churn was not both add and remove: adds=$prefAdds removes=$prefRemoves" }
    }
}
