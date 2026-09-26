package civictech.demo.social

import civictech.testkit.HttpProbe
import civictech.testkit.awaitSseData
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP-surface tests for [SocialApp] (feature `computenet-jo2jk`, task
 * `computenet-jo2jk.3`, design jo2jk-D9): one test per [SOC1-HTTP-01..04]
 * rule, plus the happy path pinning `/state`'s exact jo2jk-D6 shape.
 */
class SocialServerTest {

    // --- SOC1-HTTP-01 -----------------------------------------------------

    @Test
    fun `SOC1-HTTP-01 serves slash, state and op through DemoShell`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            val index = probe.get("/")
            assertEquals(200, index.statusCode())
            assertTrue("EventSource('/events')" in index.body(), "index should embed the SSE subscription: ${index.body()}")

            val state = probe.get("/state")
            assertEquals(200, state.statusCode())
            assertTrue(state.body().startsWith("{"), "/state should be JSON: ${state.body()}")

            val op = probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
            assertEquals(200, op)
        } finally {
            app.stop()
        }
    }

    // --- SOC1-HTTP-02 -----------------------------------------------------

    @Test
    fun `SOC1-HTTP-02 state is byte-identical across repeated fetches and across two apps given the same ops`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
            probe.post("action=person&id=2&firstName=Bob&lastName=Brown")
            probe.post("action=knows&a=1&b=2")
            val first = probe.await { """"knows":[2]""" in it }
            val second = probe.state()
            assertEquals(first, second, "/state fetched twice with no intervening op should be byte-identical")
        } finally {
            app.stop()
        }

        val appA = SocialApp(port = 0).start()
        val appB = SocialApp(port = 0).start()
        try {
            val probeA = HttpProbe("http://localhost:${appA.boundPort}")
            val probeB = HttpProbe("http://localhost:${appB.boundPort}")
            listOf(probeA, probeB).forEach { probe ->
                probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
                probe.post("action=person&id=2&firstName=Bob&lastName=Brown")
                probe.post("action=knows&a=1&b=2")
            }
            val stateA = probeA.await { """"knows":[2]""" in it }
            val stateB = probeB.await { """"knows":[2]""" in it }
            assertEquals(stateA, stateB, "two fresh apps given the same ops should agree on /state")
        } finally {
            appA.stop()
            appB.stop()
        }
    }

    // --- SOC1-HTTP-03 -----------------------------------------------------

    @Test
    fun `SOC1-HTTP-03 the first SSE frame carries current state before any change frame`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
            probe.post("action=person&id=2&firstName=Bob&lastName=Brown")
            probe.post("action=knows&a=1&b=2")
            probe.await { """"knows":[2]""" in it }

            val expected = probe.state()
            val frame = awaitSseData("http://localhost:${app.boundPort}/events")
            val payload = frame.removePrefix("data: ")
            assertEquals(expected, payload, "the first SSE frame should equal /state fetched with no op in flight")
        } finally {
            app.stop()
        }
    }

    // --- SOC1-HTTP-04 -----------------------------------------------------

    @Test
    fun `SOC1-HTTP-04 a missing param, non-numeric id, unknown id or unknown action is 400 and never mutates state`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")

            fun assertNoMutation(op: String) {
                val before = probe.state()
                assertEquals(400, probe.post(op), "expected 400 for '$op'")
                assertEquals(before, probe.state(), "/state must be unchanged after a rejected '$op'")
            }

            assertNoMutation("action=knows&a=1") // missing b
            assertNoMutation("action=knows&a=1&b=999") // unknown person 999
            assertNoMutation("action=knows&a=x&b=1") // non-numeric a
            assertNoMutation("action=nonsense") // unknown action
        } finally {
            app.stop()
        }
    }

    // --- computenet-1uf0s: startup broadcast is bounded, not per-sink ------

    /**
     * Polls [read] until it stops changing for [quietMs], or [timeoutMs]
     * elapses — used below because the late-join catch-up broadcasts
     * (computenet-1uf0s) land asynchronously, one per preloaded sink's own
     * dispatcher thread, so there is no single event to await; the count is
     * read as settled once it has been steady for a while.
     */
    private fun awaitStable(timeoutMs: Long = 15_000, quietMs: Long = 300, read: () -> Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = read()
        var lastChange = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            val now = read()
            if (now != last) {
                last = now
                lastChange = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - lastChange >= quietMs) {
                return last
            }
        }
        return last
    }

    @Test
    fun `computenet-1uf0s a preloaded source's late-join catch-up computes state a bounded number of times`() {
        // SnbGenerator(42, 0.05) mints roughly 297 preloaded sinks (per the
        // bead's probe): start()'s graph.onChange attaches to every one of
        // them, and each fires its own late-join catch-up on its own
        // dispatcher thread. Uncoalesced that is ~297 stateJson()
        // computations (confirmed by temporarily reverting the [broadcast]
        // fix while developing this test: it measured exactly 297). The
        // single-flight coalescing in [SocialApp.broadcast] does not reduce
        // that to a small constant — how many separate broadcasts a burst
        // this size produces depends on OS thread-scheduling jitter across
        // ~297 near-simultaneous dispatcher threads, observed between ~15
        // (isolated run) and ~36 (full-suite run, more contention). The
        // bound below is intentionally generous — well under half of the
        // sink count, and stable across repeated runs — to assert what the
        // acceptance criterion actually requires (not tied to N, nowhere
        // near one-broadcast-per-sink) without being sensitive to scheduler
        // noise.
        val app = SocialApp(port = 0, source = SnbGenerator(42, 0.05)).start()
        try {
            val settled = awaitStable { app.broadcastCount.get() }
            assertTrue(
                settled in 1..100,
                "expected a bounded number of startup broadcasts (independent of the ~297 sinks), got $settled",
            )
        } finally {
            app.stop()
        }
    }

    // --- SOC1-SCHEMA-02 (state half, pinned in jo2jk-D6) -------------------

    @Test
    fun `SOC1-SCHEMA-02 a big SNB id appears verbatim in state`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=person&id=8796093022390&firstName=Big&lastName=Id")
            val json = probe.await { """"id":8796093022390""" in it }
            assertTrue(""""id":8796093022390""" in json, "big SNB id should be preserved verbatim in /state: $json")
        } finally {
            app.stop()
        }
    }

    // --- happy path: /state's exact pinned shape (jo2jk-D6) ----------------

    @Test
    fun `happy path pins the exact state shape from jo2jk-D6`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
            probe.post("action=person&id=2&firstName=Bob&lastName=Brown")
            probe.post("action=knows&a=1&b=2")
            probe.post("action=forum&id=100&title=f&moderator=1")
            probe.post("action=join&person=1&forum=100")
            probe.post("action=post&id=10&author=1&forum=100&content=hi")
            probe.post("action=comment&id=11&author=2&replyOf=10&content=reply")
            probe.post("action=like&person=2&message=10")

            val json = probe.await {
                """"counts":{"persons":2,"knows":2,"forums":1,"messages":2,"likes":1,"tags":0}""" in it
            }

            assertTrue(""""knows":[2]""" in json, "person 1 should know person 2: $json")
            assertTrue(""""members":[1]""" in json, "forum 100 should have member 1: $json")
            assertTrue(""""contains":[10]""" in json, "forum 100 should contain message 10: $json")
            assertTrue(""""replies":[11]""" in json, "message 10 should have reply 11: $json")
            assertTrue(""""likes":[2]""" in json, "message 10 should be liked by person 2: $json")
        } finally {
            app.stop()
        }
    }

    // --- computenet-a77tu: stop() releases every observe-sink thread --------
    // computenet-f0v6m: rewritten to track this app's OWN minted dispatcher
    // threads BY NAME rather than a process-wide COUNT. `observe-cell-` names
    // embed a per-instance UUID (kernel/.../observe/Observe.kt:182,
    // ObserveCell.newDispatcher), so the exact set this app minted can be
    // captured at mint time and diffed against later, independent of any
    // unrelated `observe-cell-` thread that happens to be alive in the same
    // JVM (another test class's dispatcher still winding down) — a false
    // positive/negative the old raw-count comparison could not tell apart
    // from a real leak.

    /** Live threads whose name starts with `observe-cell-` (`ObserveCell`'s dispatcher naming). */
    private fun observeCellThreadNames(): Set<String> {
        val threads = arrayOfNulls<Thread>(Thread.activeCount() * 2 + 64)
        val n = Thread.enumerate(threads)
        return threads.take(n).mapNotNull { it?.name }.filter { it.startsWith("observe-cell-") }.toSet()
    }

    @Test
    fun `stop releases every observe-cell dispatcher thread a started app minted`() {
        val before = observeCellThreadNames()

        val app = SocialApp(port = 0, source = SnbGenerator(42, 0.05)).start()
        awaitUntil("app to mint at least one observe-cell dispatcher thread") {
            (observeCellThreadNames() - before).isNotEmpty()
        }
        // The exact set of threads THIS app minted, named at the moment of
        // minting — not touched again, so a sibling test minting its own
        // (differently-UUID-named) dispatcher afterward cannot inflate it.
        val minted = observeCellThreadNames() - before

        app.stop()

        // computenet-cpybp: stop() itself awaits every dispatcher it caused
        // (bounded by SocialApp's STOP_DISPATCHER_BOUND_MS; it throws naming
        // the survivors when that bound is exceeded), so the check is made at
        // the instant stop() returns — no grace period. A grace period is what
        // let this test stay green with the await removed: unawaited, the
        // dispatchers usually do die within a second, just not before stop()
        // returns.
        val survivors = observeCellThreadNames().intersect(minted)

        assertTrue(
            survivors.isEmpty(),
            "observe-cell dispatcher thread(s) minted by this app (${survivors.size} of ${minted.size}) " +
                "were still alive when stop() returned: $survivors",
        )
    }
}
