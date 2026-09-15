package civictech.demo.skillmatch

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
import civictech.oracle.run.ScalarTerminalFold
import civictech.oracle.run.ScriptSource
import civictech.oracle.run.SetTerminalFold
import civictech.oracle.run.TerminalFold
import civictech.query.ref.BatchEvaluator
import civictech.query.ref.RelationValue
import civictech.query.run.CompiledQuery
import civictech.query.run.OutputShape
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import civictech.testkit.forEachSeed
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The EXTENSIONAL half of BS-11 / `[QRY1-ORA-10]` for skillmatch (feature computenet-cab.7,
 * task .6; decision cab.7-D10): the hand-wired [SkillPipeline] and [SkillMatchQuery]'s compiled
 * form each agree with a [BatchEvaluator] reference over the compiled plan on every seed of
 * `0 until 50`, driven through `:oracle`'s [DifferentialRunner] bring-your-own path.
 *
 * The hand-wired case compares all ten of its outputs. Eight are relational and come straight
 * from the query through the Row codec below; `qualification` and `market` are the BOUNDARY
 * functions the language cannot express (residual R1 of doc/demo-findings.md F-20), computed
 * from the decoded `matchCounts`/`required`/`supply`/`demand` exactly as the demo's own
 * `LookupJoinCell`/`CombineLatestCell` combines do. The compiled `candHas` projection (residual
 * R2) has no hand-wired twin and is compared only in the compiled case.
 *
 * This is `civictech.query.run.QueryCase`'s adapter, hand-mirrored (cab.7-D9): that class lives
 * in `:query`'s test source set, which `:query`'s `ModuleDependencyTest` keeps unreachable from
 * a demo module. Shape follows `TieringQueryAgreementTest`.
 */
class SkillMatchQueryAgreementTest {

    private val compiled: CompiledQuery = SkillMatchQuery.compile()

    private val candidates = listOf("ann", "bob", "cy")
    private val jobs = listOf("dev", "ops")
    private val skills = listOf("kotlin", "sql", "k8s", "go")

    private val seeds = 0L until 50L

    // ------------------------------------------------------------------ codec
    //
    // Column orders are the query's heads (SkillMatchQuery KDoc): matches(C, S, J),
    // matchCounts grouped by (C, J), required by (J), supply/demand by (S), gap(J, S).

    private fun encode(element: Any?): Row = when (element) {
        is CandidateSkill -> Row(listOf(element.candidate, element.skill))
        is JobSkill -> Row(listOf(element.job, element.skill))
        else -> error("no Row encoding for $element")
    }

    private fun decodeRows(value: RelationValue, decode: (List<Any?>) -> Any): Set<Any?> =
        (value as RelationValue.Rows).rows.mapTo(LinkedHashSet()) { decode(it.values) }

    private fun decodeGroups(value: RelationValue, decode: (List<Any?>) -> Any): Map<Any?, Long> =
        (value as RelationValue.Groups).entries.entries.associate { (key, v) -> decode((key as Row).values) to v as Long }

    // ------------------------------------------------------------------ churn / scripts
    //
    // SkillMatchPipelineTest's churn (3 candidates, 2 jobs, 4 skills, 80 steps; a held element is
    // removed with probability 0.4, otherwise re-added), recorded as data so the hand-wired and
    // compiled scripts carry the identical logical event sequence. A remove is emitted only for a
    // held element, so Membership.live of each slice equals the held set.

    private data class Churn(val relation: String, val element: Any, val add: Boolean)

    private fun churn(seed: Long, steps: Int = 80): List<Churn> {
        val rnd = Random(seed)
        val heldCand = mutableSetOf<CandidateSkill>()
        val heldJob = mutableSetOf<JobSkill>()
        val out = mutableListOf<Churn>()
        repeat(steps) {
            if (rnd.nextInt(2) == 0) {
                val e = CandidateSkill(candidates[rnd.nextInt(candidates.size)], skills[rnd.nextInt(skills.size)])
                if (e in heldCand && rnd.nextInt(10) < 4) {
                    out += Churn(CAND, e, add = false); heldCand -= e
                } else {
                    out += Churn(CAND, e, add = true); heldCand += e
                }
            } else {
                val e = JobSkill(jobs[rnd.nextInt(jobs.size)], skills[rnd.nextInt(skills.size)])
                if (e in heldJob && rnd.nextInt(10) < 4) {
                    out += Churn(JOB, e, add = false); heldJob -= e
                } else {
                    out += Churn(JOB, e, add = true); heldJob += e
                }
            }
        }
        return out
    }

    /** [events] as a [Script], one slice per EDB relation; [asRows] encodes elements through the codec. */
    private fun script(events: List<Churn>, asRows: Boolean): Script = Script(
        listOf(CAND, JOB).map { relation ->
            val writer = WriterId(relation)
            SourceScript(
                SourceId(relation),
                events.filter { it.relation == relation }.map {
                    val element: Any = if (asRows) encode(it.element) else it.element
                    if (it.add) ScriptEvent.Add(writer, element) else ScriptEvent.Remove(writer, element)
                },
            )
        },
    )

    // ------------------------------------------------------------------ reference

    /** `relation ↦ codec.encode(live set)`, whatever the script's element encoding. */
    private fun db(script: Script): Map<String, Set<Row>> = listOf(CAND, JOB).associateWith { relation ->
        Membership.live(script.slice(SourceId(relation))).mapTo(LinkedHashSet()) { it as? Row ?: encode(it) }
    }

    /**
     * The hand-wired reference: [BatchEvaluator] over the compiled plan, decoded to demo terms,
     * plus the two boundary functions. [wrongGap] is the negative control's only knob: `gap` as
     * the jobs' skills some candidate DOES hold.
     */
    @Suppress("UNCHECKED_CAST")
    private fun handWiredReference(wrongGap: Boolean = false): Reference = Reference { script ->
        val db = db(script)
        val evaluated = BatchEvaluator.evaluate(compiled.plan, db)
        val candSkills = Membership.live(script.slice(SourceId(CAND)))
        val jobSkills = Membership.live(script.slice(SourceId(JOB)))

        val matches = decodeRows(evaluated.getValue("matches")) { (c, s, j) -> Match(c as String, j as String, s as String) }
        val matchCounts = decodeGroups(evaluated.getValue("matchCounts")) { (c, j) -> CandidateJob(c as String, j as String) }
        val required = decodeGroups(evaluated.getValue("required")) { (j) -> j as String }
        val supply = decodeGroups(evaluated.getValue("supply")) { (s) -> s as String }
        val demand = decodeGroups(evaluated.getValue("demand")) { (s) -> s as String }
        val gap = if (wrongGap) {
            val held = candSkills.mapTo(HashSet()) { (it as CandidateSkill).skill }
            jobSkills.filterTo(LinkedHashSet()) { (it as JobSkill).skill in held }
        } else {
            decodeRows(evaluated.getValue("gap")) { (j, s) -> JobSkill(j as String, s as String) }
        }

        // LookupJoinCell is a left-outer FK join: every matchCounts key appears (SkillPipeline.build).
        val qualification = matchCounts.mapValues { (cj, matched) ->
            val nd = required[(cj as CandidateJob).job] ?: 0L
            QualEntry(matched, nd, matched == nd && nd > 0L)
        }
        // CombineLatestCell is an outer per-key combine over supply ∪ demand (SkillPipeline.build).
        val market = (supply.keys + demand.keys).associateWith { skill ->
            val sv = supply[skill] ?: 0L
            val dv = demand[skill] ?: 0L
            MarketEntry(sv, dv, dv > sv)
        }

        mapOf(
            CAND to ModelState.SetState(candSkills),
            JOB to ModelState.SetState(jobSkills),
            "matches" to ModelState.SetState(matches),
            "matchCounts" to ModelState.MapState(matchCounts as Map<Any?, Any?>),
            "required" to ModelState.MapState(required as Map<Any?, Any?>),
            "qualification" to ModelState.MapState(qualification as Map<Any?, Any?>),
            "gap" to ModelState.SetState(gap),
            "supply" to ModelState.MapState(supply as Map<Any?, Any?>),
            "demand" to ModelState.MapState(demand as Map<Any?, Any?>),
            "market" to ModelState.MapState(market as Map<Any?, Any?>),
        )
    }

    /** The compiled reference: [BatchEvaluator] over the compiled plan, Row-keyed, every root. */
    private fun compiledReference(): Reference = Reference { script ->
        BatchEvaluator.evaluate(compiled.plan, db(script)).mapValues { (_, value) ->
            when (value) {
                is RelationValue.Rows -> ModelState.SetState(value.rows)
                is RelationValue.Groups -> ModelState.MapState(value.entries)
                is RelationValue.Count -> ModelState.ScalarState(value.count)
            }
        }
    }

    // ------------------------------------------------------------------ case (i): hand-wired

    private fun buildHandWired(world: SimWorld): CaseGraph {
        val host = world.host
        val refs = SkillPipeline.build(host)
        val mgmt = host.managementInlet.call

        fun fold(from: TypedRef<*>, fold: TerminalFold): TerminalFold {
            mgmt.spawn(fold)
            val result = mgmt.connect(from.ref, "outlet", fold.ref, "inlet")
            check(result !is LinkResult.Rejected) {
                "linking ${from.ref} to its fold was rejected: ${(result as LinkResult.Rejected).reason}"
            }
            return fold
        }

        val terminals = mapOf(
            CAND to fold(refs.candSkills, SetTerminalFold<CandidateSkill>()),
            JOB to fold(refs.jobSkills, SetTerminalFold<JobSkill>()),
            "matches" to fold(refs.matches, SetTerminalFold<Match>()),
            "matchCounts" to fold(refs.matchCounts, MapTerminalFold<CandidateJob, Long>()),
            "required" to fold(refs.required, MapTerminalFold<String, Long>()),
            "qualification" to fold(refs.qualification, MapTerminalFold<CandidateJob, QualEntry>()),
            "gap" to fold(refs.gap, SetTerminalFold<JobSkill>()),
            "supply" to fold(refs.supply, MapTerminalFold<String, Long>()),
            "demand" to fold(refs.demand, MapTerminalFold<String, Long>()),
            "market" to fold(refs.market, MapTerminalFold<String, MarketEntry>()),
        )

        val candOps = host.lookup(refs.candSkills)!!.inlet.call
        val jobOps = host.lookup(refs.jobSkills)!!.inlet.call
        val sources = mapOf(
            SourceId(CAND) to object : ScriptSource {
                override fun add(element: Any?) = candOps.add(element as CandidateSkill)
                override fun remove(element: Any?) = candOps.remove(element as CandidateSkill)
            },
            SourceId(JOB) to object : ScriptSource {
                override fun add(element: Any?) = jobOps.add(element as JobSkill)
                override fun remove(element: Any?) = jobOps.remove(element as JobSkill)
            },
        )
        return CaseGraph(terminals = terminals, sources = sources)
    }

    // ------------------------------------------------------------------ case (ii): compiled

    /** QueryCase.buildGraph's rule: one fold per root, chosen by its [OutputShape]. */
    private fun buildCompiled(world: SimWorld): CaseGraph {
        val host = world.host
        val applied = compiled.applyTo(host.managementInlet)
        val mgmt = host.managementInlet.call

        val terminals = compiled.outputShapes.entries.associate { (root, shape) ->
            val fold: TerminalFold = when (shape) {
                OutputShape.SET_OF_ROWS -> SetTerminalFold<Row>()
                OutputShape.MAP_BY_GROUP -> MapTerminalFold<Any?, Any?>()
                OutputShape.COUNTER -> ScalarTerminalFold()
            }
            val outputRef = applied.outputs[root]?.ref
                ?: error("Root '$root' has an OutputShape but no applied output; outputs=${applied.outputs.keys.sorted()}")
            mgmt.spawn(fold)
            val result = mgmt.connect(outputRef, "outlet", fold.ref, "inlet")
            check(result !is LinkResult.Rejected) {
                "linking root '$root' to its $shape fold was rejected: ${(result as LinkResult.Rejected).reason}"
            }
            root to fold
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

    private fun marker(case: String): String = buildString {
        appendLine("-- skillmatch $case case")
        appendLine("-- query")
        appendLine(SkillMatchQuery.query.toString())
        appendLine("-- plan")
        appendLine(compiled.plan.toString())
        appendLine("-- spec")
        append(CaseExecution.renderSpec(compiled.spec))
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `the hand-wired pipeline agrees with the boundary reference on every seed`() {
        val marker = marker("hand-wired")
        forEachSeed(seeds) { seed ->
            val outcome = DifferentialRunner.check(
                seed, marker, script(churn(seed), asRows = false), handWiredReference(), buildGraph = ::buildHandWired,
            )
            assertEquals(RunOutcome.Success, outcome) { "seed=$seed hand-wired: $outcome" }
        }
    }

    @Test
    fun `the compiled query agrees with BatchEvaluator on every seed over the same events as Rows`() {
        val marker = marker("compiled")
        forEachSeed(seeds) { seed ->
            val outcome = DifferentialRunner.check(
                seed, marker, script(churn(seed), asRows = true), compiledReference(), buildGraph = ::buildCompiled,
            )
            assertEquals(RunOutcome.Success, outcome) { "seed=$seed compiled: $outcome" }
        }
    }

    /** Negative control (cab.7-D10): `gap` as the HELD skills must diverge, on terminal `gap`. */
    @Test
    fun `a reference whose gap is the held skills mismatches on gap`() {
        val marker = marker("hand-wired, wrong gap")
        val mismatches = seeds.mapNotNull { seed ->
            val outcome = DifferentialRunner.check(
                seed, marker, script(churn(seed), asRows = false), handWiredReference(wrongGap = true),
                buildGraph = ::buildHandWired,
            )
            (outcome as? RunOutcome.Mismatch)?.takeIf { it.terminal == "gap" }
        }
        assertTrue(mismatches.isNotEmpty()) { "expected a Mismatch on terminal 'gap' for at least one seed" }
    }

    /** Non-vacuity (cab.7-D10): real adds and removes, and a seed with non-empty matches and gap. */
    @Test
    fun `the churn adds and removes and some seed reaches non-empty matches and gap`() {
        var adds = 0
        var removes = 0
        var nonEmpty = false
        for (seed in seeds) {
            val script = script(churn(seed), asRows = false)
            script.slices.flatMap { it.events }.forEach {
                when (it) {
                    is ScriptEvent.Add -> adds++
                    is ScriptEvent.Remove -> removes++
                    else -> Unit
                }
            }
            val state = handWiredReference().evaluate(script)
            val matches = (state.getValue("matches") as ModelState.SetState).elements
            val gap = (state.getValue("gap") as ModelState.SetState).elements
            if (matches.isNotEmpty() && gap.isNotEmpty()) nonEmpty = true
        }
        assertTrue(adds > 0 && removes > 0) { "churn was not both add and remove: adds=$adds removes=$removes" }
        assertTrue(nonEmpty) { "no seed reached a non-empty matches and gap in the reference" }
    }

    private companion object {
        const val CAND = "candSkills"
        const val JOB = "jobSkills"
    }
}
