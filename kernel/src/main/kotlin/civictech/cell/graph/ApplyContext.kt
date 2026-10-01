package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.durability.Journal
import civictech.cell.host.KeyedCells
import civictech.cell.host.ManagedHost
import civictech.cell.link.Link
import civictech.cell.replication.Replication
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The local services a parameterized [GraphSpec] may lower onto. Parameters
 * remain data on graph steps; this context supplies the already-existing
 * runtime mechanisms that realize them (93 I-21, x0oag-D2).
 *
 * [journalFor] is intentionally backed by a per-ref binding table rather than
 * by [journals] directly: a later durable spawn binds its resolved ref before
 * the host evaluates its `journalFor` selector. The durable graph task owns
 * those bindings; this type only provides their shared home.
 */
class ApplyContext(
    val host: ManagedHost,
    val replication: Replication? = null,
    val journals: Map<String, Journal> = emptyMap(),
    val journalDirs: Map<String, File> = emptyMap(),
) {
    private val journalBindings = ConcurrentHashMap<CellRef, Journal>()

    internal fun bind(ref: CellRef, journal: Journal) {
        journalBindings[ref] = journal
    }

    fun journalFor(ref: CellRef): Journal? = journalBindings[ref]
}

/** The handles produced by one local [GraphSpec.apply]. */
data class AppliedGraph(
    val refs: Map<String, CellRef>,
    val families: Map<String, KeyedCells<*>>,
    val links: Map<String, Link>,
)
