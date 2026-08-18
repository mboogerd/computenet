package civictech.oracle.gen

import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.Script
import java.io.Serializable

/**
 * The gen-level drive order over a case's sources: the total interleaving a runner
 * (computenet-4ru.8) replays, plus the quiesce [Barrier]s it interprets as a wait for every
 * source to settle before continuing.
 *
 * This is the settled split with `civictech.oracle.model.Script` (computenet-4ru.5): the
 * model's [Script] stays per-source and barrier-free — it is what the reference model
 * evaluates, and evaluation needs no total order because sources are independent
 * (`Script`'s own KDoc: "order matters within a source and is meaningless across sources").
 * A total drive order and a quiesce point are properties of *replaying* a case against a live
 * kernel, not of computing its reference answer, so they live here, one level below the model,
 * and never inside [ScriptEvent]'s sealed hierarchy.
 *
 * [CaseStep] deliberately has no link-open/close and no replay-step variant: a generated case
 * cannot exercise link churn under a quorum or a `[24-REPLAY-01]` baseline bypass, because
 * `QuorumSetModel` states neither is expressible in a script (`SetOperatorModels.kt`,
 * `QuorumSetModel`'s KDoc) — keeping such cases out is the generator's job, and the type
 * itself is where that job is enforced: there is no step kind to construct one with.
 */
data class CaseScript(val steps: List<CaseStep>) : Serializable {

    /**
     * Projects this total drive order down to the model's per-source, barrier-free [Script]:
     * drops every [CaseStep.Barrier] and groups the remaining [CaseStep.Op]s by
     * [CaseStep.Op.source], preserving each source's relative event order.
     */
    fun toScript(): Script {
        val bySource = LinkedHashMap<SourceId, MutableList<ScriptEvent>>()
        steps.forEach { step ->
            if (step is CaseStep.Op) {
                bySource.getOrPut(step.source) { mutableListOf() }.add(step.event)
            }
        }
        return Script(bySource.map { (source, events) -> SourceScript(source, events) })
    }

    companion object {
        val EMPTY: CaseScript = CaseScript(emptyList())
    }
}

/**
 * One step of a [CaseScript]'s total drive order.
 *
 * Exactly two variants — [Op] and [Barrier] — by construction, so a generated case cannot name
 * a link-open/close or a replay step: see [CaseScript]'s KDoc for why those are out of what a
 * script over `QuorumSetModel` (and any other reference model) can define.
 */
sealed interface CaseStep : Serializable {
    /** Drives [event] into [source]'s script slice, next in that source's arrival order. */
    data class Op(val source: SourceId, val event: ScriptEvent) : CaseStep

    /** A quiesce point: the runner waits for every source to settle before the next step. */
    data object Barrier : CaseStep
}
