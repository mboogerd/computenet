package civictech.timetravel.reconstruct

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.host.ManagedHost
import java.io.Serializable
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * [GraphSource] over a recorded [GraphSpec] (computenet-6tm33 D2): reapplies
 * every lowered spawn onto the reconstruction host and hands back the very
 * cell instances it spawned, by wrapping each lowered [SpawnStep.factory] in
 * a capturing [CellFactory] that delegates to the original, remembers the
 * built cell, and returns it unchanged.
 *
 * Does not mutate [spec]: [GraphSpec.lowered] and [GraphSpec.applyTo] never
 * write back into the steps they read, and the capturing wrapper only tees
 * the *result* of each delegate call into [captured] — the spec instance
 * given to the constructor keeps its original `steps` untouched, and its
 * `lowered()` list carries the same `handle`/`identity`/`parent`/`ConnectStep`
 * data as the original, factory field aside.
 *
 * [build] does not drive the host to idle (6tm33-D12): spawn is
 * management-band asynchronous, so the reconstructor owns the
 * `runToIdle()` that follows.
 */
class GraphSpecSource(
    private val spec: GraphSpec,
    private val journaled: ((CellRef) -> Boolean)? = null,
) : GraphSource {

    override fun build(host: ManagedHost): GraphBuild {
        val captured = ConcurrentLinkedQueue<Cell>()
        val steps = spec.lowered().map { step ->
            if (step is SpawnStep) step.copy(factory = CapturingFactory(step.factory, captured)) else step
        }
        GraphSpec(steps).applyTo(host.managementInlet)
        return GraphBuild(captured.toList(), journaled)
    }

    /**
     * Wraps [delegate], remembering every cell it builds into [captured] before
     * returning it unchanged. A private, [Serializable] class — [CellFactory]
     * itself is `Serializable` (graphs-as-data, G-30), and this wrapper is
     * built fresh per [build] call, never stored on a recorded [GraphSpec], so
     * its serializability is never exercised across the wire; it only has to
     * hold for the local `applyTo` this class calls.
     */
    private class CapturingFactory(
        private val delegate: CellFactory,
        private val captured: ConcurrentLinkedQueue<Cell>,
    ) : CellFactory, Serializable {
        override fun create(ref: CellRef): Cell = delegate.create(ref).also { captured += it }
    }
}
