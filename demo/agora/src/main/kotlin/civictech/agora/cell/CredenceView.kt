package civictech.agora.cell

import civictech.cell.CellRef
import civictech.cell.observe.View
import java.io.Serializable

/**
 * The read-model fold behind agora's canonical credence observation. The
 * `CredenceObservationSource`'s inlet handler drives it with every claim/edge
 * [CredenceUpdate], and the app reads the resulting immutable
 * `{ source -> credence }` map through the canonical `Observation` rather than
 * through a separate observation sink. Removed claims are filtered against the
 * service index at read time, never pruned here.
 *
 * [onUpdate] preserves agora's per-source credence seam: the app wires it to the
 * SSE broadcast and `MagnitudePriorityTest` uses it to observe read-model
 * arrival order. It fires once per applied delta with `(source, credence)` —
 * matching `GraphHubCell.onUpdate` — while [apply]'s return value reports
 * *effective* change so the source publishes only a real value change.
 *
 * Threading (computenet-ecso): in normal use, [apply] runs in the source's
 * host-scheduler inlet handler and [current] is used for source catch-up.
 * Cross-thread reads of the fold (agora's `graph()` on the HTTP dispatcher /
 * `dialogue-driver` thread) go through the canonical `Observation`'s current
 * frame, not through this class's [current] directly. [credences] is still
 * marked `@Volatile` here, independently, so this class is safe to publish
 * across threads on its own terms if a future caller holds a `CredenceView`
 * directly.
 */
class CredenceView(
    private val onUpdate: (CellRef, Double) -> Unit = { _, _ -> },
) : View<CredenceUpdate, Map<CellRef, Double>> {

    @Volatile
    private var credences: Map<CellRef, Double> = emptyMap()

    override fun apply(delta: CredenceUpdate): Boolean {
        onUpdate(delta.source, delta.credence)
        val changed = credences[delta.source] != delta.credence
        if (changed) credences = credences + (delta.source to delta.credence)
        return changed
    }

    override fun current(): Map<CellRef, Double> = credences

    override fun snapshot(): Serializable = HashMap(credences)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        credences = HashMap(state as Map<CellRef, Double>)
    }
}
