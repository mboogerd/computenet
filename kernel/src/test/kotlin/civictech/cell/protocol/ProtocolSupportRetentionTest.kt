package civictech.cell.protocol

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.link.Link
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * **computenet-7iyy: `ProtocolSupport.registries` held its own `WeakHashMap`
 * key through the handler closures its callers supply, so any port that ever
 * acquired a handler — and the cell that port belongs to — was immortal.**
 *
 * `ProtocolSupport.registries` is a JVM-global `WeakHashMap<Port,
 * ProtocolSupport>`. A `WeakHashMap` reclaims an entry only when its *key*
 * stops being strongly reachable, and it holds its *values* strongly — so any
 * strong path value → key makes the entry, and the key, immortal. PN-9 already
 * removed the two edges this class owns itself (the constructor's `port`
 * parameter is not stored; `ownerRef` is a `WeakReference`). What remained is
 * the one it does *not* own: `handlers`/`relays` hold caller-supplied closures,
 * and a closure routinely captures the very port it was registered on. The live
 * instance is `FanInlet.onEdgeEvent`:
 *
 * ```
 * ProtocolSupport.of(this).handle(Protocols.TopologyOrder) { link, event -> ... }
 * ```
 *
 * whose lambda captures the inlet, closing
 * `registries[inlet] → ProtocolSupport → handlers → lambda → inlet` through the
 * map's strong value edge. `unbind` (called by
 * `ManagedHost.unbindPortsRecursively` on despawn) was the only thing that
 * reclaimed it, and it is unreachable for a port whose owner is dropped without
 * a despawn — the same shape computenet-w5sm fixed for `PortRegistry` and
 * computenet-3u6x for `AttentionSupport`.
 *
 * ## The fix these arms pin
 *
 * **The map is an index of last resort, not the owner.** A port that carries a
 * [ProtocolAnchored] slot — every hosted-cell port: `FanInlet`, `FanOutlet`,
 * `FeedbackInlet` — holds its own `ProtocolSupport` in a field and never enters
 * `registries` at all. The support then lives exactly as long as the port, the
 * port exactly as long as its owner (the `registerPort` contract computenet-w5sm
 * already rests on), and no global root holds the handler closures.
 *
 * This is deliberately **not** computenet-w5sm's resolution. That one could
 * weaken the map's values because the owner anchors its ports; here there is no
 * such anchor — nothing but this map held a port's `ProtocolSupport` — so a bare
 * `WeakReference` value would let a live port's handlers silently vanish at the
 * next collection. The anchor had to be created before the global strong
 * reference could go, which is what [a live port's protocol handlers survive
 * collection] checks by execution.
 *
 * ## What each arm decides
 *
 * - [a dropped cell whose inlet handled an edge event IS collectable] — the
 *   defect, by execution, through the bead's own named call site
 *   (`FanInlet.onEdgeEvent`). **This arm failed before the fix** with
 *   `Expected null but actual was ...SelfServingCell@...`.
 * - [a dropped cell whose inlet never acquired protocol support IS collectable]
 *   — the control that makes the mechanism specific. The identical cell shape,
 *   minus the single `onEdgeEvent` call, was already collectable after
 *   computenet-w5sm; so anything the arm above observes is attributable to that
 *   call and to nothing else.
 * - [a live port's protocol handlers survive collection] — the anchor invariant.
 *   The failure mode the fix buys is handlers disappearing from under a live
 *   port; a live owner's support must be the same instance, still handling, and
 *   still delivering, after collection.
 * - [unbinding an owner still drops its protocol handlers] — eager teardown is
 *   unchanged. `ManagedHost.unbindPortsRecursively` calls `unbind` on despawn and
 *   must still clear the anchored slot, not just the (now unused) map entry.
 * - [a port with no anchor slot still gets stable protocol support] — the
 *   fallback. Ports outside the hosted-cell classes (`Use.fixed` endpoints,
 *   test doubles) keep exactly today's behaviour: one stable support per port,
 *   from the global map.
 *
 * Every builder below is a **separate method** returning only a
 * [WeakReference], and must stay one. A named local in a test method's own
 * frame — including inside an inlined `run { }` — keeps its object reachable
 * for the whole method and turns a collectability assertion into a spurious
 * failure (and, if the assertion were inverted, into a silent pass).
 *
 * Deliberately **not** claimed here: any share of `WsAnnouncementStressTest`'s
 * retention. computenet-7iyy measured that harness *after* computenet-w5sm —
 * 20000 iterations at `heapRetained=0.5%`, and a JFR `path-to-gc-roots` dump at
 * iteration 5000 with 0 of 219 sampled live objects rooted anywhere in
 * `civictech` — so this is a latent hazard on a path the harness exercises via
 * despawn, not an observed leak, and it is fixed on its own evidence.
 */
class ProtocolSupportRetentionTest {

    /**
     * The ordinary shape (from `PortRegistryRetentionTest`): the served
     * implementation reads [seen], a property of the cell, so the inlet carries
     * a reference back to the cell. That edge is what turns "the inlet is
     * pinned" into "the whole cell is pinned".
     */
    class SelfServingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val seen = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    seen += input
                }
            })
        }
    }

    @Test
    fun `a dropped cell whose inlet handled an edge event IS collectable`() {
        val cell = droppedSelfServing(observeEdges = true)
        collect()
        cell.get() shouldBe null
    }

    @Test
    fun `a dropped cell whose inlet never acquired protocol support IS collectable`() {
        val cell = droppedSelfServing(observeEdges = false)
        collect()
        cell.get() shouldBe null
    }

    /**
     * The anchor invariant. `cell` is a named local on purpose — the owner's
     * strong reference to its port is exactly what is under test here, and this
     * is the one arm in the file where a frame local is correct rather than a
     * bug.
     *
     * The support, by contrast, is held only through a [WeakReference] minted in
     * another frame, and that is what gives the arm its teeth: while the arm
     * held a strong `support` local it passed against the pre-fix code, against
     * the fix, *and* against the weak-value shape alike, so it decided nothing
     * (measured in review, 2026-08-14). Held weakly it fails under the weak-value
     * shape — `handles` comes back false, because the support was reachable only
     * from the map and nothing re-registers: `FanInlet.onEdgeEvent` installs its
     * `TopologyOrder` handler only while `edgeObservers` is still empty.
     */
    @Test
    fun `a live port's protocol handlers survive collection`() {
        val cell = SelfServingCell()
        val edges = mutableListOf<EdgeEvent>()
        cell.inlet.onEdgeEvent { _, event -> edges += event }
        val support = weakSupportOf(cell.inlet)

        collect()

        ProtocolSupport.of(cell.inlet).handles(Protocols.TopologyOrder) shouldBe true
        ProtocolSupport.of(cell.inlet) shouldBeSameInstanceAs support.get()
        ProtocolSupport.of(cell.inlet).deliver(Protocols.TopologyOrder, fakeLink(cell.inlet.ref), EdgeOpen)
        edges shouldBe listOf(EdgeOpen)
    }

    @Test
    fun `unbinding an owner still drops its protocol handlers`() {
        val cell = SelfServingCell()
        cell.inlet.onEdgeEvent { _, _ -> }
        ProtocolSupport.of(cell.inlet).handles(Protocols.TopologyOrder) shouldBe true

        ProtocolSupport.unbind(cell)

        ProtocolSupport.of(cell.inlet).handles(Protocols.TopologyOrder) shouldBe false
    }

    @Test
    fun `a port with no anchor slot still gets stable protocol support`() {
        val port: Use<Consumer<String>> = Use.fixed(object : Consumer<String> {
            override fun provide(input: String) = Unit
        })
        val support = ProtocolSupport.of(port)
        support.handle(Protocols.TopologyOrder) { _, _ -> }

        ProtocolSupport.of(port) shouldBeSameInstanceAs support
        ProtocolSupport.of(port).handles(Protocols.TopologyOrder) shouldBe true
    }

    /**
     * Builds a [SelfServingCell], optionally makes its inlet acquire protocol
     * support the way production does (`FanInlet.onEdgeEvent`, whose handler
     * closure captures the inlet), and returns only a [WeakReference] — so no
     * strong reference to the cell outlives this call frame. The observer itself
     * captures nothing, so the only new edge is the one inside `FanInlet`.
     */
    private fun droppedSelfServing(observeEdges: Boolean): WeakReference<SelfServingCell> {
        val cell = SelfServingCell()
        if (observeEdges) cell.inlet.onEdgeEvent { _, _ -> }
        return WeakReference(cell)
    }

    /**
     * Acquires [port]'s support in a frame of its own and returns only a
     * [WeakReference] to it — see the arm above for why holding it strongly
     * disarms that arm.
     */
    private fun weakSupportOf(port: civictech.cell.port.Port): WeakReference<ProtocolSupport> =
        WeakReference(ProtocolSupport.of(port))

    private fun fakeLink(to: PortRef): Link = object : Link {
        override val id: UUID = UUID.randomUUID()
        override val from: PortRef = PortRef.generate()
        override val to: PortRef = to
        override fun unlink() {}
    }

    /**
     * Force enough collection that a weakly-reachable object is cleared.
     * Several rounds with allocation between them rather than a single
     * `System.gc()`: one call is a hint, and a weak referent can survive a
     * young collection.
     */
    private fun collect() {
        repeat(8) {
            System.gc()
            @Suppress("UNUSED_EXPRESSION")
            ByteArray(1 shl 20)
        }
        System.gc()
    }
}
