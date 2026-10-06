package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.TopoEvent
import civictech.cell.link.Interest
import java.io.File
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture

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
 * - **lazy [getOrSpawn] / [spawnAsync]** — spawn on first touch, and thereafter
 *   idempotent: a live key returns the same cell without re-spawning, while
 *   concurrent touches join one pending spawn. The family lock is released
 *   before [getOrSpawn] waits on the host, so no normal spawn holds it across a
 *   host wait. The `Exact` live-ref spawn guard
 *   ([civictech.cell.graph.IdentityBinding.Exact], G-51) is unreachable through
 *   these APIs;
 * - **journal-native durable membership** — the first touch records a
 *   [TopoEvent.FamilyKey] in the key cell's own selected journal before the
 *   cell is spawned. Recovery therefore encounters the key before any of its
 *   frames and spawns it under the deterministic ref in journal order;
 * - **optional interest-driven membership** — with [spawnOnInterest], every
 *   bounded [Interest.Ranges] admitted by this host's registry asynchronously
 *   materializes its keys. This path records membership after the budgeted
 *   spawn succeeds, inside that same management task. A host-admitted cell
 *   whose membership append then fails is retained as a volatile live member
 *   while the admission future reports the append failure: it is not
 *   recoverable after restart, but retries join it instead of spawning its
 *   deterministic ref twice. A spawn refusal still owns neither an in-memory
 *   key nor a durable key record;
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
    private val spawnOnInterest: Boolean = false,
) {
    private val lock = Any()

    /** Cells spawned this session, by key — the in-memory index (was the `writers` map). */
    private val live = mutableMapOf<K, Cell>()

    /** One shared completion for every key whose cell has been prepared but not yet admitted. */
    private val pending = mutableMapOf<K, CompletableFuture<Cell>>()

    /** Every key ever spawned, populated in journal order during recovery. */
    private val known = mutableSetOf<K>()

    /** Keys whose topology provider belongs to each selected journal (journal identity is semantic). */
    private val keysByJournal = IdentityHashMap<Journal, LinkedHashSet<K>>()

    /** Kept for the family's lifetime so the registry subscription can be detached by a future lifecycle owner. */
    private val interestSubscription: AutoCloseable?

    init {
        val registry = if (spawnOnInterest) {
            host.interestRegistry()
                ?: throw IllegalStateException("family '$namespace': spawnOnInterest needs a host with a registry")
        } else {
            null
        }
        host.registerFamily(namespace, this)
        interestSubscription = registry?.onInterest { _, interest -> spawnForInterest(interest) }
    }

    /**
     * The cell for [key], spawning it on first touch. Idempotent: a live key
     * returns the same cell — no second spawn (so the live-ref guard never
     * fires) and no second durable record.
     */
    fun getOrSpawn(key: K): Cell {
        synchronized(lock) {
            live[key]?.let { return it }
        }
        return host.awaitManagement(spawnAsync(key))
    }

    /**
     * Start the cell for [key] without waiting on the host. Concurrent callers
     * share the same future, including a factory that re-enters for its own key.
     */
    fun spawnAsync(key: K): CompletableFuture<Cell> {
        return spawn(key, recordAfterSpawn = false)
    }

    /** Spawn one key from an admitted interest, recording membership only after host admission succeeds. */
    private fun spawnForInterest(key: K): CompletableFuture<Cell> =
        spawn(key, recordAfterSpawn = true)

    /** Translate one bounded interest into the keys this family owns. */
    private fun spawnForInterest(interest: Interest): CompletableFuture<Set<CellRef>> =
        try {
            when (interest) {
                Interest.Empty -> CompletableFuture.completedFuture(emptySet())
                is Interest.Ranges -> {
                    val keys = linkedSetOf<K>()
                    interest.ranges.forEach { range ->
                        for (value in range.lo until range.hi) keys += parse(value.toString())
                    }
                    val spawns = keys.map(::spawnForInterest)
                    if (spawns.isEmpty()) {
                        CompletableFuture.completedFuture(emptySet())
                    } else {
                        CompletableFuture.allOf(*spawns.toTypedArray()).thenApply {
                            spawns.mapTo(linkedSetOf()) { it.join().ref }
                        }
                    }
                }
                else -> CompletableFuture.failedFuture(
                    InterestSpawnRefused(namespace, interest.javaClass.simpleName),
                )
            }
        } catch (failure: Exception) {
            CompletableFuture.failedFuture(failure)
        }

    private fun spawn(key: K, recordAfterSpawn: Boolean): CompletableFuture<Cell> {
        lateinit var result: CompletableFuture<Cell>
        var cell: Cell? = null
        var fresh = false
        var recorded = false
        var hostAdmitted = false
        var startFailure: Throwable? = null
        var hostSpawn: CompletableFuture<CellRef>? = null

        synchronized(lock) {
            live[key]?.let { return CompletableFuture.completedFuture(it) }
            pending[key]?.let { return it }

            result = CompletableFuture()
            pending[key] = result
            try {
                val prepared = factory(key, refFor(key))
                cell = prepared
                if (recordAfterSpawn) {
                    fresh = key !in known
                    hostSpawn = host.spawnAsync(prepared) {
                        synchronized(lock) {
                            hostAdmitted = true
                            if (fresh && known.add(key)) {
                                try {
                                    recorded = host.recordTopology(
                                        prepared.ref,
                                        TopoEvent.FamilyKey(namespace, render(key)),
                                        prepared,
                                    ) { journal -> rememberForCheckpoint(journal, key) } != null
                                } catch (failure: Throwable) {
                                    forgetUnrecorded(key)
                                    throw failure
                                }
                            }
                        }
                    }
                } else {
                    fresh = known.add(key)
                    if (fresh) {
                        recorded = host.recordTopology(
                            prepared.ref,
                            TopoEvent.FamilyKey(namespace, render(key)),
                            prepared,
                        ) { journal -> rememberForCheckpoint(journal, key) } != null
                    }
                    hostSpawn = host.spawnAsync(prepared)
                }
            } catch (failure: Throwable) {
                pending.remove(key)
                if (fresh && !recorded) forgetUnrecorded(key)
                startFailure = failure
            }
        }

        startFailure?.let {
            result.completeExceptionally(it)
            return result
        }

        val prepared = checkNotNull(cell)
        checkNotNull(hostSpawn).whenComplete { _, failure ->
            synchronized(lock) {
                pending.remove(key)
                if (failure == null) {
                    live[key] = prepared
                } else if (recordAfterSpawn && hostAdmitted) {
                    // Host admission cannot be rolled back here. Keep the cell as a
                    // volatile family member so its deterministic ref stays reachable;
                    // the append failure still completes the admission exceptionally.
                    known.add(key)
                    live[key] = prepared
                } else if (fresh && !recorded) {
                    // A successful write-ahead record owns the key even if the following
                    // spawn fails: recovery must retry it. A volatile key owns nothing.
                    forgetUnrecorded(key)
                }
            }
            if (failure == null) {
                result.complete(prepared)
            } else {
                result.completeExceptionally(failure)
            }
        }
        return result
    }

    /**
     * Recovery deliberately remains synchronous so each key precedes its frames.
     * It reserves [pending] before the factory, as [spawn] does, so a factory
     * whose interest declaration names its own key joins this recovery rather
     * than spawning the same ref a second time; and it waits on the host
     * outside [lock], because a spawn completion takes that lock on the host
     * thread.
     */
    private fun recoverSynchronously(key: K): Cell {
        val result = CompletableFuture<Cell>()
        var joined: CompletableFuture<Cell>? = null
        var prepared: Cell? = null
        synchronized(lock) {
            live[key]?.let { return it }
            joined = pending[key]
            if (joined == null) {
                pending[key] = result
                try {
                    val cell = factory(key, refFor(key))
                    if (known.add(key)) {
                        host.topologyJournal(cell.ref, cell)?.let { rememberForCheckpoint(it, key) }
                    }
                    prepared = cell
                } catch (failure: Throwable) {
                    pending.remove(key)
                    result.completeExceptionally(failure)
                    throw failure
                }
            }
        }
        // A spawn already in flight for this key owns it; recovery joins it.
        joined?.let { return host.awaitManagement(it) }
        val cell = checkNotNull(prepared)
        try {
            host.managementInlet.call.spawn(cell)
        } catch (failure: Throwable) {
            synchronized(lock) { pending.remove(key) }
            result.completeExceptionally(failure)
            throw failure
        }
        synchronized(lock) {
            live[key] = cell
            pending.remove(key)
        }
        result.complete(cell)
        return cell
    }

    /** Decode the journal representation through this family's codec before spawning. */
    internal fun recoverKey(rendered: String): Cell = recoverSynchronously(parse(rendered))

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
