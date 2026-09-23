package civictech.demo.social

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * `[SOC1-FEED-09]`, `[SOC1-FEED-10]` (epic `computenet-07k` B10) for feature
 * `computenet-flfkm` task `computenet-flfkm.2`: `FeedSession.board(limit,
 * before)` orders the accumulated feed `creationDate` descending, ties by
 * message id descending, demo-side over the per-leg pages `FeedSession`
 * already retains.
 *
 * Rig: the scatter-gather idiom of `SocialFeedScatterGatherTest` — one
 * [SimulationController] host, so a pull's pages land on `runToIdle()`.
 */
class SocialFeedOrderTest {

    private companion object {
        const val V = 1L
        val FRIENDS = listOf(2L, 3L, 4L, 5L, 6L)
        const val FORUM = 900L
    }

    private class Rig {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val pipeline = SnbPipeline.build(host, journalDir = null, registry = registry)
        val graph = SocialGraph(host, pipeline)
        val reader = HostBoundedReader(host)
        val session: FeedSession by lazy {
            FeedSession(
                viewer = V,
                scope = Interest.Ranges(FRIENDS.map { Interest.Ranges.Range(it, it + 1) }),
                families = pipeline.families,
                registry = registry,
                reader = reader,
            )
        }

        init {
            graph.addPerson(Person(V, "V", "viewer"))
            FRIENDS.forEach { graph.addPerson(Person(it, "p$it", "author")) }
            graph.addForum(Forum(FORUM, "forum", V))
            controller.runToIdle()
        }

        fun post(id: Long, author: Long, creationDate: Long) {
            graph.addPost(Message(id, author, creationDate, "m$id", forumId = FORUM))
            controller.runToIdle()
        }

        fun pull() {
            val future = session.pull()
            controller.runToIdle()
            future.get(20, TimeUnit.SECONDS)
        }
    }

    // --- [SOC1-FEED-09] ----------------------------------------------------

    @Test
    fun `B10 board orders the accumulated feed creationDate desc then id desc and honors limit`() {
        val rig = Rig()
        // 100 messages, ids 100..199, author round-robin over the 5 friends, ten
        // distinct creationDates (1000..1009) with ten colliding messages each.
        val fixture = (0 until 100).map { i ->
            Message(
                id = 100L + i,
                creatorId = FRIENDS[i % FRIENDS.size],
                creationDate = 1000L + (i % 10),
                content = "m${100 + i}",
                forumId = FORUM,
            )
        }
        fixture.forEach { rig.post(it.id, it.creatorId, it.creationDate) }
        rig.pull()

        val independentlySorted = fixture
            .sortedWith(compareByDescending<Message> { it.creationDate }.thenByDescending { it.id })
            .take(20)
            .map { it.id }

        // Written out as a literal so the test cannot pass by sharing a wrong
        // comparator with the production code: the ten ids with date 1009
        // (199, 189, ..., 109) then the ten with date 1008 (198, ..., 108).
        val expectedIds = listOf(
            199L, 189L, 179L, 169L, 159L, 149L, 139L, 129L, 119L, 109L,
            198L, 188L, 178L, 168L, 158L, 148L, 138L, 128L, 118L, 108L,
        )
        expectedIds shouldBe independentlySorted

        val board20 = rig.session.board(20)
        board20.map { it.id } shouldBe expectedIds
        board20.size shouldBe 20

        rig.session.board(100).size shouldBe 100
        rig.session.board(150).size shouldBe 100
    }

    @Test
    fun `before filters to creationDate less than before`() {
        val rig = Rig()
        val fixture = (0 until 100).map { i ->
            Message(
                id = 100L + i,
                creatorId = FRIENDS[i % FRIENDS.size],
                creationDate = 1000L + (i % 10),
                content = "m${100 + i}",
                forumId = FORUM,
            )
        }
        fixture.forEach { rig.post(it.id, it.creatorId, it.creationDate) }
        rig.pull()

        val page = rig.session.board(20, before = 1005L)
        page.all { it.creationDate < 1005L } shouldBe true
        page.first().id shouldBe 194L
    }

    // --- ordering over the accumulated set, not one pull's pages ------------

    @Test
    fun `a message newer than every delivered one appears first after posting and pulling again`() {
        val rig = Rig()
        val fixture = (0 until 100).map { i ->
            Message(
                id = 100L + i,
                creatorId = FRIENDS[i % FRIENDS.size],
                creationDate = 1000L + (i % 10),
                content = "m${100 + i}",
                forumId = FORUM,
            )
        }
        fixture.forEach { rig.post(it.id, it.creatorId, it.creationDate) }
        rig.pull()

        rig.post(300L, FRIENDS.first(), 1009L)
        rig.pull()

        rig.session.board(20).first().id shouldBe 300L
    }

    @Test
    fun `board zero throws IllegalArgumentException`() {
        val rig = Rig()
        try {
            rig.session.board(0)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- [SOC1-FEED-10] source guard -----------------------------------------

    @Test
    fun `Feed kt orders demo-side, never through a kernel operator`() {
        // A Gradle test's working directory is the project directory (SocialFeedFrontierTest relies on the same).
        val source = File("src/main/kotlin/civictech/demo/social/Feed.kt").readText()
        ("import civictech.cell.data.op" in source) shouldBe false
        ("Aggregators" in source) shouldBe false
    }
}
