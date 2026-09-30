package civictech.deliberate

import civictech.cell.CellRef
import civictech.cell.durability.Journal
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.inlet
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.cell.wire.WireSerializers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Where the engine's per-claim metadata survives a restart (SPEC §11
 * DUR-02): per key, a last-writer-wins map of named fields. The engine writes
 * only the fields that changed ([DeliberationEngine.persistNow]) and reads
 * every record back once, at construction.
 */
interface MetaStore {
    /** Every record written so far, by key: its fields, last write per field wins. */
    fun load(): Map<String, Map<String, String>>

    /** Sets the non-null [fields] of record [key] and removes the null ones. */
    fun put(key: String, fields: Map<String, String?>)
}

/** Folds one field-level write into [records]. Shared by every store so they agree exactly. */
internal fun foldFields(
    records: Map<String, Map<String, String>>,
    key: String,
    fields: Map<String, String?>,
): Map<String, Map<String, String>> {
    val next = HashMap(records[key].orEmpty())
    fields.forEach { (f, v) -> if (v == null) next.remove(f) else next[f] = v }
    return records + (key to next)
}

/** A process-local [MetaStore] (tests; the volatile app needs none). */
class InMemoryMetaStore : MetaStore {
    private val records = ConcurrentHashMap<String, Map<String, String>>()
    override fun load(): Map<String, Map<String, String>> = HashMap(records)
    override fun put(key: String, fields: Map<String, String?>) {
        records.compute(key) { _, old -> foldFields(mapOf(key to old.orEmpty()), key, fields).getValue(key) }
    }
}

/**
 * One field-level metadata write, as it crosses the host journal: only the
 * fields of record [key] that changed; a null value removes the field.
 */
@Serializable
@SerialName("deliberate.MetaFields")
data class MetaDelta(val key: String, val fields: Map<String, String?>) : java.io.Serializable

/** Folds [MetaDelta]s into `key → fields`. */
class MetaView : View<MetaDelta, Map<String, Map<String, String>>> {
    @Volatile
    private var records: Map<String, Map<String, String>> = emptyMap()

    override fun apply(delta: MetaDelta): Boolean {
        val next = foldFields(records, delta.key, delta.fields)
        val changed = next[delta.key] != records[delta.key]
        records = next
        return changed
    }

    override fun current(): Map<String, Map<String, String>> = records

    override fun snapshot(): java.io.Serializable = HashMap(records.mapValues { HashMap(it.value) })

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: java.io.Serializable) {
        records = HashMap(state as Map<String, Map<String, String>>)
    }
}

/** Registers the deliberation's message types with the wire codec (journal and wire capable). */
class DeliberateWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(MetaDelta::class)
            subclass(Stance::class)
            subclass(Credence::class)
            subclass(Influence::class)
        }
    }
}

/**
 * The durable [MetaStore]: one hosted [ObserveCell] folding [MetaDelta]s. It
 * is the **only** journaled cell on the host (`DeliberateApp` selects the
 * journal per cell, `journalFor`): every [put] is an ordinary routed
 * invocation, journaled write-ahead before it is staged, and after a restart
 * `host.recoverFrom(journal)` re-delivers them into the fold. The credence
 * cells are volatile, so the journal never holds a derived frame — and the
 * kernel's replay re-journaling of re-emissions (computenet-vcrc7) has
 * nothing to re-journal: this cell emits nothing.
 *
 * Replay only *stages* frames, so a reader must wait for them to be folded:
 * the kernel's `Recovery.awaitApplied()` (fenced by `civictech.cell.host.Quiescence`)
 * blocks until every replayed frame and its same-host cascade has been
 * delivered — `DeliberateApp`'s init calls it right after `host.recoverFrom`,
 * before the startup checkpoint.
 */
class JournaledMetaStore(private val host: ManagedHost, registry: LocationRegistry) : MetaStore {
    /** Deterministic: journaled frames must find the same cell after a restart. */
    val cell = ObserveCell(MetaView(), ref = REF)

    init {
        host.managementInlet.call.spawn(cell)
    }

    private val inlet = registry.inlet<MetaDelta>(cell.ref, "inlet")

    /** Held by every write and by [checkpoint], so a checkpoint never races a write. */
    private val writeLock = Any()

    override fun put(key: String, fields: Map<String, String?>) {
        if (fields.isEmpty()) return
        synchronized(writeLock) { inlet.propagate(MetaDelta(key, fields)) }
    }

    // Legacy: journals written before the kernel fence landed hold a
    // `"fence"` record from the old poll-and-nonce protocol (removed below).
    // Cross-build replay is a supported path (spec 31 "Cross-build replay"),
    // so a fold that still carries one must keep filtering it out.
    override fun load(): Map<String, Map<String, String>> = cell.current() - LEGACY_FENCE

    /**
     * Compacts [journal] (which must be the journal this cell tees to) down to
     * one checkpoint of the fold. The kernel checkpoint itself carries every
     * frame still staged, so it needs no fence (safe at any inter-invocation
     * boundary — AgoraApp checkpoints on this basis). What still needs a
     * fence is this cell's own fold: the snapshot must reflect the journal's
     * frames, so writes are held off and [host]'s own quiescence is awaited
     * first — only then has every frame this cell holds been applied to its
     * fold. `checkpoint` itself does not fence (kernel design), so callers on
     * a live host must take this hold-off themselves.
     */
    fun checkpoint(journal: Journal, timeout: Duration = Duration.parse("30s")) = synchronized(writeLock) {
        host.quiescence().await(timeout.inWholeMilliseconds, "deliberate metadata checkpoint")
        host.checkpoint(journal)
    }

    companion object {
        val REF = CellRef(UUID.nameUUIDFromBytes("deliberate:meta".toByteArray()))
        private const val LEGACY_FENCE = "fence"
    }
}
