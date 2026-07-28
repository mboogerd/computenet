package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.host.LocationRegistry
import civictech.cell.host.TopologyLink
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The topology-facing half of the flow feed, so [InspectorModel] can record and
 * retract edges without knowing how they are observed. [FlowCollector] is the
 * only real implementation; [None] is what a model constructed without a
 * collector (M0/M1/M2 tests) uses, and it reproduces M0's behaviour exactly —
 * every edge's `fused` stays the contract's `null`.
 */
internal interface FlowBinding {

    /**
     * Start observing [link]'s traffic, and answer the contract's `Edge.fused`
     * for it: `false` when the producing endpoint is a tappable outlet, `true`
     * when it is a locally hosted port with no emission point of its own, and
     * `null` when this inspector cannot tell (a non-local or free-standing
     * producer). Idempotent per `link.id`.
     */
    fun bind(link: TopologyLink): Boolean?

    /** Stop observing the edge [id] — an unlink. Idempotent. */
    fun unbind(id: UUID)

    /** Stop observing every edge produced by [ref] — a despawn. */
    fun dropCell(ref: CellRef)

    companion object {
        val None: FlowBinding = object : FlowBinding {
            override fun bind(link: TopologyLink): Boolean? = null
            override fun unbind(id: UUID) = Unit
            override fun dropCell(ref: CellRef) = Unit
        }
    }
}

/**
 * M3 — the flow feed (`doc/spec/90-roadmap/97-inspector-plan/tickets/M3-BE.md`):
 * per-edge message rates, sampled at the one seam built for exactly this,
 * [FanOutlet.tap] (spec 20/23 §Taps, G-47).
 *
 * ### Why a tap, and why at the outlet
 *
 * A tap is the Observe-role attachment: uncounted by the SPSC funnel, never an
 * expected sibling of a wave-completeness set
 * ([civictech.cell.consistency.WaveFrontier] filters
 * [civictech.cell.link.LinkRole.Consume]), always admitted, and fired *before*
 * the outlet's consumers. Attaching a `Propagate` **consumer** instead — the
 * only other way to see traffic from outside the graph — would be a semantic
 * intrusion on three counts at once: it would be refused outright on an
 * `Owned`/`Leased` contract (SPSC), it would enter every downstream join's
 * completeness condition, and it would take delivery of payloads the inspector
 * has no business touching. The tap does none of that: the handler below never
 * looks at its arguments, so payloads stay `Borrowed` in the strictest sense —
 * unread, uncopied, unretained (10-target-v3 §Constraints 3).
 *
 * The outlet is also the only point that sees *all* of an edge's traffic. Spec
 * 20/21 §Fusion: co-hosted chains run as nested direct calls, so a same-host
 * edge never passes through `ManagedHost.enqueueHostedInvocation` at all —
 * measured, not assumed: a two-cell co-hosted `SetCell → SetCell` graph driven
 * with five `add`s produces five host-queue entries, all of them the *ingress*
 * to the first cell's inlet, and zero for the edge between the two. The tap
 * fires on the emission itself, upstream of the decision to call directly or to
 * enqueue, so it sees fused and cross-host edges identically (P2's promise runs
 * the other way: observation costs the same either way and adds no hop).
 *
 * ### Attribution
 *
 * A tap fires once per *emission*, not once per consumer, and a [FanOutlet]
 * broadcasts: every one of an outlet's consume links carries every emission.
 * So an outlet's emission count is each of its outgoing edges' message count —
 * duplicated across them, never divided. Rates are therefore exact per edge for
 * the broadcast fan-out the kernel implements, and the only traffic they omit
 * is what genuinely never reaches a link's consumer: [FanOutlet.at] deliveries
 * (the `onLinked` catch-up baseline and pull replies, which bypass taps by
 * construction) and emissions a `disclosureFilter` suppresses.
 *
 * ### Cost on the graph thread
 *
 * Per message per tapped outlet, the handler does one reference comparison
 * (screening `Object`'s own methods), one `AtomicLong.incrementAndGet`, one
 * thread-local read ([CurrentContext.get]) and one volatile reference store.
 * No allocation, no map lookup, no lock, and nothing touching the payload. The
 * recorded [MessageContext] is the wave-plane object the outlet had already
 * built — carrying only a timestamp, a port ref and a hop count — so keeping
 * the last one retains nothing of the message.
 *
 * The aggregation half never runs on a graph thread: [sample] is driven by the
 * inspector's own single scheduler thread, and the batch it produces is handed
 * to the SSE broadcaster's per-client bounded queues, which drop frames rather
 * than wait (10-target-v3 §Constraints 6 — viz never blocks the graph).
 */
internal class FlowCollector(
    private val registry: LocationRegistry,
    /** Non-blocking sink for one aggregated window. */
    private val onBatch: (FlowBatch) -> Unit,
    /** The contract's `flow.rates.window`, in milliseconds. */
    private val windowMs: Long = WINDOW_MS,
) : FlowBinding, AutoCloseable {

    private val lock = Any()

    /** Live edges, by id — the mapping from a contract `Edge.id` to its producing outlet. */
    private val edges = LinkedHashMap<UUID, PortRef>()

    /** One tap per producing outlet, shared by every edge that outlet feeds. */
    private val sites = LinkedHashMap<PortRef, TapSite>()

    /** Did the last published window have anything tapped? Drives the trailing all-quiet batch. */
    private var everSampled = false

    override fun bind(link: TopologyLink): Boolean? {
        val producer = link.from
        val cell = producer.cell ?: return null
        // A producer this registry does not host locally cannot be reached as
        // an object at all (a mirrored/announced edge, spec 42) — the contract's
        // "null when unknown", never a guess.
        val host = registry.locate(cell) ?: return null
        val outlet = host.outletAt(producer)
            // Locally hosted, but the endpoint is not an emission point: the
            // delivery is a delegating pass-through (spec 20/21 §Fusion, 10/14
            // flattening), so this edge carries no observable message. Fused,
            // and deliberately never a zero rate — the two say different things.
            ?: return true

        synchronized(lock) {
            if (link.id in edges) return false
            edges[link.id] = producer
            sites.getOrPut(producer) { TapSite(outlet) }.edges += link.id
        }
        return false
    }

    override fun unbind(id: UUID) {
        val orphaned = synchronized(lock) {
            val producer = edges.remove(id) ?: return
            val site = sites[producer] ?: return
            site.edges -= id
            if (site.edges.isNotEmpty()) return
            sites.remove(producer)
            site
        }
        orphaned.detach()
    }

    override fun dropCell(ref: CellRef) {
        // Despawn does not unlink (`ManagedHost.despawn` unpublishes and
        // deactivates; only an explicit `Link.unlink` retracts the edge), so the
        // untap has to be driven from the unpublish hook as well.
        val ids = synchronized(lock) { edges.filterValues { it.cell == ref }.keys.toList() }
        ids.forEach(::unbind)
    }

    /**
     * One window: snapshot and reset every site's counter, and publish a
     * [FlowBatch] of the edges that carried traffic (the contract omits rate-0
     * edges). Runs on the inspector's scheduler thread.
     *
     * Cadence: a window publishes whenever anything is tapped, even when
     * nothing moved — the client's decay rule keys on *received* windows, so a
     * quiet second must read as "quiet", not as "the feed stopped". Once the
     * last tap is gone one trailing empty window says so, and the feed then
     * falls silent rather than emitting an empty batch every second forever.
     */
    fun sample() {
        val batch = synchronized(lock) {
            if (sites.isEmpty() && !everSampled) return
            everSampled = sites.isNotEmpty()
            val perSecond = 1000.0 / windowMs
            val rows = ArrayList<FlowEdgeRate>()
            sites.values.forEach { site ->
                val count = site.drain()
                if (count == 0L) return@forEach
                val context = site.lastContext
                val rate = count * perSecond
                site.edges.forEach { id ->
                    rows += FlowEdgeRate(
                        id = id.toString(),
                        rate = rate,
                        lastWave = context?.timestamp?.let { WaveStamp(it.sourceId.toString(), it.counter) },
                        hop = context?.hop,
                    )
                }
            }
            FlowBatch(window = windowMs, edges = rows)
        }
        onBatch(batch)
    }

    /** Untaps everything. A stopped inspector leaves no handler attached to a live graph. */
    override fun close() {
        val all = synchronized(lock) {
            val snapshot = sites.values.toList()
            sites.clear()
            edges.clear()
            // a closed collector is silent, not one trailing batch short of it
            everSampled = false
            snapshot
        }
        all.forEach { it.detach() }
    }

    /** Tapped outlets — diagnostics and tests. */
    internal val tappedOutlets: Set<PortRef> get() = synchronized(lock) { sites.keys.toSet() }

    /**
     * One outlet's tap and its window counter. The counter and [lastContext]
     * are the only state a graph thread touches; [edges] is written under
     * [FlowCollector.lock] and read from the sampling thread inside that same
     * monitor, never from the tap handler.
     */
    private class TapSite(source: FanOutlet<*>) {
        val edges = LinkedHashSet<UUID>()

        private val count = AtomicLong()

        /**
         * The wave the last observed emission carried. A plain volatile
         * reference: one store per message, no compare-and-set, and a reader
         * that loses a race merely reports the previous window's wave — a
         * label on a rate, never a number anyone sums.
         */
        @Volatile
        var lastContext: MessageContext? = null
            private set

        private val tapRef = PortRef.generate()

        /**
         * The tapped outlet, erased so [FanOutlet.tap]/[FanOutlet.untap] can be
         * called on it. The handler is a proxy of the outlet's *own* contract,
         * so every method of it lands on [observe] — an outlet is not
         * necessarily a `Propagate`.
         *
         * `Use.fixed` is not [civictech.cell.link.Linked], so [FanOutlet.tap]
         * takes its unnegotiated path and always answers
         * [civictech.cell.link.LinkResult.Connected] — the same shape
         * [Errors]'s dead-letter tap relies on. It also means installing this
         * tap fires no `onLinked` hook, so it triggers no catch-up push and
         * records nothing in the topology index: the inspector's own
         * attachments never appear as edges it then reports.
         */
        @Suppress("UNCHECKED_CAST")
        private val tapped: FanOutlet<Any> = (source as FanOutlet<Any>).also { outlet ->
            val handler = Proxy.fromClass<Any>(outlet.clazz) { proxy, method, args ->
                if (method.declaringClass === OBJECT) objectMethod(proxy, method, args) else observe()
            }
            outlet.tap(Use.fixed(handler, tapRef))
        }

        /** The whole per-message cost: one atomic add, one thread-local read, one volatile store. */
        private fun observe(): Any? {
            count.incrementAndGet()
            lastContext = CurrentContext.get()
            return null
        }

        fun drain(): Long = count.getAndSet(0)

        fun detach() = tapped.untap(tapRef)

        private companion object {
            val OBJECT: Class<*> = Any::class.java

            /**
             * `equals`/`hashCode`/`toString` are not messages. A dynamic proxy
             * forwards them here too, and answering `null` to `hashCode` would
             * throw on unboxing — so they are screened off the counting path
             * rather than counted or crashed on.
             */
            fun objectMethod(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "inspector-flow-tap"
                    else -> null
                }
        }
    }

    internal companion object {
        /** Contract §SSE `flow.rates`: `"window": 1000` — one batch per second. */
        const val WINDOW_MS = 1000L
    }
}
