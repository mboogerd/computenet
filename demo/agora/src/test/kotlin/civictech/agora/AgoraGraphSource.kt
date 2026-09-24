package civictech.agora

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.timetravel.reconstruct.GraphBuild
import civictech.timetravel.reconstruct.GraphSource
import java.io.File

/**
 * `:timetravel`'s [GraphSource] over agora's own structure log (computenet-3qkx1 D6/D7/D12): the
 * journal carries invocations, never spawns, so the reconstructor needs the topology from
 * somewhere — here, the `graph.jsonl` an [AgoraService] replays on construction. The adapter lives
 * in agora, never in `:timetravel` (epic computenet-ocv §9.1: stay agnostic of app structure logs).
 *
 * Public, with a public `(String)` constructor, so the CLI reaches it as
 * `--graph-provider civictech.agora.AgoraGraphSource --graph-arg <journalDir>` (D7).
 */
class AgoraGraphSource(private val journalDir: String) : GraphSource {

    /**
     * Agora wires every edge through `registry.inlet(...)`, which throws unless the cell is
     * published on that registry — a registry of this adapter's own would hold no location for
     * the reconstruction host's cells (D6). The one-argument form therefore refuses.
     */
    override fun build(host: ManagedHost): GraphBuild =
        throw UnsupportedOperationException("AgoraGraphSource needs the host's registry")

    override fun build(host: ManagedHost, registry: LocationRegistry): GraphBuild =
        AgoraService(host, registry, structureLog = File(journalDir, "graph.jsonl"))
            .let { GraphBuild(it.cells()) }
}
