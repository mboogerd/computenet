package civictech.demo.backlogtriage

import civictech.cell.CellRef
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
 * `[QRY1-ORA-10]` for backlog-triage (feature computenet-cab.7, task .9; decisions
 * cab.7-D10/D12): the hand-wired [TriagePipeline] and [TriageQuery]'s compiled form agree
 * extensionally with each other's reference on every seed of `0 until 50`, through `:oracle`'s
 * [DifferentialRunner]. Compared on the MEAN lane only — `score`, `votes` (the boundary
 * arithmetic `(wins − losses) / (wins + losses)` and `wins + losses`) — never `wilson`, any
 * `RatingCell` (`elo`, `bt`, `trueskill`, `glicko`, `wenglin`) or `meta`; see [TriageQuery]'s
 * KDoc for why those have no relational twin (or, for `wilson`, are simply out of scope here).
 *
 * This is `civictech.query.run.QueryCase`'s adapter, hand-mirrored (cab.7-D9): `:query`'s
 * `ModuleDependencyTest` forbids a `:demo:*` fingerprint on its own classpath even in test
 * scope, so that class — living in `:query`'s test source set — is unreachable from here, and
 * a demo query's compile-through-QueryCompiler / apply-and-fold / batch-reference machinery is
 * rewritten per demo rather than shared.
 */
class TriageQueryAgreementTest {

    private val writer = WriterId("w")
    private val agents = listOf("ada", "bo", "cy")
    private val features = listOf("f1", "f2", "f3", "f4", "f5")

    // ------------------------------------------------------------------ churn generation
    //
    // 3 agents, 5 features, 100 steps: add a Pref(agent, winner, loser) with winner != loser,
    // or — with probability 0.4 when the agent already holds one — remove a held Pref. Writes
    // go straight through the prefs cell's own inlet; TriageApp's reverse-vote retraction (an
    // agent's opposing vote is auto-retracted on a new one) is app logic layered on top of the
    // pipeline, not a pipeline invariant, so it is deliberately not emulated here (bead context).

    private sealed interface Churn {
        data class AddPref(val pref: Pref) : Churn
        data class RemovePref(val pref: Pref) : Churn
    }

    private fun churn(seed: Long, steps: Int = 100): List<Churn> {
        val rnd = Random(seed)
        val heldByAgent = mutableMapOf<String, MutableSet<Pref>>()
        val out = mutableListOf<Churn>()
        repeat(steps) {
            val agent = agents[rnd.nextInt(agents.size)]
            val held = heldByAgent.getOrPut(agent) { mutableSetOf() }
            if (held.isNotEmpty() && rnd.nextInt(10) < 4) {
                val p = held.elementAt(rnd.nextInt(held.size))
                out += Churn.RemovePref(p)
                held -= p
            } else {
                val winner = features[rnd.nextInt(features.size)]
                val loser = features[rnd.nextInt(features.size)]
                if (winner != loser) {
                    val p = Pref(agent, winner, loser)
                    out += Churn.AddPref(p)
                    held += p
                }
            }
        }
        return out
    }

    /** [events] as a hand-wired [Script]: `prefs` carries [Pref]s directly. */
    private fun handWiredScript(events: List<Churn>): Script {
        val prefEvents = events.map { c ->
            when (c) {
                is Churn.AddPref -> ScriptEvent.Add(writer, c.pref)
                is Churn.RemovePref -> ScriptEvent.Remove(writer, c.pref)
            }
        }
        return Script(listOf(SourceScript(SourceId("prefs"), prefEvents)))
    }

    /** The same [events], encoded as [Row]s for the compiled query's EDB source. */
    private fun compiledScript(events: List<Churn>): Script {
        val prefEvents = events.map { c ->
            when (c) {
                is Churn.AddPref ->
                    ScriptEvent.Add(writer, Row(listOf(c.pref.agent, c.pref.winner, c.pref.loser)))

                is Churn.RemovePref ->
                    ScriptEvent.Remove(writer, Row(listOf(c.pref.agent, c.pref.winner, c.pref.loser)))
            }
        }
        return Script(listOf(SourceScript(SourceId("prefs"), prefEvents)))
    }

    // ------------------------------------------------------------------ case (i): hand-wired

    private fun buildHandWired(world: SimWorld): CaseGraph {
        val host = world.host
        val refs = TriagePipeline.build(host)
        val mgmt = host.managementInlet.call

        val scoreFold = MapTerminalFold<String, Double>()
        val votesFold = MapTerminalFold<String, Long>()
        listOf<TerminalFold>(scoreFold, votesFold).forEach { mgmt.spawn(it) }

        fun connect(from: CellRef, fold: TerminalFold) {
            val result = mgmt.connect(from, "outlet", fold.ref, "inlet")
            check(result !is LinkResult.Rejected) {
                "linking $from to its fold was rejected: ${(result as LinkResult.Rejected).reason}"
            }
        }
        connect(refs.score, scoreFold)
        connect(refs.votes, votesFold)

        val prefOps = host.lookup(refs.prefs)!!.inlet.call
        val sources = mapOf(
            SourceId("prefs") to object : ScriptSource {
                override fun add(element: Any?) = prefOps.add(element as Pref)
                override fun remove(element: Any?) = prefOps.remove(element as Pref)
            },
        )
        return CaseGraph(
            terminals = mapOf("score" to scoreFold, "votes" to votesFold),
            sources = sources,
        )
    }

    /** [Row]-keyed `RelationValue.Groups` decoded to the single group column's own value. */
    private fun decodeGroups(value: RelationValue): Map<String, Any?> {
        val groups = (value as RelationValue.Groups).entries
        return groups.entries.associate { (key, v) -> (key as Row).values.single() as String to v }
    }

    /**
     * The mean-lane boundary reference (cab.7-D12): `score = (wins − losses) / (wins + losses)`,
     * `votes = wins + losses`, over the union of both counters' keys (absent = 0).
     * [flipVotesToDifference] is the negative control's only knob.
     */
    @Suppress("UNCHECKED_CAST")
    private fun handWiredReference(flipVotesToDifference: Boolean = false): Reference = Reference { script ->
        val db = mapOf(
            "prefs" to Membership.live(script.slice(SourceId("prefs")))
                .mapTo(LinkedHashSet()) { (it as Pref).let { p -> Row(listOf(p.agent, p.winner, p.loser)) } },
        )
        val evaluated = BatchEvaluator.evaluate(TriageQuery.compiled.plan, db)
        val wins = decodeGroups(evaluated.getValue("wins")).mapValues { it.value as Long }
        val losses = decodeGroups(evaluated.getValue("losses")).mapValues { it.value as Long }
        val items = wins.keys + losses.keys
        val score = items.associateWith { item ->
            val w = wins[item] ?: 0L
            val l = losses[item] ?: 0L
            (w - l).toDouble() / (w + l)
        }
        val votes = items.associateWith { item ->
            val w = wins[item] ?: 0L
            val l = losses[item] ?: 0L
            if (flipVotesToDifference) (w - l) else (w + l)
        }
        mapOf(
            "score" to ModelState.MapState(score as Map<Any?, Any?>),
            "votes" to ModelState.MapState(votes as Map<Any?, Any?>),
        )
    }

    // ------------------------------------------------------------------ case (ii): compiled

    private fun buildCompiled(world: SimWorld): CaseGraph {
        val host = world.host
        val applied = TriageQuery.compiled.applyTo(host.managementInlet)
        val mgmt = host.managementInlet.call

        val terminals = TriageQuery.compiled.outputShapes.keys.associateWith { root ->
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

    /** The un-boundaried reference: [BatchEvaluator] over the compiled plan, Row-keyed. */
    private fun compiledReference(): Reference = Reference { script ->
        val db = TriageQuery.compiled.sourceHandles.keys.associateWith { relation ->
            Membership.live(script.slice(SourceId(relation))).mapTo(LinkedHashSet()) { it as Row }
        }
        BatchEvaluator.evaluate(TriageQuery.compiled.plan, db).mapValues { (_, value) ->
            when (value) {
                is RelationValue.Rows -> ModelState.SetState(value.rows)
                is RelationValue.Groups -> ModelState.MapState(value.entries)
                is RelationValue.Count -> ModelState.ScalarState(value.count)
            }
        }
    }

    private fun marker(): String = buildString {
        appendLine("-- backlog-triage query source")
        appendLine(TriageQuery.SOURCE.trimEnd())
        appendLine("-- plan")
        appendLine(TriageQuery.compiled.plan.toString())
        appendLine("-- spec")
        append(CaseExecution.renderSpec(TriageQuery.compiled.spec))
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

    /** Negative control (cab.7-D10): a reference with `votes` computed as `wins − losses` must diverge. */
    @Test
    fun `a wins-minus-losses votes reference mismatches on at least one seed`() {
        val mismatches = (0L until 50L).mapNotNull { seed ->
            val script = handWiredScript(churn(seed))
            val outcome = DifferentialRunner.check(seed, marker(), script, handWiredReference(flipVotesToDifference = true), buildGraph = ::buildHandWired)
            (outcome as? RunOutcome.Mismatch)?.takeIf { it.terminal == "votes" }
        }
        assertTrue(mismatches.isNotEmpty()) { "expected at least one seed to mismatch on 'votes' with a wins-minus-losses reference" }
    }

    /**
     * Non-vacuity (cab.7-D10): across the seed range, some seed's reference reaches an item
     * carrying both a win and a loss, and the churn achieves both adds and removes.
     */
    @Test
    fun `some seed reaches an item with both a win and a loss, with real churn on both directions`() {
        var bothSeen = false
        var adds = 0
        var removes = 0
        for (seed in 0L until 50L) {
            val script = handWiredScript(churn(seed))
            val state = handWiredReference().evaluate(script)
            val db = mapOf(
                "prefs" to Membership.live(script.slice(SourceId("prefs")))
                    .mapTo(LinkedHashSet()) { (it as Pref).let { p -> Row(listOf(p.agent, p.winner, p.loser)) } },
            )
            val evaluated = BatchEvaluator.evaluate(TriageQuery.compiled.plan, db)
            val wins = decodeGroups(evaluated.getValue("wins")).keys
            val losses = decodeGroups(evaluated.getValue("losses")).keys
            if ((wins intersect losses).isNotEmpty()) bothSeen = true
            check(state.getValue("score") is ModelState.MapState) { "unexpected score shape" }
            script.slice(SourceId("prefs")).events.forEach {
                when (it) {
                    is ScriptEvent.Add -> adds++
                    is ScriptEvent.Remove -> removes++
                    else -> Unit
                }
            }
        }
        assertTrue(bothSeen) { "expected at least one seed to reach an item present in both wins and losses" }
        assertTrue(adds > 0 && removes > 0) { "prefs churn was not both add and remove: adds=$adds removes=$removes" }
    }
}
