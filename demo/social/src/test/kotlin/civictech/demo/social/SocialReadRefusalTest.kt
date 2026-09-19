package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.TagFrontier
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.testkit.HttpProbe
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `[SOC1-SREAD-04]` (feature `computenet-rx8om` design rx8om-D1/rx8om-D8, task
 * `computenet-rx8om.2`): a host refusal on any short-read hop is surfaced
 * verbatim, over HTTP as 503 with the reason name, and over the [ShortReads]
 * API as [ReadOutcome.Refused] — never substituted with an empty or cached
 * answer, and never contagious to a ref the reader was not told to refuse.
 */
class SocialReadRefusalTest {

    /**
     * Wraps a [BoundedReader], answering [StateReadResult.Unavailable] for any
     * ref present in [refused] and delegating otherwise. [refused] is a live
     * map — a test adds a ref to it *after* the owning app has spawned that
     * ref's cell, since the ref cannot be known before creation
     * (rx8om-D5: `getOrSpawn` is the only public key->ref path).
     */
    private class RefusingReader(
        private val delegate: BoundedReader,
        private val refused: MutableMap<CellRef, StateReadResult.Reason>,
    ) : BoundedReader {
        override fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> {
            val reason = refused[ref]
            return if (reason != null) {
                CompletableFuture.completedFuture(StateReadResult.Unavailable(reason))
            } else {
                delegate.read(ref, request)
            }
        }
    }

    // --- [SOC1-SREAD-04] -----------------------------------------------------

    @Test
    fun `SOC1-SREAD-04 a refused ref is 503 with the reason name, does not leak to another ref, and never caches`() {
        val refused = ConcurrentHashMap<CellRef, StateReadResult.Reason>()
        val app = SocialApp(port = 0, reader = { host -> RefusingReader(HostBoundedReader(host), refused) }).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
            probe.post("action=person&id=2&firstName=Bob&lastName=Brown")
            probe.await { """"id":2""" in it }

            // The ref cannot be named until the cell it names has been spawned.
            val person1Ref = app.pipeline.families.person.getOrSpawn(1).ref
            refused[person1Ref] = StateReadResult.Reason.MIGRATING

            val first = probe.get("/person/1")
            assertEquals(503, first.statusCode())
            assertTrue(""""refused":"MIGRATING"""" in first.body(), first.body())

            app.shortReads.is1(1).get(10, TimeUnit.SECONDS) shouldBe ReadOutcome.Refused(StateReadResult.Reason.MIGRATING)

            val other = probe.get("/person/2")
            assertEquals(200, other.statusCode())
            assertTrue(""""found":true""" in other.body(), other.body())

            // no cache: a second read of the refused ref is still refused
            val second = probe.get("/person/1")
            assertEquals(503, second.statusCode())
            assertTrue(""""refused":"MIGRATING"""" in second.body(), second.body())
        } finally {
            app.stop()
        }
    }

    // --- [SOC1-SREAD-04] boundary: a cell that declares neither bound --------

    /** Copy of `BoundedStateReadTest.PlainBoundedCell`/`RoutedWalkRefusalTest.PlainBoundedCell` (kernel test sources, not visible from `:demo:social`). */
    private class PlainBoundedCell(
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : civictech.cell.Cell, civictech.cell.BoundedStateful {
        override fun readBounded(request: StateRead) = civictech.cell.StatePage(entries = emptyList())
        override fun snapshot(): java.io.Serializable = 0
        override fun restore(state: java.io.Serializable) = Unit
    }

    @Test
    fun `SOC1-SREAD-04 boundary a since-carrying read against a cell with supportsSince false is Refused SINCE_UNSUPPORTED`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry())
        val plain = PlainBoundedCell().also { host.managementInlet.call.spawn(it) }

        val result = host.readState(plain.ref, StateRead(since = TagFrontier(emptyMap())))
        result.isDone shouldBe true
        result.get() shouldBe StateReadResult.Unavailable(StateReadResult.Reason.SINCE_UNSUPPORTED)
        result.get().asOutcome() shouldBe ReadOutcome.Refused(StateReadResult.Reason.SINCE_UNSUPPORTED)

        // the same bound against a real person cell (SetCell, supportsSince = true) is a Page,
        // not a refusal — the boundary is the cell's own declaration, not the reader's.
        val pipeline = SnbPipeline.build(host, journalDir = null)
        val graph = SocialGraph(host, pipeline)
        graph.addPerson(Person(1, "Ada", "Lovelace"))
        val personRef = pipeline.families.person.getOrSpawn(1).ref

        val sincePage = host.readState(personRef, StateRead(since = TagFrontier(emptyMap())))
        controller.runToIdle()
        sincePage.get().shouldBeInstanceOf<StateReadResult.Page>()
    }

    // --- [SOC1-SREAD-04]/rx8om-D1: a never-spawned ref --------------------

    @Test
    fun `SOC1-SREAD-04 rx8om-D1 a never-spawned ref is Refused NOT_HOSTED with the future completed normally`() {
        val host = ManagedHost(registry = LocationRegistry())
        val reader = HostBoundedReader(host)

        val future = reader.read(CellRef(UUID.randomUUID()), StateRead())
        val result = future.get(10, TimeUnit.SECONDS)

        future.isCompletedExceptionally shouldBe false
        result shouldBe StateReadResult.Unavailable(StateReadResult.Reason.NOT_HOSTED)
        result.asOutcome() shouldBe ReadOutcome.Refused(StateReadResult.Reason.NOT_HOSTED)
    }

    // --- computenet-suj6a: SocialApp.respondOutcome's TIMEOUT branch ---------

    /**
     * Wraps a [BoundedReader], answering with a [CompletableFuture] that is
     * never completed for any ref present in [stuck] and delegating
     * otherwise — the never-completing-future case [RefusingReader] above
     * cannot produce, since [RefusingReader] always hands back a completed
     * future.
     */
    private class StuckReader(
        private val delegate: BoundedReader,
        private val stuck: MutableMap<CellRef, CompletableFuture<StateReadResult>>,
    ) : BoundedReader {
        override fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> =
            stuck[ref] ?: delegate.read(ref, request)
    }

    /**
     * `[SOC1-SREAD-04]`/rx8om-D8 (computenet-suj6a): a short read whose future
     * never completes answers 503 `{"refused":"TIMEOUT"}` within
     * [SocialApp]'s configured bound, and — because [DemoShell] dispatches
     * every route (`/state` included) on one thread (`server.executor =
     * null`) — a concurrent `/state` request queued behind it is delayed by
     * the same bound rather than answered promptly. This is the accepted
     * tradeoff `SHORT_READ_TIMEOUT`'s KDoc documents for a demo app (mirrors
     * `DialogueApp.onDriver`'s `ACTION_TIMEOUT_MS`); the test uses a short
     * override so it does not have to wait out the real 10s production bound.
     */
    @Test
    fun `SOC1-SREAD-04 a stuck short read times out with 503 TIMEOUT and stalls the dispatcher`() {
        val stuck = ConcurrentHashMap<CellRef, CompletableFuture<StateReadResult>>()
        val timeoutSeconds = 1L
        val app = SocialApp(
            port = 0,
            reader = { host -> StuckReader(HostBoundedReader(host), stuck) },
            shortReadTimeoutSeconds = timeoutSeconds,
        ).start()
        try {
            HttpProbe("http://localhost:${app.boundPort}").use { probe ->
                probe.post("action=person&id=1&firstName=Ada&lastName=Lovelace")
                probe.await { """"id":1""" in it }

                val person1Ref = app.pipeline.families.person.getOrSpawn(1).ref
                stuck[person1Ref] = CompletableFuture()

                // Issue the stuck read on its own thread and a /state read
                // right behind it: DemoShell's single dispatcher thread means
                // /state cannot be answered until the stuck read gives up.
                val stateBefore = System.nanoTime()
                val readExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try {
                    val stuckResponse = readExecutor.submit<java.net.http.HttpResponse<String>> { probe.get("/person/1") }
                    // give the stuck request a head start onto the dispatcher thread
                    Thread.sleep(100)
                    val stateResponse = probe.get("/state")
                    val stateElapsedMs = (System.nanoTime() - stateBefore) / 1_000_000

                    assertEquals(503, stuckResponse.get(10, TimeUnit.SECONDS).statusCode())
                    assertTrue(
                        """"refused":"TIMEOUT"""" in stuckResponse.get().body(),
                        stuckResponse.get().body(),
                    )
                    assertEquals(200, stateResponse.statusCode())
                    assertTrue(
                        stateElapsedMs >= (timeoutSeconds * 1000) - 100,
                        "/state answered after ${stateElapsedMs}ms, before the stuck read's " +
                            "${timeoutSeconds}s bound elapsed — expected it to queue behind the stuck read " +
                            "on DemoShell's single dispatcher thread",
                    )
                } finally {
                    readExecutor.shutdownNow()
                }
            }
        } finally {
            app.stop()
        }
    }
}
