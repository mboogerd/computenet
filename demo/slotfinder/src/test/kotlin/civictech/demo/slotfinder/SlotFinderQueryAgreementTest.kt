package civictech.demo.slotfinder

import civictech.cell.data.SetOps
import civictech.cell.graph.lookup
import civictech.cell.link.LinkResult
import civictech.oracle.model.Membership
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.CaseGraph
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.MapTerminalFold
import civictech.oracle.run.Reference
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.ScalarTerminalFold
import civictech.oracle.run.SetTerminalFold
import civictech.oracle.run.TerminalFold
import civictech.oracle.run.asScriptSource
import civictech.query.ref.BatchEvaluator
import civictech.query.ref.RelationValue
import civictech.query.run.OutputShape
import civictech.query.schema.Row
import civictech.testkit.SimWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * [QRY1-ORA-10] for slotfinder (cab.7-D3, cab.7-D10): [SlotFinderQuery]'s compiled form and
 * the hand-wired [SlotPipeline] agree extensionally, at the post-quorum boundary, on every
 * seed of `0L until 50L` through [DifferentialRunner]'s bring-your-own entry point.
 *
 * Two independent cases, sharing a seed and a reference FUNCTION (never a script — `common`
 * is not writable in the hand-wired graph, so the two cases cannot share one script):
 *
 * - **(i) hand-wired, pre- and post-boundary**: [SlotPipeline.build] driven by the three
 *   participant sources; terminals `common`, `filtered`, `byDay`. `common`'s expected state is
 *   the batch quorum intersection of the participants' live sets; `filtered` and `byDay` are
 *   read off [BatchEvaluator] applied to [SlotFinderQuery.compiled]'s plan over that same
 *   `common` batch, decoded back from [Row] into `Slot`/day-keyed terms so the comparison
 *   reads the hand-wired cells' own value domain.
 * - **(ii) compiled**: [SlotFinderQuery.compiled] applied directly, driven by a script over
 *   the single EDB relation `common`, generated at the SAME seed by the same one-writer churn
 *   [SlotFinderPipelineTest] uses; reference = [BatchEvaluator] over the live `common` rows —
 *   the plain [Row]-typed comparison, no codec needed since both sides speak `Row`.
 *
 * Agreement is therefore transitive through [BatchEvaluator] on the ONE compiled plan, not a
 * shared script.
 *
 * `nearMiss` is a quorum output with no relational twin on the far side of the boundary
 * (cab.7-D3) and is not compared here.
 */
class SlotFinderQueryAgreementTest {

    private val seeds = 0L until 50L

    // ------------------------------------------------------------------ codec (case i only)

    private fun encodeSlot(slot: Slot): Row = Row(listOf(slot.day, slot.hour))

    private fun decodeSlot(row: Row): Slot = Slot(row.values[0] as String, row.values[1] as Int)

    // --------------------------------------------------------------------------- case (i)

    private fun buildHandWired(world: SimWorld): CaseGraph {
        val refs = SlotPipeline.build(world.host)
        val mgmt = world.host.managementInlet.call

        val commonFold = SetTerminalFold<Slot>()
        val filteredFold = SetTerminalFold<Slot>()
        val byDayFold = MapTerminalFold<String, Long>()
        val terminals: List<Pair<String, TerminalFold>> = listOf(
            "common" to commonFold,
            "filtered" to filteredFold,
            "byDay" to byDayFold,
        )
        val outlets = mapOf(
            "common" to refs.common.ref,
            "filtered" to refs.filtered.ref,
            "byDay" to refs.byDay.ref,
        )
        terminals.forEach { (name, fold) ->
            mgmt.spawn(fold)
            val result = mgmt.connect(outlets.getValue(name), "outlet", fold.ref, "inlet")
            check(result !is LinkResult.Rejected) {
                "Linking hand-wired terminal '$name' was rejected: ${(result as LinkResult.Rejected).reason}"
            }
        }

        val sources = PARTICIPANTS.associate { name ->
            val ops: SetOps<Slot> = world.host.lookup(refs.participants.getValue(name))!!.inlet.call
            SourceId(name) to ops.asScriptSource()
        }
        return CaseGraph(terminals = terminals.toMap(), sources = sources)
    }

    /** [businessHours] defaults to the true bound; the negative control shifts it. */
    private fun handWiredReference(businessHours: IntRange = Slot.BUSINESS_HOURS): Reference = Reference { script ->
        val liveSets: List<Set<Slot>> = PARTICIPANTS.map { name ->
            Membership.live(script.slice(SourceId(name))).map { it as Slot }.toSet()
        }
        val commonBatch: Set<Slot> = liveSets.reduce { a, b -> a intersect b }

        val db = mapOf("common" to commonBatch.mapTo(LinkedHashSet<Row>()) { encodeSlot(it) })
        val evaluated = BatchEvaluator.evaluate(SlotFinderQuery.compiled.plan, db)

        // The negative control shifts the bound directly rather than recompiling a second
        // plan: it is testing the runner's mismatch reporting, not BatchEvaluator itself.
        val filtered = if (businessHours == Slot.BUSINESS_HOURS) {
            (evaluated.getValue("filtered") as RelationValue.Rows).rows.mapTo(LinkedHashSet()) { decodeSlot(it) }
        } else {
            commonBatch.filter { it.hour in businessHours }.toSet()
        }
        val byDay = LinkedHashMap<Any?, Any?>()
        if (businessHours == Slot.BUSINESS_HOURS) {
            (evaluated.getValue("byDay") as RelationValue.Groups).entries.forEach { (key, count) ->
                byDay[(key as Row).values[0] as String] = count
            }
        } else {
            filtered.groupBy { it.day }.forEach { (day, slots) -> byDay[day] = slots.size.toLong() }
        }

        mapOf(
            "common" to ModelState.SetState(commonBatch),
            "filtered" to ModelState.SetState(filtered),
            "byDay" to ModelState.MapState(byDay),
        )
    }

    /** The same 80-step, single-random-stream churn [SlotFinderPipelineTest] drives. */
    private fun participantScript(seed: Long): Script {
        val rnd = Random(seed)
        val held = PARTICIPANTS.associateWith { mutableSetOf<Slot>() }
        val events = PARTICIPANTS.associateWith { mutableListOf<ScriptEvent>() }
        repeat(80) {
            val user = PARTICIPANTS[rnd.nextInt(PARTICIPANTS.size)]
            val slot = Slot(Slot.DAYS[rnd.nextInt(Slot.DAYS.size)], Slot.HOURS.random(Random(rnd.nextLong())))
            val writer = WriterId(user)
            val mine = held.getValue(user)
            if (slot in mine && rnd.nextInt(10) < 4) {
                events.getValue(user) += ScriptEvent.Remove(writer, slot); mine -= slot
            } else {
                events.getValue(user) += ScriptEvent.Add(writer, slot); mine += slot
            }
        }
        return Script(PARTICIPANTS.map { SourceScript(SourceId(it), events.getValue(it)) })
    }

    @Test
    fun `hand-wired SlotPipeline agrees with the compiled query's BatchEvaluator reference on every seed`() {
        var nonEmptyFilteredSeen = false
        var addsSeen = false
        var removesSeen = false
        for (seed in seeds) {
            val script = participantScript(seed)
            script.slices.forEach { slice ->
                slice.events.forEach { event ->
                    when (event) {
                        is ScriptEvent.Add -> addsSeen = true
                        is ScriptEvent.Remove -> removesSeen = true
                        else -> Unit
                    }
                }
            }
            val reference = handWiredReference()
            val referenceState = reference.evaluate(script)
            if ((referenceState.getValue("filtered") as ModelState.SetState).elements.isNotEmpty()) {
                nonEmptyFilteredSeen = true
            }

            val outcome = DifferentialRunner.check(
                seed = seed,
                caseMarker = "slotfinder hand-wired: SlotPipeline vs BatchEvaluator(SlotFinderQuery.compiled)",
                script = script,
                reference = reference,
                buildGraph = ::buildHandWired,
            )
            assertEquals(RunOutcome.Success, outcome, describeOutcome(outcome, script))
        }
        assertTrue(addsSeen, "the churn never issued an Add across seeds $seeds — script generator is vacuous")
        assertTrue(removesSeen, "the churn never issued a Remove across seeds $seeds — script generator is vacuous")
        assertTrue(
            nonEmptyFilteredSeen,
            "no seed of $seeds reached a non-empty 'filtered' reference — the case is vacuously true",
        )
    }

    @Test
    fun `negative control - a shifted business-hours bound reports Mismatch on filtered`() {
        var mismatchSeen = false
        for (seed in seeds) {
            val script = participantScript(seed)
            val outcome = DifferentialRunner.check(
                seed = seed,
                caseMarker = "slotfinder hand-wired negative control: business hours shifted to 8..17",
                script = script,
                reference = handWiredReference(businessHours = 8..17),
                buildGraph = ::buildHandWired,
            )
            // Only one seed of the range needs to disagree to prove the control instrument
            // works; a seed whose churn never touches hour 8 legitimately agrees on both
            // bounds, so per-seed agreement is not itself a failure.
            if (outcome is RunOutcome.Mismatch && outcome.terminal == "filtered") {
                mismatchSeen = true
            }
        }
        assertTrue(
            mismatchSeen,
            "no seed of $seeds reported a 'filtered' Mismatch under the shifted business-hours bound",
        )
    }

    // -------------------------------------------------------------------------- case (ii)

    private fun buildCompiled(world: SimWorld): CaseGraph {
        val compiled = SlotFinderQuery.compiled
        val applied = compiled.applyTo(world.host.managementInlet)
        val mgmt = world.host.managementInlet.call

        val terminals = compiled.outputShapes.entries.associate { (root, shape) ->
            val fold: TerminalFold = when (shape) {
                OutputShape.SET_OF_ROWS -> SetTerminalFold<Row>()
                OutputShape.MAP_BY_GROUP -> MapTerminalFold<Any?, Any?>()
                OutputShape.COUNTER -> ScalarTerminalFold()
            }
            val outputRef = applied.outputs.getValue(root)
            mgmt.spawn(fold)
            val result = mgmt.connect(outputRef.ref, "outlet", fold.ref, "inlet")
            check(result !is LinkResult.Rejected) {
                "Linking compiled root '$root' was rejected: ${(result as LinkResult.Rejected).reason}"
            }
            root to fold
        }

        val sources = applied.sources.entries.associate { (relation, ref) ->
            val ops: SetOps<Row> = world.host.lookup(ref)!!.inlet.call
            SourceId(relation) to ops.asScriptSource()
        }
        return CaseGraph(terminals = terminals, sources = sources)
    }

    private fun compiledReference(): Reference = Reference { script ->
        val db = SlotFinderQuery.compiled.sourceHandles.keys.associateWith { relation ->
            Membership.live(script.slice(SourceId(relation))).mapTo(LinkedHashSet()) { it as Row }
        }
        BatchEvaluator.evaluate(SlotFinderQuery.compiled.plan, db).mapValues { (_, value) ->
            when (value) {
                is RelationValue.Rows -> ModelState.SetState(value.rows)
                is RelationValue.Groups -> ModelState.MapState(value.entries)
                is RelationValue.Count -> ModelState.ScalarState(value.count)
            }
        }
    }

    /** A single-writer, same-shape churn over `common` directly (cab.7-D10). */
    private fun commonScript(seed: Long): Script {
        val rnd = Random(seed)
        val held = mutableSetOf<Slot>()
        val writer = WriterId("w")
        val events = mutableListOf<ScriptEvent>()
        repeat(80) {
            val slot = Slot(Slot.DAYS[rnd.nextInt(Slot.DAYS.size)], Slot.HOURS.random(Random(rnd.nextLong())))
            if (slot in held && rnd.nextInt(10) < 4) {
                events += ScriptEvent.Remove(writer, encodeSlot(slot)); held -= slot
            } else {
                events += ScriptEvent.Add(writer, encodeSlot(slot)); held += slot
            }
        }
        return Script(listOf(SourceScript(SourceId("common"), events)))
    }

    @Test
    fun `compiled SlotFinderQuery agrees with BatchEvaluator over its own plan on every seed`() {
        var addsSeen = false
        var removesSeen = false
        var nonEmptyFilteredSeen = false
        for (seed in seeds) {
            val script = commonScript(seed)
            script.slices.single().events.forEach { event ->
                when (event) {
                    is ScriptEvent.Add -> addsSeen = true
                    is ScriptEvent.Remove -> removesSeen = true
                    else -> Unit
                }
            }
            val reference = compiledReference()
            val referenceState = reference.evaluate(script)
            val filteredState = referenceState["filtered"] as? ModelState.SetState
            if (filteredState != null && filteredState.elements.isNotEmpty()) nonEmptyFilteredSeen = true

            val outcome = DifferentialRunner.check(
                seed = seed,
                caseMarker = "slotfinder compiled: SlotFinderQuery.compiled vs BatchEvaluator(same plan)",
                script = script,
                reference = reference,
                buildGraph = ::buildCompiled,
            )
            assertEquals(RunOutcome.Success, outcome, describeOutcome(outcome, script))
        }
        assertTrue(addsSeen, "the common churn never issued an Add across seeds $seeds — script generator is vacuous")
        assertTrue(
            removesSeen,
            "the common churn never issued a Remove across seeds $seeds — script generator is vacuous",
        )
        assertTrue(
            nonEmptyFilteredSeen,
            "no seed of $seeds reached a non-empty compiled 'filtered' reference — the case is vacuously true",
        )
    }

    private fun describeOutcome(outcome: RunOutcome, script: Script): String = when (outcome) {
        RunOutcome.Success -> "success"
        else -> "$outcome\nreplay script: $script"
    }
}
