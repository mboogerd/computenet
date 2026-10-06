package civictech.demo.social

import civictech.testkit.HttpProbe
import civictech.testkit.awaitSseData
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * computenet-l3msn: the startup `/state` broadcast count of an app with a
     * preloaded source is a small constant, not a function of how many sinks
     * the source created. `start()` fences the load and
     * [SocialGraph.onChange]'s bulk attach drops each preloaded sink's
     * late-join catch-up, so structurally the count is 0 — nothing writes
     * after the fence. The bound is [STARTUP_BROADCAST_BOUND] rather than 0
     * only to leave room for a stray fold; it is independent of N, which is
     * what the two scales below pin (roughly 297 and 1060 sinks). The
     * 1uf0s-era `1..100` at 0.05 alone went red on CI at 101-233 (runs
     * 36302838435, 36302865720, 36309096662): coalescing a spread-out
     * catch-up burst only divided N by a load-dependent factor.
     */
    private fun assertStartupBroadcastsBounded(scale: Double) {
        val app = SocialApp(port = 0, source = SnbGenerator(42, scale)).start()
        try {
            val settled = awaitStable(quietMs = 500) { app.broadcastCount.get() }
            println("computenet-l3msn: startup broadcasts at scale $scale: $settled")
            assertTrue(
                settled <= STARTUP_BROADCAST_BOUND,
                "expected at most $STARTUP_BROADCAST_BOUND startup broadcasts at scale $scale " +
                    "(independent of the preloaded sink count), got $settled",
            )
        } finally {
            app.stop()
        }
    }

    @Test
    fun `computenet-l3msn startup broadcasts are a small constant at SnbGenerator 0_05`() =
        assertStartupBroadcastsBounded(0.05)

    @Test
    fun `computenet-l3msn startup broadcasts are a small constant at SnbGenerator 0_2`() =
        assertStartupBroadcastsBounded(0.2)

    /**
     * computenet-l3msn: skipping catch-ups is confined to the sinks that
     * existed when `start()` attached. A person created after `start()` gets
     * its sink through [SocialGraph]'s forward attach, and its creating write
     * still produces a broadcast — the one an SSE client needs to see the
     * new person.
     */
    @Test
    fun `computenet-l3msn a sink created after start still broadcasts its creating write`() {
        val app = SocialApp(port = 0, source = SnbGenerator(42, 0.05)).start()
        try {
            val before = awaitStable(quietMs = 500) { app.broadcastCount.get() }
            val newId = app.graph.personIds().last() + 1
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            assertEquals(200, probe.post("action=person&id=$newId&firstName=Late&lastName=Comer"))
            awaitUntil("a broadcast for the new person's creating write", timeoutMs = 10_000) { app.broadcastCount.get() > before }
            assertTrue(newId in app.graph.personIds(), "the new person $newId is admitted")
        } finally {
            app.stop()
        }
    }

    /**
     * computenet-l3msn: a throw inside a broadcast (here from the frame
     * computation, via the test-only [SocialApp.frameFault]) must not wedge
     * the single-flight worker. Before the `finally` reset,
     * `broadcastInFlight` stayed true after the throw and every later
     * [SocialApp.broadcast] returned early, so the count never moved again.
     */
    @Test
    fun `computenet-l3msn a throwing broadcast does not stop later frames`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            app.frameFault = { throw IllegalStateException("computenet-l3msn injected frame fault") }
            assertEquals(200, probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace"))
            awaitUntil("the faulted broadcast attempt", timeoutMs = 10_000) { app.broadcastCount.get() >= 1 }
            val afterFault = awaitStable(quietMs = 500) { app.broadcastCount.get() }
            app.frameFault = null

            assertEquals(200, probe.post("action=person&id=2&firstName=Bob&lastName=Brown"))
            awaitUntil("a broadcast after the fault cleared", timeoutMs = 10_000) { app.broadcastCount.get() > afterFault }
            assertTrue(""""id":2,""" in probe.await { """"id":2,""" in it }, "state carries person 2")
        } finally {
            app.stop()
        }
    }

    private companion object {
        /** See [assertStartupBroadcastsBounded]: structurally 0; a constant, never a fraction of N. */
        const val STARTUP_BROADCAST_BOUND = 2L
    }

    @Test
    fun `static dimensions are one observation partitioned into four independent groups`() {
        val app = SocialApp(port = 0)
        try {
            // Exercise the multi-group coordinator dispatcher as well as each
            // aligned group's dispatcher; stop() must await both layers.
            app.staticObservation.onChange { }
            assertEquals(
                linkedMapOf(
                    "tags" to "tags",
                    "tagClasses" to "tagClasses",
                    "places" to "places",
                    "organisations" to "organisations",
                ),
                app.staticObservation.current().groupOf,
            )
            assertFailsWith<IllegalStateException> {
                app.staticObservation.awaitTermination(0)
            }
        } finally {
            app.stop()
        }
        assertTrue(app.staticObservation.awaitTermination(0))
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
    // threads BY NAME rather than a process-wide COUNT. `aligned-observe-`
    // names embed a per-instance UUID, so the exact set this app minted can be
    // captured at mint time and diffed against later, independent of any
    // unrelated observation thread that happens to be alive in the same JVM
    // (another test class's dispatcher still winding down) — a false
    // positive/negative the old raw-count comparison could not tell apart
    // from a real leak.

    /** Live threads belonging to the observation API's group/coordinator dispatchers. */
    private fun observationThreadNames(): Set<String> {
        val threads = arrayOfNulls<Thread>(Thread.activeCount() * 2 + 64)
        val n = Thread.enumerate(threads)
        return threads.take(n).mapNotNull { it?.name }
            .filter { it.startsWith("aligned-observe-") || it.startsWith("observation-") }
            .toSet()
    }

    @Test
    fun `stop releases every observe-cell dispatcher thread a started app minted`() {
        val before = observationThreadNames()

        val app = SocialApp(port = 0, source = SnbGenerator(42, 0.05)).start()
        awaitUntil("app to mint at least one observation dispatcher thread") {
            (observationThreadNames() - before).isNotEmpty()
        }
        // The exact set of threads THIS app minted, named at the moment of
        // minting — not touched again, so a sibling test minting its own
        // (differently-UUID-named) dispatcher afterward cannot inflate it.
        val minted = observationThreadNames() - before

        app.stop()

        // computenet-cpybp: stop() itself awaits every dispatcher it caused
        // (bounded by SocialApp's STOP_DISPATCHER_BOUND_MS; it throws naming
        // the survivors when that bound is exceeded), so the check is made at
        // the instant stop() returns — no grace period. A grace period is what
        // let this test stay green with the await removed: unawaited, the
        // dispatchers usually do die within a second, just not before stop()
        // returns.
        val survivors = observationThreadNames().intersect(minted)

        assertTrue(
            survivors.isEmpty(),
            "observation dispatcher thread(s) minted by this app (${survivors.size} of ${minted.size}) " +
                "were still alive when stop() returned: $survivors",
        )
    }

    @Test
    fun `a per-key sink admitted after graph close is terminally closed at creation`() {
        val app = SocialApp(port = 0)
        try {
            val before = observationThreadNames()
            // Make a late sink eligible for listener attachment, then close
            // before any per-key sink exists: the creation path, not close's
            // existing-sink iteration, must enforce terminal shutdown.
            app.graph.onChange { }
            app.graph.close()

            app.graph.addPerson(Person(1, "Late", "Admission"))

            assertTrue(
                app.graph.awaitDispatchers(0).isEmpty(),
                "a sink created after graph close must already be terminally closed",
            )
            assertTrue(
                (observationThreadNames() - before).isEmpty(),
                "a sink admitted after close must not mint an observation dispatcher",
            )
        } finally {
            app.stop()
        }
    }
}
