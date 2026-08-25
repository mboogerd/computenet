package civictech.oracle.gen

import civictech.oracle.model.Delivery
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
data class CaseScript(
    val steps: List<CaseStep>,
    /**
     * The gossip this case's replicas exchange, as **positions in [steps]** rather than as event
     * counts (`ORA2 §GEN-03`). Empty for every non-replicated case, which is why it defaults.
     *
     * See [CaseDelivery] for why the position-rather-than-count representation is the one that
     * makes a mis-stated causal history unconstructable.
     */
    val deliveries: List<CaseDelivery> = emptyList(),
) : Serializable {

    init {
        deliveries.forEach { delivery ->
            require(delivery.atStep in 0..steps.size) {
                "CaseDelivery names step ${delivery.atStep}, outside this ${steps.size}-step script"
            }
        }
    }

    /**
     * Projects this total drive order down to the model's per-source, barrier-free [Script]:
     * drops every [CaseStep.Barrier], groups the remaining [CaseStep.Op]s by
     * [CaseStep.Op.source] preserving each source's relative event order, and **derives** each
     * [CaseDelivery]'s `afterEvents`/`throughEvents` from how many `Op`s of the receiving and
     * sending slices precede its `atStep`.
     *
     * Deriving rather than carrying those two counts is the point. `civictech.oracle.model.Delivery`
     * states causality as a pair of event counts, and a harness that writes those counts by hand
     * can understate what a replica had absorbed — which surfaces as a `ReplicaDivergence` or a
     * `ReplicasAgreeButWrong` verdict *shaped like a kernel defect* even though the fault is in
     * the case (computenet-4ru.1.4's finding, and its reviewer's note that the loud failure is
     * loudly MIS-ATTRIBUTED). Here the counts cannot be understated or overstated: they are read
     * off the same total order the runner replays, so the statement "this replica had absorbed
     * that prefix" is true by construction of the drive order rather than by the author's care.
     */
    fun toScript(): Script {
        val events = LinkedHashMap<SourceId, MutableList<ScriptEvent>>()
        val absorbed = LinkedHashMap<SourceId, MutableList<Delivery>>()
        val counts = LinkedHashMap<SourceId, Int>()
        val scheduled = deliveries.groupBy { it.atStep }

        fun deliverAt(index: Int) {
            scheduled[index]?.forEach { delivery ->
                absorbed.getOrPut(delivery.into) { mutableListOf() } += Delivery(
                    afterEvents = counts[delivery.into] ?: 0,
                    from = delivery.from,
                    throughEvents = counts[delivery.from] ?: 0,
                )
            }
        }

        steps.forEachIndexed { index, step ->
            deliverAt(index)
            if (step is CaseStep.Op) {
                events.getOrPut(step.source) { mutableListOf() }.add(step.event)
                counts[step.source] = (counts[step.source] ?: 0) + 1
            }
        }
        deliverAt(steps.size)

        // Union of the two lanes: a replica may receive gossip before it has issued anything of
        // its own, and its slice still has to exist for the sender's prefix to be resolvable.
        val sources = LinkedHashSet<SourceId>().apply {
            addAll(events.keys)
            addAll(absorbed.keys)
        }
        return Script(
            sources.map { source ->
                SourceScript(source, events[source].orEmpty(), absorbed[source].orEmpty())
            },
        )
    }

    companion object {
        val EMPTY: CaseScript = CaseScript(emptyList())
    }
}

/**
 * One gossip delivery, stated as a **position in a [CaseScript]'s total drive order**: *"just
 * before step [atStep], replica [into] absorbed everything replica [from] had emitted so far."*
 *
 * Deliberately not a [CaseStep] variant, and deliberately not a pair of event counts.
 *
 * - Not a [CaseStep]: `CaseStep` is a *sealed* hierarchy that four files outside this one
 *   exhaustively `when` over (`shrink/RenderKotlin.kt`, `run/DifferentialRunner.kt` and their
 *   tests), so a third variant is a compile break in files this task does not own. A parallel
 *   field costs nothing semantically — the total order still says exactly when the gossip
 *   happened — and keeps the ORA1 drive loop's `when` exhaustive and untouched.
 * - Not event counts: see [CaseScript.toScript].
 *
 * ## Acyclicity, by construction
 *
 * `civictech.oracle.model.DotModel` refuses a script whose deliveries are cyclic, and a
 * *full* all-to-all gossip round at one point of the drive order IS such a cycle: two replicas
 * that each claim to have absorbed the other at their current event counts describe no reachable
 * state. `ScriptGenerator` therefore never emits one — see its `gossip round` KDoc for the two
 * rules (a per-round permutation chain, and at least one own event per replica between rounds)
 * and the argument that they make a cycle unconstructable rather than merely improbable.
 *
 * @property atStep index into [CaseScript.steps] **before** which this delivery lands;
 *   `steps.size` is admissible and means "after the last step".
 * @property into the replica that absorbed the gossip.
 * @property from the replica whose emissions arrived. Never equal to [into].
 */
data class CaseDelivery(
    val atStep: Int,
    val into: SourceId,
    val from: SourceId,
) : Serializable {
    init {
        require(atStep >= 0) { "CaseDelivery.atStep must not be negative: $atStep" }
        require(into != from) { "A replica does not gossip with itself: ${into.id}" }
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
