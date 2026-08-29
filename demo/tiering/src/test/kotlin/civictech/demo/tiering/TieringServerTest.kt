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
            // old gate `"S":[{"item":"pizza"` stopped short of the score, so any S-tier
            // pizza opened it whatever score the board carried. The gate now IS the
            // asserted string. Note this one was not itself flaky: `tierAvg` is a MEAN,
            // so ada alone already scores 6/6 = 1.0000 and the assertion holds before
            // bo's valuation lands (measured: deleting bo's post above still passes here
            // and first fails at the re-tier assert below). The tightening closes the
            // prefix/full-string gap, not a one-vs-two-valuation race.
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

    /**
     * The `retier` action over HTTP (feature computenet-j2x.5, task .1): a
     * manual pin overrides the board tier, `retier none` restores the computed
     * one, and `"manual"` is an *additive* `/state` field — every field the
     * test above asserts keeps its shape.
     */
    @Test
    fun `a manual re-tier overrides the board and none restores the computed tier`() {
        val app = TieringApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            probe.post("action=item&name=pizza")
            probe.post("action=tier&agent=ada&item=pizza&tier=S")
            var json = probe.await { """"S":[{"item":"pizza","score":1.0000}]""" in it }
            assertTrue(""""manual":{}""" in json, "no pin yet, so the manual map is empty: $json")

            // pin pizza to D: the board row moves, and the displayed score is
            // the pin's canonical score (2/6 = 0.3333), not the fused 1.0.
            probe.post("action=retier&item=pizza&tier=D")
            json = probe.await { """"D":[{"item":"pizza","score":0.3333}]""" in it }
            assertTrue(""""D":[{"item":"pizza","score":0.3333}]""" in json, "the pin should own the board row: $json")
            assertTrue(""""manual":{"pizza":"D"}""" in json, "the pin should show in the manual map: $json")
            // the SIGNALS table still reports the computed pipeline, untouched
            assertTrue(""""item":"pizza","tierAvg":6.0000""" in json, "signals must still read `fused`: $json")

            // release it: the computed tier comes back
            probe.post("action=retier&item=pizza&tier=none")
            json = probe.await { """"S":[{"item":"pizza","score":1.0000}]""" in it }
            assertTrue(""""manual":{}""" in json, "the released pin should leave the manual map empty: $json")

            assertEquals(400, probe.post("action=retier&item=pizza&tier=Z"))
            assertEquals(400, probe.post("action=retier&tier=S"))
        } finally {
            app.stop()
        }
    }

    /**
     * `--journal <dir>`: every routed op is write-ahead journalled and
     * `ManagedHost.recoverFrom` replays it into a freshly rebuilt graph.
     *
     * Single-host, so this proves the journal *lane* (routed write path,
     * `KeyedCells.hostJournal`, rebuild-then-recover ordering) and nothing
     * about dot re-minting across a mesh — that is [KE1-31]'s crash-restart
     * proof and belongs to a sibling two-JVM task.
     *
     * **Scope, and why it is this narrow — the MANUAL lane only.** Two
     * independent properties of this demo stop `--journal` covering the rest,
     * both stated in [TieringApp]'s KDoc and both measured 2026-08-29:
     * `tier`/`pref` payloads are unencodable by `WireCodec`, and every
     * pipeline cell except the manual OR-map is spawned at a *fresh random*
     * ref, so no journal record can find it after a restart. So this test
     * asserts the manual lane replays and asserts nothing whatever about the
     * items set or the signal lanes under a journal.
     */
    @Test
    fun `a journalled re-tier replays on restart`() {
        val dir = kotlin.io.path.createTempDirectory("tiering-journal").toFile()
        try {
            val first = TieringApp(port = 0, journalDir = dir).start()
            try {
                val probe = HttpProbe("http://localhost:${first.boundPort}")
                probe.post("action=item&name=pizza")
                probe.post("action=retier&item=pizza&tier=B")
                probe.await { """"manual":{"pizza":"B"}""" in it }
            } finally {
                first.stop()
            }

            // a brand-new process-equivalent over the same journal directory
            val second = TieringApp(port = 0, journalDir = dir).start()
            try {
                val probe = HttpProbe("http://localhost:${second.boundPort}")
                val json = probe.await { """"manual":{"pizza":"B"}""" in it }
                assertTrue(""""manual":{"pizza":"B"}""" in json, "the pin should replay: $json")
                assertTrue(""""B":[{"item":"pizza","score":0.6667}]""" in json, "and hold the board: $json")
                // `"items":[]` here is expected, not a bug in the replay: the
                // items SetCell is a graph-DSL cell at a fresh random ref.
                assertTrue(""""items":[]""" in json, "graph-DSL cells do not replay — see this test's KDoc: $json")
            } finally {
                second.stop()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Wire mode reaches the wire: two hosts in this JVM, bridged over a real
     * `:wire` socket, gossip the replicated manual OR-map.
     *
     * Deliberately **not** the E1.6 convergence proof — that is a two-JVM
     * `JvmPeer` test and belongs to a sibling task of feature computenet-j2x.5
     * (concurrent same-item re-tier, remove-vs-re-tier, crash replay, late
     * joiner, partition/heal). This is the far weaker smoke check that the
     * flag-driven mode this task adds — bridge host, `Peering.Side`,
     * `Replication.replicate` at a role-derived ref — is wired at all, so the
     * mode is not shipped entirely unexercised. One writer, one direction.
     */
    @Test
    fun `wire mode replicates a manual re-tier to the peer`() {
        val listener = TieringApp(port = 0, wire = TieringApp.Wire.Listen(0)).start()
        var dialer: TieringApp? = null
        try {
            val wsPort = checkNotNull(listener.boundWsPort) { "a listening peer must have a bound ws port" }
            dialer = TieringApp(port = 0, wire = TieringApp.Wire.Dial("ws://localhost:$wsPort")).start()

            // the two hosts hold distinct instances of ONE logical cell
            assertEquals(0L, listener.manualInstanceId)
            assertEquals(1L, dialer.manualInstanceId)

            val listenerProbe = HttpProbe("http://localhost:${listener.boundPort}")
            val dialerProbe = HttpProbe("http://localhost:${dialer.boundPort}")

            listenerProbe.post("action=item&name=pizza")
            dialerProbe.post("action=item&name=pizza")
            listenerProbe.post("action=retier&item=pizza&tier=B")

            val json = dialerProbe.await { """"manual":{"pizza":"B"}""" in it }
            assertTrue(""""manual":{"pizza":"B"}""" in json, "the pin should reach the peer: $json")
            assertTrue(""""B":[{"item":"pizza","score":0.6667}]""" in json, "and land on its board: $json")
        } finally {
            dialer?.stop()
            listener.stop()
        }
    }
}
