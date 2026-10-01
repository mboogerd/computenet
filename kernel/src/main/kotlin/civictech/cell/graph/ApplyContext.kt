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

    /** Construct the wrapper for a keyed family without spawning or recovering any key. */
    internal fun buildFamily(step: SpawnStep): KeyedCells<Any> {
        val family = step.family
            ?: error("spawn step '${step.handle}' has no family parameter")
        val factory = step.factory as? KeyedCellFactory
            ?: error("spawn step '${step.handle}': parameter 'family' requires a KeyedCellFactory")
        val journalDir = family.journalId?.let { journalId ->
            journalDirs[journalId]
                ?: throw missingFamilyJournal(step.handle, journalId)
        }
        return KeyedCells(
            host = host,
            journalDir = journalDir,
            namespace = family.namespace,
            factory = { key, ref -> factory.create(key, ref) },
            render = family.keys.render,
            parse = family.keys.parse,
        )
    }
}

/** The handles produced by one local [GraphSpec.apply]. */
data class AppliedGraph(
    val refs: Map<String, CellRef>,
    val families: Map<String, KeyedCells<*>>,
    val links: Map<String, Link>,
)
