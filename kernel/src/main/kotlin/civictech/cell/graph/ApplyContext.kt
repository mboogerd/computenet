package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.Cell
import civictech.cell.data.Replicable
import civictech.cell.durability.Journal
import civictech.cell.evolve.Shadow
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.KeyedCells
import civictech.cell.host.ManagedHost
import civictech.cell.host.Recovery
import civictech.cell.host.JournalRecords
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
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
 * those bindings; this type only provides their shared home. A context owner
 * wires the host to that table before applying a spec, for example:
 *
 * ```
 * lateinit var context: ApplyContext
 * val host = ManagedHost(journalFor = { ref -> context.journalFor(ref) ?: defaultJournal })
 * context = ApplyContext(host, journals = mapOf("default" to defaultJournal))
 * ```
 *
 * The selector is evaluated once at spawn, so [GraphSpec.apply] binds a
 * `journalId` before calling the host's spawn path.
 */
class ApplyContext(
    val host: ManagedHost,
    val replication: Replication? = null,
    val journals: Map<String, Journal> = emptyMap(),
    val journalDirs: Map<String, File> = emptyMap(),
    val topology: Journal? = null,
) : TopologyApplier {
    private val journalBindings = ConcurrentHashMap<CellRef, Journal>()
    private val fold = MutableTopologyFold()
    private val activeLinks = mutableMapOf<TopologyLinkKey, Link>()
    private val familyInstances = mutableMapOf<String, KeyedCells<*>>()
    private var replayDepth = 0

    init {
        topology?.let { journal -> host.registerTopology(journal) { live().events() } }
    }

    /** Cumulative live spawn handles, including handles explicitly [adopt]ed by the app. */
    val handles: Map<String, CellRef> get() = fold.snapshot().handles

    internal fun bind(ref: CellRef, journal: Journal) {
        journalBindings[ref] = journal
    }

    fun journalFor(ref: CellRef): Journal? = journalBindings[ref]

    /** Register an already-live app-owned ref as a topology handle without journaling it. */
    fun adopt(handle: String, ref: CellRef) {
        fold.adopt(handle, ref)
    }

    /** Immutable view of the successfully-applied live topology. */
    fun live(): TopologyFold = fold.snapshot()

    /** Recover topology and frames together, preserving this context's services and handle table. */
    fun recover(journal: Journal): Recovery = host.recoverFrom(journal, this)

    /**
     * Apply only topology records from [journal]. Checkpoints and frames remain untouched; this
     * is the offline topology seam used by consumers that need the fold but not a live replay.
     */
    fun replayTopology(journal: Journal): TopologyFold = replaying {
        journal.replay().forEach { record ->
            val decoded = JournalRecords.decode(record)
            if (decoded is DecodedJournalRecord.Topology) decoded.events.forEach(::apply)
        }
        live()
    }

    /** Suppress topology recording for the dynamic extent of a recovery replay. */
    internal fun <T> replaying(action: () -> T): T {
        replayDepth++
        return try {
            action()
        } finally {
            replayDepth--
        }
    }

    /** One write-ahead topology record for one GraphSpec delta or one builder operation. */
    internal fun journalTopology(events: List<TopoEvent>) {
        if (replayDepth == 0) topology?.let { host.journalTopology(it, events) }
    }

    internal fun hasHandle(handle: String): Boolean = fold.containsHandle(handle)

    internal fun refFor(handle: String): CellRef = fold.refFor(handle)
        ?: throw IllegalStateException("unknown handle '$handle'")

    internal fun linkFor(key: TopologyLinkKey): Link? = synchronized(activeLinks) { activeLinks[key] }

    internal fun familyFor(handle: String): KeyedCells<*>? = synchronized(familyInstances) { familyInstances[handle] }

    /** Apply one recovered or already-journaled event. Recording is deliberately separate. */
    override fun apply(event: TopoEvent) {
        when (event) {
            is TopoEvent.Spawn -> applySpawn(event)
            is TopoEvent.Connect -> applyConnect(event)
            is TopoEvent.Unlink -> applyUnlink(event)
            is TopoEvent.Despawn -> applyDespawn(event)
            is TopoEvent.Family -> applyFamily(event)
            is TopoEvent.FamilyKey -> {
                host.recoverFamilyKey(event.namespace, event.key)
                fold.record(event)
            }
        }
    }

    /**
     * A compacted journal restores cell state after its leading topology fold. Re-handshake
     * the folded links at that boundary so ordinary on-linked catch-up observes the restored
     * state; an uncompacted frame tail needs no such nudge because its replay emits normally.
     */
    override fun checkpointRestored() {
        live().links.values.forEach { event ->
            val key = TopologyLinkKey.of(event)
            synchronized(activeLinks) { activeLinks.remove(key) }?.unlink()
            applyConnect(event)
        }
    }

    internal fun applySpawn(event: TopoEvent.Spawn, prepared: Cell? = null): CellRef {
        check(!fold.containsHandle(event.handle)) { "duplicate handle '${event.handle}'" }
        event.journalId?.let { journalId ->
            bind(event.ref, journals[journalId] ?: throw missingJournal(event.handle, journalId))
        }
        val cell = prepared ?: event.factory.create(event.ref)
        requireBoundRef(event.handle, IdentityBinding.Exact(event.ref), event.ref, cell.ref)
        val spawned = if (event.replicated) {
            val service = replication ?: throw missingReplication(event.handle)
            val replicable = cell as? Replicable<*>
                ?: throw IllegalStateException(
                    "spawn step '${event.handle}': parameter 'replicated' requires a Replicable cell " +
                        "(built ${cell.javaClass.name})",
                )
            service.replicate(replicable, host)
            if (event.shadow) suppressShadow(cell)
            cell.ref
        } else if (event.shadow) {
            Shadow.spawn(host, cell)
        } else {
            host.managementInlet.call.spawn(cell)
        }
        check(spawned == event.ref) {
            "spawn step '${event.handle}' materialized $spawned instead of pinned ref ${event.ref}"
        }
        fold.record(event)
        return spawned
    }

    internal fun applyConnect(event: TopoEvent.Connect): Link? {
        val key = TopologyLinkKey.of(event)
        val link = when (
            val result = host.managementInlet.call.connectStep(
                event.from, event.outlet, event.to, event.inlet, event.options,
            )
        ) {
            is LinkResult.Connected -> result.link.also { synchronized(activeLinks) { activeLinks[key] = it } }
            is LinkResult.Rejected -> error(
                "link ${event.from}.${event.outlet} → ${event.to}.${event.inlet} rejected: ${result.reason}",
            )
            LinkResult.Deferred -> null
        }
        fold.record(event)
        return link
    }

    internal fun applyUnlink(event: TopoEvent.Unlink) {
        val key = TopologyLinkKey.of(event)
        val link = synchronized(activeLinks) { activeLinks.remove(key) }
            ?: throw IllegalStateException("unlink event '$key': no live link")
        link.unlink()
        fold.record(event)
    }

    internal fun applyDespawn(event: TopoEvent.Despawn) {
        fold.linksTouching(event.ref).forEach { edge ->
            val key = TopologyLinkKey.of(edge)
            val link = synchronized(activeLinks) { activeLinks.remove(key) }
                ?: throw IllegalStateException("despawn ${event.ref}: fold contains live link '$key' with no link object")
            link.unlink()
        }
        host.managementInlet.call.despawn(event.ref)
        fold.record(event)
    }

    private fun applyFamily(event: TopoEvent.Family) {
        check(!fold.containsHandle(event.handle)) { "duplicate handle '${event.handle}'" }
        val family = buildFamily(
            SpawnStep(handle = event.handle, factory = event.factory, family = event.family),
        )
        host.registerFamily(event.family.namespace, family)
        synchronized(familyInstances) { familyInstances[event.handle] = family }
        fold.record(event)
    }

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
