package civictech.cell.attention

import civictech.cell.port.Link
import civictech.cell.port.Linked
import civictech.cell.port.Port
import civictech.cell.port.PortRegistry
import civictech.cell.port.ProtocolSupport
import civictech.cell.port.Protocols
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Attention protocol message (spec 34): a raw level travels; receivers
 * quantize. Kept a Float so a sum/load-signaling variant can be added
 * without a protocol change (34 decision 1).
 */
data class Attention(val level: Float)

/** Host notices about parked/replayed cells, traveling downstream (34 decision 3). */
sealed interface SuspensionNotice {
    data object Suspended : SuspensionNotice
    data object Resumed : SuspensionNotice
}

/**
 * Quantized attention (spec 34 decision 1). Ordinal order is scheduling
 * order: NONE < LOW < NORMAL < HIGH. [level] is the representative value a
 * cell re-emits upstream, so damping composes across hops.
 */
enum class AttentionBand(val level: Float) {
    NONE(0f), LOW(0.25f), NORMAL(0.5f), HIGH(1f);

    companion object {
        fun quantize(level: Float): AttentionBand = when {
            level <= 0f -> NONE
            level < 0.4f -> LOW
            level < 0.75f -> NORMAL
            else -> HIGH
        }
    }
}

/**
 * Per-cell attention state (spec 34, G-6): aggregates the levels reported by
 * downstream links (**max** — attention is a priority signal, not a load
 * meter) together with the cell's own declared level (sinks call [attend]),
 * quantizes to an [AttentionBand], and re-emits upstream over the cell's
 * inbound links **only when the band changes** — quantization is the update
 * damping (34 decision 1), and it is also what terminates propagation around
 * cycles (a revisited cell's band is already set).
 *
 * A cell with no attention information at all sits at NORMAL (neutral):
 * "nobody said anything" is not the same as "somebody said zero" (NONE).
 *
 * Wiring is port-generic — handlers land on the cell's registered ports at
 * [of]-time (hosts call [of] at spawn), so no cell-specific logic is needed
 * (spec 34 "attention is a generic protocol").
 *
 * ponytail: state is thread-safe but recompute is not atomic — attention is
 * advisory scheduling metadata, and the deterministic simulation is
 * single-threaded; per-host serialization if production hosts ever contend.
 * ponytail: ports registered after [of] don't participate; all current cells
 * register ports at construction.
 */
class AttentionSupport private constructor(private val owner: Any) {

    /** The cell's own declared interest (sinks: UIs, subscriptions, monitors). */
    @Volatile
    private var ownLevel: Float? = null

    /** Latest level reported per downstream link (link id → level). */
    private val linkLevels = ConcurrentHashMap<UUID, Float>()

    private val listeners = CopyOnWriteArrayList<(AttentionBand) -> Unit>()

    @Volatile
    var band: AttentionBand = AttentionBand.NORMAL
        private set

    fun onBandChange(listener: (AttentionBand) -> Unit) {
        listeners += listener
    }

    /** Declare this cell's own interest level (a sink's entry point). */
    fun attend(level: Float) {
        ownLevel = level
        recompute()
    }

    private fun recompute() {
        val signals = listOfNotNull(ownLevel) + linkLevels.values
        val newBand =
            if (signals.isEmpty()) AttentionBand.NORMAL else AttentionBand.quantize(signals.max())
        if (newBand == band) return // damping: intra-band jitter stops here
        band = newBand
        listeners.forEach { it(newBand) }
        emitUpstream(newBand)
    }

    /** Push the current band up every inbound link (consumer → producer). */
    private fun emitUpstream(band: AttentionBand) {
        forEachLinkedPort { port ->
            port.linking.links.forEach { link ->
                if (link.toPort === port) {
                    Protocols.sendUpstream(link, Protocols.Attention, Attention(band.level))
                }
            }
        }
    }

    private fun wire() {
        forEachLinkedPort { port ->
            // outlet face: downstream subscribers report their band here
            ProtocolSupport.of(port as Port).handle(Protocols.Attention) { link, message ->
                if (link.fromPort === port) {
                    linkLevels[link.id] = (message as Attention).level
                    recompute()
                }
            }
            port.linking.onUnlinkListeners += { link ->
                if (link.fromPort === port && linkLevels.remove(link.id) != null) recompute()
                // inbound links leaving need no action: the upstream side drops its level
            }
            // inlet face: a fresh inbound link learns our current band at once
            port.linking.onLinkedListeners += { link ->
                if (link.toPort === port) {
                    Protocols.sendUpstream(link, Protocols.Attention, Attention(band.level))
                }
            }
        }
    }

    private inline fun forEachLinkedPort(action: (Linked) -> Unit) {
        val ports = PortRegistry.of(owner)
        ports.names().forEach { name ->
            (ports[name] as? Linked)?.let(action)
        }
    }

    companion object {
        // ponytail: JVM-global weak map, same pattern as PortRegistry
        private val registries = Collections.synchronizedMap(WeakHashMap<Any, AttentionSupport>())

        fun of(owner: Any): AttentionSupport =
            synchronized(registries) {
                registries.getOrPut(owner) { AttentionSupport(owner).also { it.wire() } }
            }
    }
}
