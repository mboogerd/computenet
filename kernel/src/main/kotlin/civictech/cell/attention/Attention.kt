package civictech.cell.attention

import civictech.cell.port.Link
import civictech.cell.port.Linked
import civictech.cell.port.Port
import civictech.cell.port.PortRegistry
import civictech.cell.port.ProtocolSupport
import civictech.gen.wire.Contract
import civictech.gen.wire.Protocol
import civictech.gen.wire.ProtocolCardinality
import civictech.gen.wire.ProtocolDirection
import civictech.cell.port.Protocols
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow

/**
 * Attention protocol message (spec 34): a raw level travels; receivers
 * quantize. Kept a Float so a sum/load-signaling variant can be added
 * without a protocol change (34 decision 1).
 */
data class Attention(val level: Float)

@Contract(management = true)
@Protocol("attention", ProtocolDirection.UPSTREAM, band = 0, lane = "attention", cardinality = ProtocolCardinality.FAN_IN_MERGE)
fun interface AttentionProtocol { fun attention(message: Attention) }

/** Host notices about parked/replayed cells, traveling downstream (34 decision 3). */
sealed interface SuspensionNotice {
    data object Suspended : SuspensionNotice
    data object Resumed : SuspensionNotice
}

@Contract(management = true)
@Protocol("suspension", ProtocolDirection.DOWNSTREAM, band = 0, lane = "suspension", cardinality = ProtocolCardinality.FAN_OUT_BROADCAST)
fun interface SuspensionProtocol { fun suspension(message: SuspensionNotice) }

/**
 * Marker (spec 34 decision 3, session delta 3): a cell that must never be
 * attention-parked. Membership is contagious — one non-suspendable member
 * vetoes suspension for its whole glitch-free region.
 */
interface NonSuspendable

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
 * Aggregation strategy (spec 34 decision 1): folds a cell's own declared
 * level and its downstream links' levels into one; `null` = no signal at all
 * (the cell sits at neutral NORMAL). Programmable per cell via
 * [AttentionSupport.aggregator]; strategies compose (see [decay]).
 *
 * [ticksSinceSignal] is scheduling-step time supplied by the owner's host —
 * never wall time, so the deterministic simulation stays deterministic (P1).
 * With no host binding it is always 0 (time-independent strategies ignore it).
 */
fun interface AttentionAggregator {
    fun aggregate(own: Float?, downstream: Collection<Float>, ticksSinceSignal: Long): Float?

    companion object {
        /** Priority semantics (default): as important as the MOST interested consumer. */
        val Max = AttentionAggregator { own, down, _ ->
            (listOfNotNull(own) + down).maxOrNull()
        }

        /** Load semantics: total downstream interest (double-counts diamond fan-in by design). */
        val Sum = AttentionAggregator { own, down, _ ->
            (listOfNotNull(own) + down).takeIf { it.isNotEmpty() }?.sum()
        }

        /**
         * [base]'s result halves every [halfLifeTicks] without a fresh signal;
         * quantization still floors sub-band jitter. Re-evaluated on signals
         * and on explicit [AttentionSupport.refresh] — hosts/harnesses own the
         * refresh cadence (the dispatch hot path does not poll).
         */
        fun decay(halfLifeTicks: Long, base: AttentionAggregator = Max) =
            AttentionAggregator { own, down, ticks ->
                base.aggregate(own, down, ticks)?.let {
                    it * 0.5f.pow(ticks.toFloat() / halfLifeTicks.toFloat())
                }
            }
    }
}

/**
 * Per-cell attention state (spec 34, G-6): aggregates the levels reported by
 * downstream links together with the cell's own declared level (sinks call
 * [attend]) through the cell's [aggregator] (default [AttentionAggregator.Max]
 * — attention is a priority signal, not a load meter),
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

    /** Aggregation strategy; assigning one re-evaluates the band immediately. */
    @Volatile
    var aggregator: AttentionAggregator = AttentionAggregator.Max
        set(value) {
            field = value
            recompute()
        }

    /** Scheduling-step clock, bound by the hosting [civictech.cell.host.ManagedHost]; never wall time (P1). */
    @Volatile
    var ticks: () -> Long = { 0L }

    /** Step of the last signal (attend / link report / unlink), for time-aware aggregators. */
    @Volatile
    private var lastSignalTick: Long = 0L

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
        signal()
    }

    /**
     * Re-evaluate without a new signal — how time-aware aggregators (decay)
     * observe the clock advancing. No-op for time-independent strategies.
     */
    fun refresh() = recompute()

    /** A fresh signal arrived: stamp the clock, then re-evaluate. */
    private fun signal() {
        lastSignalTick = ticks()
        recompute()
    }

    private fun recompute() {
        val level = aggregator.aggregate(ownLevel, linkLevels.values, ticks() - lastSignalTick)
        val newBand = level?.let(AttentionBand::quantize) ?: AttentionBand.NORMAL
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
                    signal()
                }
            }
            port.linking.onUnlinkListeners += { link ->
                if (link.fromPort === port && linkLevels.remove(link.id) != null) signal()
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
