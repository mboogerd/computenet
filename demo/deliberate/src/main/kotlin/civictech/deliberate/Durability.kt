package civictech.deliberate

import civictech.cell.CellRef
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
 * DUR-02): a last-writer-wins map of string records. The engine writes whole
 * records ([DeliberationEngine.persistNow]) and reads them all back once, at
 * construction.
 */
interface MetaStore {
    /** Every record written so far, by key, last write wins. */
    fun load(): Map<String, String>

    fun put(key: String, value: String)
}

/** A process-local [MetaStore] (tests; the volatile app needs none). */
class InMemoryMetaStore : MetaStore {
    private val records = ConcurrentHashMap<String, String>()
    override fun load(): Map<String, String> = HashMap(records)
    override fun put(key: String, value: String) {
        records[key] = value
    }
}

/** One engine metadata record, as it crosses the host journal. */
@Serializable
@SerialName("deliberate.MetaDelta")
data class MetaDelta(val key: String, val value: String) : java.io.Serializable

/** Folds [MetaDelta]s into a last-writer-wins `key → value` map. */
class MetaView : View<MetaDelta, Map<String, String>> {
    @Volatile
    private var records: Map<String, String> = emptyMap()

    override fun apply(delta: MetaDelta): Boolean {
        val changed = records[delta.key] != delta.value
        if (changed) records = records + (delta.key to delta.value)
        return changed
    }

    override fun current(): Map<String, String> = records

    override fun snapshot(): java.io.Serializable = HashMap(records)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: java.io.Serializable) {
        records = HashMap(state as Map<String, String>)
    }
}

/** Registers [MetaDelta] with the wire codec, so the host journal can encode it. */
class DeliberateWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(MetaDelta::class)
        }
    }
}

/**
 * The durable [MetaStore]: one hosted [ObserveCell] folding [MetaDelta]s, on
 * the same host — and so behind the same host journal — as the agora layers.
 * Every [put] is an ordinary routed invocation, journaled write-ahead before
 * it is staged; after a restart, `host.recoverFrom(journal)` re-delivers them
 * and the fold holds the last value of every key again. One mechanism, the
 * kernel's, covers the credence graph and the engine's metadata alike.
 *
 * Replay only *stages* frames, so a reader must wait for them to be folded:
 * [awaitReplayed] routes a fresh fence record after `recoverFrom` returned
 * and waits until the fold holds it. The cell is FIFO per inlet, so by then
 * every replayed record before the fence has been applied.
 */
class JournaledMetaStore(host: ManagedHost, registry: LocationRegistry) : MetaStore {
    /** Deterministic, like agora's hub: journaled frames must find the same cell after a restart. */
    val cell = ObserveCell(MetaView(), ref = CellRef(UUID.nameUUIDFromBytes("deliberate:meta".toByteArray())))

    init {
        host.managementInlet.call.spawn(cell)
    }

    private val inlet = registry.inlet<MetaDelta>(cell.ref, "inlet")

    override fun put(key: String, value: String) = inlet.propagate(MetaDelta(key, value))

    override fun load(): Map<String, String> = cell.current() - FENCE

    /** Blocks until every record replayed before this call has been folded. */
    fun awaitReplayed(timeout: Duration) {
        val nonce = UUID.randomUUID().toString()
        put(FENCE, nonce)
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (cell.current()[FENCE] != nonce) {
            check(System.nanoTime() < deadline) { "deliberate: metadata replay did not finish within $timeout" }
            Thread.sleep(5)
        }
    }

    private companion object {
        const val FENCE = "fence"
    }
}
