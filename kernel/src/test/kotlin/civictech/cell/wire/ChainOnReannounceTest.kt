package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanOutlet
import civictech.cell.port.streamTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T07 finding 2: [Peering.chainOnReannounce] promotes the app-side re-announce
 * chaining idiom — `registry.onPublish { ref -> chained[ref]?.let { (cell,
 * link) -> cell.outlet.linking.fireLinked(link) } }` — that `demo/shopping`
 * and `demo/exchange` each hand-rolled (with the identical PN-9 full-multicast
 * fix landing at both call sites plus `Replication.maybeLink`, per the DRY
 * audit's "the comments are the drift evidence"). This is the kernel-level
 * regression test that now stands in for that three-site comment chain: the
 * NEXT catch-up-on-reannounce change breaks this test, not two demo Main.kts.
 */
class ChainOnReannounceTest {

    @Test
    fun `a chained ref's announce re-fires the full on-link catch-up, every time it announces`() {
        val registry = LocationRegistry()
        val outlet = FanOutlet.create<Propagate<String>>()
        var catchUps = 0
        // the single onLinked slot AND the PN-9 multicast both count — chainOnReannounce
        // must re-fire fireLinked, not merely the singular onLinked hook
        outlet.linking.onLinked = { catchUps++ }
        outlet.linking.onLinkedListeners += { catchUps++ }

        val consumer = object : Propagate<String> { override fun propagate(value: String) {} }
        val link = outlet.streamTo(consumer) // fires once at install (PN-9)
        catchUps shouldBe 2 // onLinked + the multicast listener, both fired at install

        val chainedRef = CellRef(UUID.randomUUID())
        Peering.chainOnReannounce(registry, mapOf(chainedRef to (outlet to link)))
        catchUps shouldBe 2 // wiring the chain alone fires nothing

        val host = ManagedHost(registry = registry)

        // the peer's initial announce
        registry.publish(chainedRef, host)
        catchUps shouldBe 4 // both hooks re-fired

        // a LATE / returning peer re-announce (M10.1 anti-entropy) — re-fires again
        registry.publish(chainedRef, host)
        catchUps shouldBe 6

        // a ref NOT in the chained map never fires anything
        registry.publish(CellRef(UUID.randomUUID()), host)
        catchUps shouldBe 6
    }
}
