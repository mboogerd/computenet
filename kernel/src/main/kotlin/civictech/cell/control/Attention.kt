package civictech.cell.control

import civictech.cell.link.Link
import civictech.cell.link.Linked
import civictech.cell.port.Port
import civictech.cell.port.PortRegistry
import civictech.cell.protocol.ProtocolSupport
import civictech.gen.wire.Contract
import civictech.gen.wire.Protocol
import civictech.nature.ProtocolDirection
import civictech.cell.protocol.Protocols
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow

/**
 * Attention protocol message (spec 34; 93 I-4 Candidate C, G-58 core): a raw
 * *current* level travels, not a delta; receivers quantize. [version] is a
 * per-emitter monotonic LWW discriminator (93 I-4 rule 2) — a payload field,
 * not a `MessageContext` wave — so a receiver applies an update iff its
 * version exceeds the stored one for that link ([AttentionFrontier]).
 * Defaulted for wire/source compatibility with pre-G-58 callers; a fresh
 * slot (no prior version) always accepts regardless of the value received.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("Attention")
data class Attention(val level: Float, val version: Long = 0L)

@Contract(management = true)
@Protocol("attention", ProtocolDirection.UPSTREAM, band = 0)
fun interface AttentionProtocol { fun attention(message: Attention) }

/**
 * Per-link LWW slot algebra (93 I-4 Candidate C, G-58 core): one level+version
 * slot per direct downstream link, keyed by link id ("`LinkId` is the local
 * identity of the link the update arrived on"). [onUpdate] is the idempotency
 * law — applies iff the incoming version is newer than the slot's stored one
 * (or no slot yet exists), so a duplicate/stale redelivery on one link is
 * absorbed while a genuinely later version supersedes; the fold over the
 * resulting slot multiset ([levels]) is therefore commutative and
 * associative, order-independent. [onUnlink] is retraction: slot removal —
 * "the attention frontier is the current downstream link set" — garbage
 * collecting the link's contribution; any subsequent in-flight update for a
 * removed link keys a fresh slot rather than resurrecting the retracted one.
 */
class AttentionFrontier {
    private data class Slot(val level: Float, val version: Long)

    private val slots = ConcurrentHashMap<UUID, Slot>()

    /** Current per-link levels for the aggregator's fold. */
    val levels: Collection<Float> get() = slots.values.map { it.level }

    /** Frontier membership: does this link currently hold a slot? */
    fun contains(link: UUID): Boolean = slots.containsKey(link)

    /**
     * LWW apply (93 I-4 rule 2): applies iff [version] is newer than the
     * slot's stored version, or no slot exists yet. Returns `true` iff the
     * slot changed, so callers only re-fold on a genuine change.
     */
    fun onUpdate(link: UUID, level: Float, version: Long): Boolean {
        var applied = false
        slots.compute(link) { _, existing ->
            if (existing == null || VersionMinter.isNewer(version, existing.version)) {
                applied = true
                Slot(level, version)
            } else existing
        }
        return applied
    }

    /** Retraction (93 I-4 rule 3): removes the slot. Returns `true` iff one existed. */
    fun onUnlink(link: UUID): Boolean = slots.remove(link) != null
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
         *
         * [cadenceTicks] is the decay cadence knob (G-58 core, 95 §R6 —
         * choosing a value is research; the knob itself is not): elapsed
         * ticks are floored to the nearest [cadenceTicks] boundary before the
         * half-life exponent is computed, so decay advances in discrete
         * steps rather than continuously with every `refresh` call. Default
         * `1` (advance every tick) preserves prior behavior exactly.
         */
        fun decay(halfLifeTicks: Long, cadenceTicks: Long = 1, base: AttentionAggregator = Max) =
            AttentionAggregator { own, down, ticks ->
                base.aggregate(own, down, ticks)?.let {
                    val steps = (ticks / cadenceTicks) * cadenceTicks
                    it * 0.5f.pow(steps.toFloat() / halfLifeTicks.toFloat())
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
class AttentionSupport private constructor(owner: Any) {

    /**
     * computenet-3u6x (leak fix): the owner is held **weakly**, and the
     * constructor parameter is deliberately not stored in a strong field.
     * [registries] is a `WeakHashMap` keyed on the owner; a `WeakHashMap`
     * reclaims an entry only when its key stops being strongly reachable and it
     * holds its values strongly, so `private val owner: Any` — a direct strong
     * path value → key — made **every** entry, and every cell
     * [AttentionSupport.of] was ever called on, immortal for the JVM lifetime
     * together with everything it reached. Unconditionally so: unlike
     * `PortRegistry.registries` (computenet-w5sm) and `ProtocolSupport.registries`
     * (PN-9), whose cycles need the owner's served implementation to capture the
     * owner, this one had no escape and no eviction path at all.
     *
     * A cleared referent means the cell is dead, so every read below bails and
     * the support goes inert — the same shape as `ProtocolSupport.ownerRef`
     * (PN-9). A live cell is always strongly held by its host, so nothing that
     * matters is lost.
     */
    private val ownerRef = java.lang.ref.WeakReference(owner)

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

    /**
     * Interest scatter (PN-19, spec 34 decisions 3/5; plan §3b (a)). The metadata
     * plane reuses the data plane's overlap rule: attention travels upstream only
     * to inbound links this predicate accepts; a rejected link instead receives an
     * explicit [AttentionBand.NONE] — "outside my interest scope", distinct from
     * "nobody said anything" (neutral NORMAL) — so an upstream instance whose
     * interest does not overlap the attending consumer's scope is left unattended
     * and parks like any cell (an unattended shard). The predicate is supplied by
     * the caller so the attention package stays free of a dependency on the
     * `link.Interest` algebra (which would cycle through `data`); a
     * consumer scatters by passing `{ link -> shardInterest(link).overlaps(scope) }`
     * — the same [civictech.cell.link.Interest.overlaps] the gossip linker
     * uses. Default accepts every link ⇒ byte-identical to pre-PN-19 broadcast, so
     * a non-scattering (non-opting) graph is unchanged. Assigning a scatter
     * immediately re-emits the current band under the new scope.
     */
    @Volatile
    var scatter: (Link) -> Boolean = { true }
        set(value) {
            field = value
            emitUpstream(band)
        }

    /** Step of the last signal (attend / link report / unlink), for time-aware aggregators. */
    @Volatile
    private var lastSignalTick: Long = 0L

    /** The cell's own declared interest (sinks: UIs, subscriptions, monitors). */
    @Volatile
    private var ownLevel: Float? = null

    /** Per-link LWW slot state (93 I-4 Candidate C, G-58 core): see [AttentionFrontier]. */
    private val frontier = AttentionFrontier()

    /** Mints this cell's own outgoing [Attention.version] sequence (G-58 core). */
    private val versionMinter = VersionMinter()

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
        val level = aggregator.aggregate(ownLevel, frontier.levels, ticks() - lastSignalTick)
        val newBand = level?.let(AttentionBand::quantize) ?: AttentionBand.NORMAL
        if (newBand == band) return // damping: intra-band jitter stops here
        band = newBand
        listeners.forEach { it(newBand) }
        emitUpstream(newBand)
    }

    /**
     * Push the current band up every inbound link (consumer → producer), minting a
     * fresh version. PN-19: a link the [scatter] predicate rejects receives an
     * explicit [AttentionBand.NONE] instead of [band] — the interest scatter, so an
     * out-of-scope upstream instance is left unattended (default accepts all ⇒
     * broadcast, unchanged).
     */
    private fun emitUpstream(band: AttentionBand) {
        forEachLinkedPort { port ->
            port.linking.links.forEach { link ->
                if (link.toPort === port) {
                    val effective = if (scatter(link)) band else AttentionBand.NONE
                    Protocols.sendUpstream(link, Protocols.Attention, Attention(effective.level, versionMinter.next()))
                }
            }
        }
    }

    private fun wire() {
        forEachLinkedPort { port ->
            // outlet face: downstream subscribers report their band here
            ProtocolSupport.of(port as Port).handle(Protocols.Attention) { link, message ->
                if (link.fromPort === port) {
                    val update = message as Attention
                    // idempotency law (93 I-4 rule 2): a duplicate/stale version is
                    // absorbed by the LWW slot and must not trigger a re-signal.
                    if (frontier.onUpdate(link.id, update.level, update.version)) signal()
                }
            }
            port.linking.onUnlinkListeners += { link ->
                // retraction (93 I-4 rule 3): slot removal GCs the link's contribution
                // and re-folds the remainder; inbound links leaving need no action here.
                if (link.fromPort === port && frontier.onUnlink(link.id)) signal()
            }
            // inlet face: a fresh inbound link learns our current band at once —
            // scattered (PN-19): a link outside the interest scope learns NONE.
            port.linking.onLinkedListeners += { link ->
                if (link.toPort === port) {
                    val effective = if (scatter(link)) band else AttentionBand.NONE
                    Protocols.sendUpstream(link, Protocols.Attention, Attention(effective.level, versionMinter.next()))
                }
            }
        }
    }

    /** No-op once the owner has been collected (computenet-3u6x): a dead cell has no ports to walk. */
    private inline fun forEachLinkedPort(action: (Linked) -> Unit) {
        val ports = PortRegistry.of(ownerRef.get() ?: return)
        ports.names().forEach { name ->
            (ports[name] as? Linked)?.let(action)
        }
    }

    companion object {
        // ponytail: JVM-global weak map, same pattern as PortRegistry.
        // Safe as a WeakHashMap only because the value holds its key weakly
        // (see [ownerRef]); a strong owner field made every entry immortal
        // (computenet-3u6x).
        private val registries = Collections.synchronizedMap(WeakHashMap<Any, AttentionSupport>())

        fun of(owner: Any): AttentionSupport =
            synchronized(registries) {
                registries.getOrPut(owner) { AttentionSupport(owner).also { it.wire() } }
            }

        /**
         * Drop [owner]'s entry (computenet-3u6x), the counterpart of
         * `PortRegistry.release` / `ProtocolSupport.unbind` that this map lacked
         * entirely; [civictech.cell.host.ManagedHost] calls it on despawn.
         *
         * Since [ownerRef] this is not the only thing standing between a dropped
         * cell and collection — a dropped owner is collectable whether or not
         * anyone calls this. It still earns its keep for a *hosted* cell, whose
         * entry can be reached from its own key by a listener registered
         * elsewhere: `ManagedHost` spawn installs an `onBandChange` listener
         * whose closure captures the cell, which is again a value → key path.
         * Evicting on despawn cuts it at the moment the host stops owning the
         * cell, instead of leaving it to the JVM lifetime.
         */
        internal fun release(owner: Any) {
            synchronized(registries) { registries.remove(owner) }
        }
    }
}
