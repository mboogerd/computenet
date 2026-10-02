package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.TopoEvent
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID

/**
 * A durable, dynamically-sized family of cells keyed by [K] — one cell per key,
 * spawned lazily on first touch and kept recoverable across a `kill -9` (spec 24
 * durability, M10.4).
 *
 * This packages the plumbing a per-key writer set (`demo/shopping`'s
 * `writerFor(user)`) otherwise hand-codes four times over:
 * - **deterministic ref-per-key** — `nameUUIDFromBytes("$namespace:$key")`, so a
 *   key's cell carries the same [CellRef] (hence the same replay-stable tag
 *   source, [civictech.cell.data.SetCell]) across restarts;
 * - **lazy [getOrSpawn]** — spawn on first touch, and thereafter idempotent: a
 *   live key returns the same cell without re-spawning, so the `Exact` live-ref
 *   spawn guard ([civictech.cell.graph.IdentityBinding.Exact], G-51) is
 *   unreachable through this API;
 * - **journal-native durable membership** — the first touch records a
 *   [TopoEvent.FamilyKey] in the key cell's own selected journal before the
 *   cell is spawned. Recovery therefore encounters the key before any of its
 *   frames and spawns it under the deterministic ref in journal order;
 * - **checkpoint-safe membership** — the family contributes its recorded keys
 *   to each journal's topology fold, so compaction preserves membership.
 *
 * Durability follows the host's selector for each key's ref, not [journalDir].
 * A null selector makes that key volatile: no topology record is written and
 * [keys]/[contains] are in-memory only. [journalDir] is solely the convenience
 * location used by [recover] to open [HOST_JOURNAL]; no `keys` side file exists.
 *
 * Non-`String` keys supply [render]/[parse] so a key round-trips through its
 * topology event and its ref seed; the default is `String` identity. Rendered
 * keys must be single-line.
 */
class KeyedCells<K : Any>(
    private val host: ManagedHost,
    private val journalDir: File?,
    private val namespace: String,
    private val factory: (K, CellRef) -> Cell,
    private val render: (K) -> String = { it.toString() },
    private val parse: (String) -> K = { @Suppress("UNCHECKED_CAST") (it as K) },
) {
    private val lock = Any()

    /** Cells spawned this session, by key — the in-memory index (was the `writers` map). */
    private val live = mutableMapOf<K, Cell>()

    /** Every key ever spawned, populated in journal order during recovery. */
    private val known = mutableSetOf<K>()

    /** Keys whose topology provider belongs to each selected journal (journal identity is semantic). */
    private val keysByJournal = IdentityHashMap<Journal, LinkedHashSet<K>>()

    init {
        host.registerFamily(namespace, this)
    }

    /**
     * The cell for [key], spawning it on first touch. Idempotent: a live key
     * returns the same cell — no second spawn (so the live-ref guard never
     * fires) and no second durable record.
     */
    fun getOrSpawn(key: K): Cell = getOrSpawn(key, recovering = false)

    private fun getOrSpawn(key: K, recovering: Boolean): Cell = synchronized(lock) {
        live[key]?.let { return it }
        val ref = refFor(key)
        val cell = factory(key, ref)
        val fresh = known.add(key)
        var recorded = recovering
        try {
            if (fresh) {
                if (recovering) {
                    host.topologyJournal(ref, cell)?.let { rememberForCheckpoint(it, key) }
                } else {
                    recorded = host.recordTopology(
                        ref,
                        TopoEvent.FamilyKey(namespace, render(key)),
                        cell,
                    ) { journal -> rememberForCheckpoint(journal, key) } != null
                }
            }
            host.managementInlet.call.spawn(cell)
            live[key] = cell
            cell
        } catch (failure: Throwable) {
            // A successful write-ahead record owns the key even if the following spawn fails:
            // recovery must retry it. A volatile or refused record owns nothing.
            if (fresh && !recorded) forgetUnrecorded(key)
            throw failure
        }
    }

    /** Decode the journal representation through this family's codec before spawning. */
    internal fun recoverKey(rendered: String): Cell = getOrSpawn(parse(rendered), recovering = true)

    /**
     * Restore the family after a crash. The journal's [TopoEvent.FamilyKey]
     * records enforce the ordering: each key is decoded and spawned before the
     * next record (and therefore before that key's first frame) is submitted.
     */
    fun recover() {
        hostJournal(journalDir)?.let { host.recoverFrom(it) }
    }

    /** Every key spawned in this process; durable membership is complete after [recover]. */
    fun keys(): Set<K> = synchronized(lock) { known.toSet() }

    /**
     * Whether [key] has been spawned — durable membership is complete after
     * [recover], same as [keys]. An O(1) membership check against the same
     * lock-guarded set [keys] copies wholesale; prefer this when only
     * membership is wanted (e.g. one scope key per pull), not the whole set.
     */
    fun contains(key: K): Boolean = synchronized(lock) { key in known }

    /** Deterministic, restart-stable ref: `nameUUIDFromBytes("$namespace:$key")` (instanceId 0). */
    private fun refFor(key: K): CellRef =
        CellRef(UUID.nameUUIDFromBytes("$namespace:${render(key)}".toByteArray()))

    private fun rememberForCheckpoint(journal: Journal, key: K) {
        val keys = keysByJournal[journal]
        if (keys != null) {
            keys += key
            return
        }
        keysByJournal[journal] = linkedSetOf(key)
        host.registerTopology(journal) {
            synchronized(lock) {
                keysByJournal[journal].orEmpty().map { TopoEvent.FamilyKey(namespace, render(it)) }
            }
        }
    }

    private fun forgetUnrecorded(key: K) {
        known.remove(key)
        keysByJournal.values.forEach { it.remove(key) }
    }

    companion object {
        /** The host WAL a family recovers from inside its journal dir. */
        const val HOST_JOURNAL = "host.journal"

        /**
         * The host write-ahead journal a [KeyedCells] on the same [journalDir]
         * recovers from — build the [ManagedHost]'s `journal` from this so the
         * WAL [recover] replays is the same file the host wrote. `null` =
         * ephemeral (no WAL).
         */
        fun hostJournal(journalDir: File?): Journal? =
            journalDir?.let { FileJournal(File(it, HOST_JOURNAL)) }
    }
}
