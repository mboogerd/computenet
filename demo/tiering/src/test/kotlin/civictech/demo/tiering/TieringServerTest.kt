package civictech.demo.tiering

import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TieringServerTest {

    @Test
    fun `valuations and preferences fuse into the board and re-tier on change`() {
        val app = TieringApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            probe.post("action=item&name=pizza")
            probe.post("action=item&name=sushi")

            // an unrated item sits in the unrated bucket
            var json = probe.await { """"unrated":["pizza","sushi"]""" in it }
            assertTrue(""""unrated":["pizza","sushi"]""" in json, "new items should be unrated: $json")

            // two S valuations put pizza in S (tierAvg 6 → score 1.0)
            probe.post("action=tier&agent=ada&item=pizza&tier=S")
            probe.post("action=tier&agent=bo&item=pizza&tier=S")
            // gate on the asserted state, not a prefix of it (computenet-i6vx): the
            // board's tier buckets and their scores are independent folds behind
            // `observeAll`, so `"S":[{"item":"pizza"` matches ada's valuation alone —
            // one element short of the two-valuation score this asserts.
            json = probe.await { """"S":[{"item":"pizza","score":1.0000}]""" in it }
            assertTrue(""""S":[{"item":"pizza","score":1.0000}]""" in json, "pizza should be tier S: $json")

            // a pairwise vote alone tiers sushi (pref-only signal: (1+1)/2 = 1.0 → S)
            // and drags pizza down (blend 0.7·1.0 + 0.3·0.0 = 0.7 → A)
            probe.post("action=pref&agent=cy&winner=sushi&loser=pizza")
            json = probe.await {
                """"item":"sushi","score":1.0000""" in it && """"A":[{"item":"pizza","score":0.7000}]""" in it
            }
            assertTrue(""""A":[{"item":"pizza","score":0.7000}]""" in json, "the lost pairwise should demote pizza: $json")

            // re-tiering replaces, never duplicates: ada drops pizza to C (score 3)
            // → tierAvg (6+3)/2 = 4.5 → 0.75 → blend 0.7·0.75 = 0.525 → tier C
            probe.post("action=tier&agent=ada&item=pizza&tier=C")
            json = probe.await { """"C":[{"item":"pizza","score":0.5250}]""" in it }
            assertTrue(""""C":[{"item":"pizza","score":0.5250}]""" in json, "re-tier should replace ada's S: $json")

            // full retraction: remove the pref → sushi has no signal left → unrated
            probe.post("action=unpref&agent=cy&winner=sushi&loser=pizza")
            json = probe.await { """"unrated":["sushi"]""" in it }
            assertTrue(""""unrated":["sushi"]""" in json, "sushi should fall back to unrated: $json")

            // boundary validation
            assertEquals(400, probe.post("action=tier&agent=ada&item=pizza&tier=Z"))
            assertEquals(400, probe.post("action=pref&agent=ada&winner=pizza&loser=pizza"))
            assertEquals(400, probe.post("action=item"))
        } finally {
            app.stop()
        }
    }
}
