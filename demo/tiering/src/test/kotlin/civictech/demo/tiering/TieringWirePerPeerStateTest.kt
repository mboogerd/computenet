package civictech.demo.tiering

import civictech.testkit.HttpProbe
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Wire mode keeps each peer's own `items`/`vals`/`prefs` state its own
 * (computenet-3san, caught while reviewing that change).
 *
 * The three cells [TierPipeline.build] spawns at derived refs are NOT
 * [civictech.cell.replication.Replication]-replicated, but
 * `Peering.announceTo` announces *every* local ref its registry holds, and
 * `LocationRegistry.publish(ref, sink)` installs the announced `Remote`
 * unconditionally — it does not defer to an existing `Local`. Give both peers
 * the same instance id under [TierPipeline.ITEMS_ID] and they overwrite each
 * other's local location, after which every routed `item`/`tier`/`pref` write
 * leaves for the wire and is lost at the far end, which is holding that same
 * ref as `Remote` right back. Measured that way: neither peer's `/state`
 * ever showed an item it had posted itself.
 *
 * **Driving the manual lane both ways first is load-bearing, not scene
 * setting.** The overwrite only happens once a peer's announcement batch has
 * been applied; post immediately after `start()` and the still-`Local`
 * location wins the race, so the defect is invisible and this test passes on
 * the broken code. A manual pin crossing in each direction is the observable
 * proof that both sides' `announceTo` catch-ups — which carry the pipeline
 * refs in the same batch — have landed.
 *
 * The pre-existing wire tests cannot see any of this: [TieringServerTest]'s
 * wire case and the two-JVM suites drive only `retier`, whose OR-map already
 * carried a role-derived instance id.
 */
class TieringWirePerPeerStateTest {
    @Test
    fun `each peer keeps its own items, valuations and prefs`() {
        val listener = TieringApp(port = 0, wire = TieringApp.Wire.Listen(0)).start()
        var dialer: TieringApp? = null
        try {
            val wsPort = checkNotNull(listener.boundWsPort) { "a listening peer must have a bound ws port" }
            dialer = TieringApp(port = 0, wire = TieringApp.Wire.Dial("ws://localhost:$wsPort")).start()

            val listenerProbe = HttpProbe("http://localhost:${listener.boundPort}")
            val dialerProbe = HttpProbe("http://localhost:${dialer.boundPort}")

            // Settle the mesh in BOTH directions: a pin minted on one side and
            // observed on the other proves that side's announcement batch has
            // been applied by the observer.
            listenerProbe.post("action=retier&item=fromListener&tier=A")
            dialerProbe.await { """"fromListener":"A"""" in it }
            dialerProbe.post("action=retier&item=fromDialer&tier=C")
            listenerProbe.await { """"fromDialer":"C"""" in it }

            // Only now the routed pipeline lane, with disjoint payloads per side.
            listenerProbe.post("action=item&name=pizza")
            listenerProbe.post("action=tier&agent=ada&item=pizza&tier=S")
            dialerProbe.post("action=item&name=sushi")
            dialerProbe.post("action=pref&agent=cy&winner=sushi&loser=pizza")

            val lj = listenerProbe.await {
                """"items":["pizza"]""" in it && """{"agent":"ada","item":"pizza","tier":"S"}""" in it
            }
            assertTrue(""""items":["pizza"]""" in lj, "the listener must hold its own item: $lj")
            assertTrue(
                """{"agent":"ada","item":"pizza","tier":"S"}""" in lj,
                "the listener must hold its own valuation: $lj",
            )
            assertTrue(""""prefs":[]""" in lj, "and not the dialer's preference: $lj")

            val dj = dialerProbe.await {
                """"items":["sushi"]""" in it && """{"agent":"cy","winner":"sushi","loser":"pizza"}""" in it
            }
            assertTrue(""""items":["sushi"]""" in dj, "the dialer must hold its own item: $dj")
            assertTrue(
                """{"agent":"cy","winner":"sushi","loser":"pizza"}""" in dj,
                "the dialer must hold its own preference: $dj",
            )
            assertTrue(""""valuations":[]""" in dj, "and not the listener's valuation: $dj")
        } finally {
            dialer?.stop()
            listener.stop()
        }
    }
}
