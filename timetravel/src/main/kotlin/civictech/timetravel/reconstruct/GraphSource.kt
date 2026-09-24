package civictech.timetravel.reconstruct

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost

/**
 * Rebuilds a graph's topology onto a fresh, offline reconstruction host
 * (computenet-6tm33 D2, epic computenet-ocv §3 "The graph is not in the
 * journal"): the journal carries invocations, never spawns, so the
 * reconstructor cannot rebuild the graph from journal content alone and
 * needs a source of the topology — a recorded [civictech.cell.graph.GraphSpec]
 * ([GraphSpecSource]) or an application-owned structure log (the CLI/agora
 * adapter, out of scope here).
 *
 * [build] is called once per [Reconstructor][civictech.timetravel.reconstruct]
 * run, against a fresh [host] the reconstructor owns; it does not drive the
 * host to idle itself — the reconstructor owns that drive (6tm33-D12).
 */
fun interface GraphSource {
    fun build(host: ManagedHost): GraphBuild

    /**
     * [build], additionally handed the [registry] [host] publishes its spawned cells into
     * (computenet-3qkx1 D6). Override this form when the topology is wired through
     * `LocationRegistry.inlet` — a fresh registry of the source's own would hold no location for
     * the reconstruction host's cells. The reconstructor calls this form; the default delegates
     * to [build], so every one-argument source keeps working unchanged.
     */
    fun build(host: ManagedHost, registry: LocationRegistry): GraphBuild = build(host)
}

/**
 * The result of one [GraphSource.build] (D2): every cell instance the source
 * spawned onto the host, all of it — a source that spawns a cell it does not
 * hand back here cannot be classified or suppressed downstream, and the
 * reconstructor refuses before replay rather than firing an unverified
 * reconstruction into it ([Unreconstructible][civictech.timetravel.fidelity]
 * `GRAPH_SOURCE_INCOMPLETE`, owned by the reconstructor, not this type).
 *
 * @property cells every instance this source spawned onto the host, all of
 *   them.
 * @property journaled the source's own knowledge of the original run's
 *   `journalFor` selector, if it has any — `null` means unknown. This
 *   feature only carries the value through; the honesty feature (`VOLATILE_CELL`)
 *   is the consumer.
 */
data class GraphBuild(val cells: Collection<Cell>, val journaled: ((CellRef) -> Boolean)? = null)
