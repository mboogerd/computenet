package civictech.agora

import civictech.cell.ReplayScope
import civictech.cell.TagFrontier
import civictech.cell.durability.FileJournal
import civictech.cell.graph.ApplyContext
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.timetravel.reconstruct.GraphBuild
import civictech.timetravel.reconstruct.GraphSource
import java.io.File

/**
 * `:timetravel`'s [GraphSource] over agora's journaled kernel topology. The adapter lives in
 * agora, never in `:timetravel` (epic computenet-ocv §9.1: stay agnostic of app structure).
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

    override fun build(host: ManagedHost, registry: LocationRegistry): GraphBuild {
        val journal = FileJournal(File(journalDir, "host.journal"))
        val context = ApplyContext(
            host,
            journals = mapOf("host" to journal),
            topology = journal,
        )
        val service = AgoraService(host, registry, context = context)
        // replayTopology is synchronous but, unlike full recover(), has no
        // frame replay scope of its own. Install the same marker factories use
        // during recovery so link admission cannot emit neutral catch-ups
        // before the reconstructor restores state and replays the frame tail.
        ReplayScope.with(TagFrontier(emptyMap())) { context.replayTopology(journal) }
        service.rebuildIndex()
        return GraphBuild(service.cells())
    }
}
