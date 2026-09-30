package civictech.cell.link

import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.proxy.InvocationSink
import civictech.cell.wire.PortAddress
import civictech.cell.wire.bridgeFrom
import civictech.cell.wire.bridgeTo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-5nw9 — the supersession predicate for BRIDGED links, pinned.
 *
 * The primary handshake overload evicts a prior record on the same
 * `(from, to, role)` triple (computenet-lioe, `LinkSupersessionTest`) because it
 * mints the record and so owns that identity. The bridged overload
 * deliberately does not, and these cases pin WHY rather than merely that it
 * doesn't: the counterpart of a bridged edge has no local port, so
 * `bridgeTo`/`bridgeFrom` mint a fresh random surrogate `PortRef` for it per
 * call. The triple is a **per-call nonce** on this path, which makes an
 * eviction keyed on it structurally unreachable — not a wrong fix, an
 * unreachable one.
 *
 * That per-call nonce is why two bridges to two DIFFERENT remote addresses
 * remain fan-out, not corpses: each record carries its own surrogate
 * endpoint and its own `sink`, so N records relay to N distinct
 * destinations. `civictech.cell.wire.ProtocolBridgeTest` pins that live
 * case (one outlet bridged to two remote consumers, both delivering);
 * collapsing those would be a bug.
 *
 * The identity that IS stable across calls is the `PortAddress` pair
 * itself, and computenet-gyvli.5 (closing computenet-1mbp) wired
 * supersession on exactly that: bridging the SAME address pair twice now
 * closes the first record (`EdgeClose` observed) before the second
 * registers, so a re-bridge over one pair relays to that destination once,
 * not twice. `civictech.cell.wire.BridgedReconnectSupersessionTest` pins
 * that behavior directly; the two address-pair cases below now assert its
 * outcome (only the SECOND record survives) rather than the pre-gyvli.5
 * "both survive" reading.
 */
class BridgedLinkSupersessionTest {

    private fun addr(port: String) = PortAddress(CellRef(UUID.randomUUID()), port)

    private val nullSink = InvocationSink { }

    @Test
    fun `bridging one outlet to the same remote address twice mints two distinct triples`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val selfAddr = addr("outlet")
        val toAddr = addr("inlet")

        val a = (outlet.bridgeTo(selfAddr = selfAddr, toAddr = toAddr, sink = nullSink)
            as LinkResult.Connected).link
        val b = (outlet.bridgeTo(selfAddr = selfAddr, toAddr = toAddr, sink = nullSink)
            as LinkResult.Connected).link

        // Same producer endpoint, same target CELL — but the consumer-side ref is
        // a locally minted surrogate (`PortRef.generate(toAddr.cell)`), fresh per
        // call. This is the whole reason the triple cannot be a supersession key.
        a.from shouldBe b.from
        a.role shouldBe b.role
        a.to.cell shouldBe b.to.cell
        a.to shouldNotBe b.to

        // Consequently no record matches the other's eviction predicate: the
        // primary path's `evictSuperseded(support, from, to, role)` filter would
        // select nothing here, for either record.
        outlet.linking.links.count { it.from == b.from && it.to == b.to && it.role == b.role } shouldBe 1

        // The (from, to, role) triple can't supersede — but computenet-gyvli.5
        // wired supersession on the PortAddress pair itself (computenet-1mbp):
        // the second bridgeTo over the SAME (selfAddr, toAddr) closes `a`
        // (EdgeClose observed) before `b` registers, so only `b` survives.
        outlet.linking.links shouldContainExactly listOf(b)
    }

    @Test
    fun `the consumer half mints its surrogate producer ref per call in the same way`() {
        val inlet = FanInlet.create<Consumer<String>>()
        val selfAddr = addr("inlet")
        val fromAddr = addr("outlet")

        val a = (inlet.bridgeFrom(selfAddr = selfAddr, fromAddr = fromAddr, sink = nullSink)
            as LinkResult.Connected).link
        val b = (inlet.bridgeFrom(selfAddr = selfAddr, fromAddr = fromAddr, sink = nullSink)
            as LinkResult.Connected).link

        a.to shouldBe b.to               // the local inlet's own ref, stable
        a.from.cell shouldBe b.from.cell // same remote cell
        a.from shouldNotBe b.from        // surrogate, minted per call

        // Same address-pair supersession as the bridgeTo case above: the
        // second bridgeFrom over the SAME (fromAddr, selfAddr) closes `a`
        // before `b` registers, so only `b` survives.
        inlet.linking.links shouldContainExactly listOf(b)
    }

    /**
     * computenet-4jpd fixed `evictSuperseded` failing to fire `onUnlinkListeners`.
     * The bridged overload cannot carry that defect (it runs no eviction), but the
     * teardown it *does* wire is asserted here rather than read: an ordinary
     * `unlink` on a bridged record removes it AND multicasts to the listeners.
     */
    @Test
    fun `unlinking a bridged record removes it and fires the unlink listeners`() {
        val outlet = FanOutlet.create<Consumer<String>>()
        val kept = (outlet.bridgeTo(selfAddr = addr("outlet"), toAddr = addr("inlet"), sink = nullSink)
            as LinkResult.Connected).link
        val doomed = (outlet.bridgeTo(selfAddr = addr("outlet"), toAddr = addr("inlet"), sink = nullSink)
            as LinkResult.Connected).link

        val notified = mutableListOf<Link>()
        outlet.linking.onUnlinkListeners += { notified += it }

        doomed.unlink()

        notified shouldContainExactly listOf(doomed)
        outlet.linking.links shouldContainExactly listOf(kept)
    }
}
