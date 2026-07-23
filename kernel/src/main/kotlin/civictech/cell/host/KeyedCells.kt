package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import java.io.File
import java.io.FileOutputStream
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
 * - **a durable record of live keys** — an append-on-first-spawn side log
 *   (the generalized `users.txt`), fsync'd so a crash mid-history keeps it;
 * - **the one correct [recover] ordering** — pre-spawn every known key BEFORE
 *   [ManagedHost.recoverFrom] replays their journaled ops. Replayed ops must
 *   find their cells already live, or a re-minted tag resurrects a removed
 *   element and replay silently diverges (M10.1). Getting this order wrong is
 *   the hazard this class exists to remove from callers.
 *
 * [journalDir] `null` = ephemeral: no key log, no host WAL, zero files touched;
 * [getOrSpawn]/[keys] still work in memory and [recover] is a no-op.
 *
 * Non-`String` keys supply [render]/[parse] so a key round-trips through its
 * durable line and its ref seed; the default is `String` identity — the demo's
 * case. Rendered keys must be single-line.
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

    /** Every key ever spawned, durable across restarts (was `users.txt`). Seeded from disk. */
    private val known = mutableSetOf<K>()

    /** Append-on-first-spawn key log inside [journalDir]; null = ephemeral. */
    private val keysFile = journalDir?.let { File(it, KEYS_FILE) }

    init {
        // Seed the durable key set so keys() answers and dedup holds before recover().
        keysFile?.takeIf { it.exists() }
            ?.readLines()?.filter { it.isNotBlank() }
            ?.forEach { known += parse(it) }
    }

    /**
     * The cell for [key], spawning it on first touch. Idempotent: a live key
     * returns the same cell — no second spawn (so the live-ref guard never
     * fires) and no second durable record.
     */
    fun getOrSpawn(key: K): Cell = synchronized(lock) {
        live[key]?.let { return it }
        val ref = refFor(key)
        val cell = factory(key, ref)
        host.managementInlet.call.spawn(cell)
        live[key] = cell
        // record durably only the first time this key is ever seen (append-on-first-spawn)
        if (known.add(key)) appendKey(key)
        cell
    }

    /**
     * Restore the family after a crash, in the one order that keeps replay
     * convergent: pre-spawn every durably-known key FIRST — so the host's
     * journal replays each op through the same cell, re-minting the same tags
     * — and only THEN [ManagedHost.recoverFrom] the host WAL. Never the reverse:
     * replaying onto an un-spawned family dead-letters (or, with a fresh ref,
     * resurrects removed elements) and diverges (M10.1/M10.4).
     */
    fun recover() {
        synchronized(lock) { known.toList() }.forEach { getOrSpawn(it) }
        hostJournal(journalDir)?.let { host.recoverFrom(it) }
    }

    /** Every key ever spawned by this family — durable, so complete after [recover]. */
    fun keys(): Set<K> = synchronized(lock) { known.toSet() }

    /** Deterministic, restart-stable ref: `nameUUIDFromBytes("$namespace:$key")` (instanceId 0). */
    private fun refFor(key: K): CellRef =
        CellRef(UUID.nameUUIDFromBytes("$namespace:${render(key)}".toByteArray()))

    /** fsync the new key so it survives a crash the moment its cell is admitted. */
    private fun appendKey(key: K) {
        val file = keysFile ?: return
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write((render(key) + "\n").toByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    companion object {
        /** The per-key durable record inside a family's journal dir. */
        const val KEYS_FILE = "keys"

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
