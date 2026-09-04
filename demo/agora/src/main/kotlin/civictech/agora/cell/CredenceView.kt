package civictech.agora.cell

import civictech.cell.CellRef
import civictech.cell.observe.ObservationSink
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import java.io.Serializable

/**
 * The read-model fold behind agora's hub, run by the kernel observation sink
 * ([ObserveCell]). Folds every claim/edge's [CredenceUpdate] stream into one
 * immutable `{ source -> credence }` map (replacing the hand-rolled
 * `GraphHubCell` + `ConcurrentHashMap`). Removed claims are filtered against the
 * service index at read time, never pruned here.
 *
 * [onUpdate] preserves agora's per-source credence seam: the app wires it to the
 * SSE broadcast and `MagnitudePriorityTest` uses it to observe read-model
 * arrival order. It fires once per applied delta with `(source, credence)` —
 * matching `GraphHubCell.onUpdate` — while [apply]'s return value reports
 * *effective* change so the sink fires `onChange` only on a real value change.
 *
 * Threading (computenet-ecso): in normal use, [apply] and [current] are only
 * ever invoked by the [ObserveCell] wrapping this view — always under its own
 * lock, from its host scheduler thread — and every cross-thread read of the
 * fold (agora's `graph()` on the HTTP dispatcher / `dialogue-driver` thread)
 * goes through `ObservationSink.current()`, i.e. [ObserveCell]'s own
 * `@Volatile latest`, not through this class's [current] directly. That
 * already gives `graph()` readers a safe publication of whatever [apply] last
 * wrote here, by the ordinary "plain write before a volatile write, volatile
 * read before the dependent plain read" JMM argument. [credences] is still
 * marked `@Volatile` here, independently, so this class is safe to publish
 * across threads on its own terms — e.g. a future caller that holds a
 * `CredenceView` directly rather than only its wrapping `ObservationSink` —
 * rather than relying on every future caller going through [ObserveCell].
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

/** The former `GraphHubCell.credenceOf`, now a read over the sink's snapshot. */
fun ObservationSink<Map<CellRef, Double>>.credenceOf(ref: CellRef): Double? = current()[ref]
