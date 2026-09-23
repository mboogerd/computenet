package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.StateReadResult
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Follows-as-interest (SOC1 F5, feature `computenet-4q9is`, task
 * `computenet-4q9is.1`; epic `computenet-07k` B12, B13): the viewer's `knows`
 * set is the feed's scope, derived by [ViewerInterest] through the
 * [BoundedReader] at the start of every pull (4q9is-D1..D6).
 *
 * Rig as in `SocialFeedScatterGatherTest` (8eb53-D8): one
 * [SimulationController] host, `runToIdle()` after every write and before
 * joining a pull future. The reader chain is
 * `RecordingReader(RefusingReader(HostBoundedReader))`, so the recorder also
 * sees reads the refusing layer answers; [Rig.refused] is empty unless a test
 * fills it.
 */
class SocialInterestTest {

    private companion object {
        const val V = 1L
        const val A = 2L
        const val C = 4L
        const val D = 5L
        const val S = 6L // a stranger: nobody's friend, but holds posts
        const val FORUM = 100L
    }

    private class Rig {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val pipeline = SnbPipeline.build(host, journalDir = null, registry = registry)
        val families = pipeline.families
        val graph = SocialGraph(host, pipeline)
        val refused: MutableMap<CellRef, StateReadResult.Reason> = ConcurrentHashMap()
        val recorder = RecordingReader(RefusingReader(HostBoundedReader(host), refused))
        val interest = ViewerInterest(GraphLocator(graph, families), recorder, registry)
        val session = FeedSession(V, interest, families, registry, recorder)

        init {
            for (id in listOf(V, A, C, D, S)) graph.addPerson(Person(id, "p$id", "person"))
            graph.addForum(Forum(FORUM, "forum", V))
            controller.runToIdle()
        }

        fun post(id: Long, author: Long) {
            graph.addPost(Message(id, author, id, "m$id", forumId = FORUM))
            controller.runToIdle()
        }

        fun knows(other: Long, date: Long) {
            graph.addKnows(V, other, date)
            controller.runToIdle()
        }

        fun unknows(other: Long, date: Long) {
            graph.removeKnows(V, other, date)
            controller.runToIdle()
        }

        /** Idempotent on a live key: the person cell exists for every fixture id. */
        fun personRef(id: Long): CellRef = families.person.getOrSpawn(id).ref

        /** Only called for authors that have posted (a live key), so it spawns nothing. */
        fun authoredRef(id: Long): CellRef = families.authored.getOrSpawn(id).ref

        /** Pulls with the recorder reset first, so [RecordingReader.calls] is this pull's reads only. */
        fun pull(): PullReport {
            recorder.reset()
            val future = session.pull()
            controller.runToIdle()
            return future.get(20, TimeUnit.SECONDS)
        }

        /** A pull expected to fail: the cause its future completed exceptionally with. */
        fun failingPull(): Throwable {
            recorder.reset()
            val future = session.pull()
            controller.runToIdle()
            return shouldThrow<ExecutionException> { future.get(20, TimeUnit.SECONDS) }.cause!!
        }

        fun legReads(): List<RecordingReader.Call> = recorder.calls().filter { it.ref != personRef(V) }

        fun personReads(): Int = recorder.calls().count { it.ref == personRef(V) }

        fun boardIds(): Set<Long> = session.board().map { it.id }.toSet()

        fun pagedIds(): List<Long> = session.board(limit = 100).map { it.id }
    }

    private fun ranges(vararg ids: Long) = Interest.Ranges(ids.map { Interest.Ranges.Range(it, it + 1) })

    // --- [SOC1-INT-01] ------------------------------------------------------

    @Test
    fun `SOC1-INT-01 scope is one sorted singleton range per friend, declared on the person ref, read once per pull`() {
        val rig = Rig()
        rig.knows(C, 7) // added out of order: the scope is sorted, not insertion-ordered
        rig.knows(A, 7)
        rig.post(10, A)
        rig.post(30, C)
        rig.session.scope shouldBe Interest.Empty // derived source: Empty until the first pull

        val report = rig.pull()

        val expected = ranges(A, C)
        rig.session.scope shouldBe expected
        rig.registry.interestOf(rig.personRef(V)) shouldBe expected
        rig.personReads() shouldBe 1
        rig.legReads().map { it.ref } shouldBe listOf(rig.authoredRef(A), rig.authoredRef(C))
        report.legs.keys shouldBe setOf(rig.authoredRef(A), rig.authoredRef(C))
        rig.boardIds() shouldBe setOf(10L, 30L)

        // Every pull re-derives: exactly one more person read, not zero.
        rig.pull()
        rig.personReads() shouldBe 1
    }

    @Test
    fun `an unknown viewer has the Empty scope at zero reads and spawns no person cell`() {
        val rig = Rig()
        rig.recorder.reset()

        val future = rig.interest.scopeOf(999L)
        rig.controller.runToIdle()

        future.get(20, TimeUnit.SECONDS) shouldBe Interest.Empty
        rig.recorder.count shouldBe 0
        rig.families.person.contains(999L) shouldBe false
    }

    // --- [SOC1-INT-02] (B12) ------------------------------------------------

    @Test
    fun `SOC1-INT-02 B12 adding a knows edge widens the next pull by exactly one read`() {
        val rig = Rig()
        rig.knows(A, 7)
        rig.post(10, A)
        rig.post(50, D)
        val refA = rig.authoredRef(A)
        val refD = rig.authoredRef(D)

        val first = rig.pull()
        rig.boardIds() shouldBe setOf(10L)
        rig.recorder.count shouldBe 2 // 1 person + 1 (A)
        first.legs.keys shouldBe setOf(refA)

        rig.knows(D, 7)
        val second = rig.pull()

        rig.boardIds() shouldBe setOf(10L, 50L)
        rig.recorder.count shouldBe 3 // exactly one more
        second.legs.keys shouldBe setOf(refA, refD)
        rig.session.scope shouldBe ranges(A, D)
    }

    // --- [SOC1-INT-03] (B13) ------------------------------------------------

    @Test
    fun `SOC1-INT-03 B13 removing an edge stops the leg and hides earlier messages, re-adding resumes from the retained frontier`() {
        val rig = Rig()
        rig.knows(A, 7)
        rig.knows(D, 7)
        rig.post(10, A)
        rig.post(50, D)
        val refA = rig.authoredRef(A)
        val refD = rig.authoredRef(D)

        rig.pull()
        rig.boardIds() shouldBe setOf(10L, 50L)

        // Same date as the add: SocialGraph.removeKnows removes by element equality.
        rig.unknows(D, 7)
        rig.post(60, D)
        val second = rig.pull()

        rig.recorder.requestsFor(refD) shouldBe emptyList()
        second.legs.keys shouldBe setOf(refA)
        rig.boardIds() shouldBe setOf(10L) // 50 hidden, 60 never pulled
        rig.pagedIds() shouldBe listOf(10L)
        rig.session.frontiers().keys.contains(refD) shouldBe true // state kept, output hidden

        rig.knows(D, 8)
        val third = rig.pull()

        rig.recorder.requestsFor(refD).last().since shouldNotBe null
        third.legs.keys shouldBe setOf(refA, refD)
        rig.boardIds() shouldBe setOf(10L, 50L, 60L)
        rig.pagedIds() shouldBe listOf(60L, 50L, 10L)
    }

    // [SOC1-INT-04]: task computenet-4q9is task 2 (computenet-4q9is.2) appends the interest-driven spawn tests here.

    // --- [SOC1-INT-05] (B13, second clause) ---------------------------------

    @Test
    fun `SOC1-INT-05 an empty knows set is the Empty scope - no leg read, empty board, never Total`() {
        val rig = Rig()
        rig.knows(A, 7)
        rig.post(10, A)
        rig.post(70, S) // the stranger's authored cell is live in the family
        rig.families.authored.contains(S) shouldBe true

        rig.pull()
        rig.boardIds() shouldBe setOf(10L)

        rig.unknows(A, 7)
        val second = rig.pull()

        rig.session.scope shouldBe Interest.Empty
        rig.registry.interestOf(rig.personRef(V)) shouldBe Interest.Empty
        second.legs shouldBe emptyMap()
        rig.boardIds() shouldBe emptySet()
        rig.pagedIds() shouldBe emptyList()
        // A widen-to-Total would read A's and S's authored cells; only the person ref is read.
        rig.recorder.calls().map { it.ref } shouldBe listOf(rig.personRef(V))
    }

    // --- 4q9is-D4: a refused scope read fails the pull ----------------------

    @Test
    fun `a refused person read fails the pull with ScopeUnavailable, leaves scope and board, and a later pull succeeds`() {
        val rig = Rig()
        rig.knows(A, 7)
        rig.post(10, A)
        rig.pull()
        val scopeBefore = rig.session.scope
        val frontiersBefore = rig.session.frontiers()
        scopeBefore shouldBe ranges(A)

        rig.knows(D, 7) // a scope change the refused pull must NOT apply
        rig.post(50, D)
        rig.refused[rig.personRef(V)] = StateReadResult.Reason.NOT_HOSTED
        val cause = rig.failingPull()

        cause.shouldBeInstanceOf<ScopeUnavailable>().reason shouldBe StateReadResult.Reason.NOT_HOSTED
        rig.legReads() shouldBe emptyList()
        rig.session.scope shouldBe scopeBefore
        rig.boardIds() shouldBe setOf(10L)
        rig.session.frontiers() shouldBe frontiersBefore
        rig.registry.interestOf(rig.personRef(V)) shouldBe scopeBefore

        rig.refused.clear()
        val later = rig.pull() // inFlight was released by the exceptional completion

        later.legs.keys shouldBe setOf(rig.authoredRef(A), rig.authoredRef(D))
        rig.session.scope shouldBe ranges(A, D)
        rig.boardIds() shouldBe setOf(10L, 50L)
    }

    // --- 4q9is-D5: nothing but Ranges or Empty is pulled --------------------

    @Test
    fun `a Total scope fails the pull without issuing a leg`() {
        val rig = Rig()
        rig.post(10, A)
        val session = FeedSession(
            V,
            { CompletableFuture.completedFuture<Interest>(Interest.Total) },
            rig.families,
            rig.registry,
            rig.recorder,
        )
        rig.recorder.reset()

        val future = session.pull()
        rig.controller.runToIdle()
        val cause = shouldThrow<ExecutionException> { future.get(20, TimeUnit.SECONDS) }.cause!!

        cause.shouldBeInstanceOf<IllegalStateException>()
        rig.recorder.count shouldBe 0
        session.scope shouldBe Interest.Empty
        session.board() shouldBe emptySet()
    }
}
