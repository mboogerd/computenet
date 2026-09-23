package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The API half of `[SOC1-CREAD-01]` (IC8, IC3) and `[SOC1-CREAD-04]`
 * (feature `computenet-flfkm`, task `computenet-flfkm.3`, design
 * flfkm-D4..D6), driven directly against [ComplexReads] — no HTTP; plus the
 * HTTP half (task `computenet-flfkm.4`, design flfkm-D8): `/feed`, `/replies`,
 * `/fof`, the `[SOC1-CREAD-03]` 404 guard and the `[SOC1-CREAD-02]` `/state`
 * `queries` literal, driven against a started [SocialApp] with [HttpProbe].
 *
 * The host is a **threaded** [ManagedHost], the configuration `SocialApp`
 * builds, so a complex read's future completes on the host's own scheduler
 * without a `step()` pump; [awaitUntil] is the bounded wait that replaces
 * one, and the read counters are only ever read after it — same idiom as
 * `SocialShortReadTest`. The HTTP-half fixtures build their graphs through
 * `app.graph` directly rather than `/op` — `/op action=post` has no
 * `locationCountryId` param (flfkm-D2), so the IC3 fixture needs the direct
 * path, and the other fixtures use it too for the same shape.
 */
class SocialComplexReadTest {

    /**
     * Records every `(ref, request)` pair passed through, so
     * `[SOC1-CREAD-01]`'s read-cost clauses count reads rather than timing
     * them. Duplicated from `SocialShortReadTest`'s private fake, deliberately
     * (that file is claimed by an open sibling task).
     */
    private class CountingReader(private val delegate: BoundedReader) : BoundedReader {
        val calls = CopyOnWriteArrayList<Pair<CellRef, StateRead>>()

        override fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> {
            calls += ref to request
            return delegate.read(ref, request)
        }

        fun reset() = calls.clear()
        val count: Int get() = calls.size
    }

    private fun <T> CompletableFuture<T>.answer(): T = get(20, TimeUnit.SECONDS)

    // --- [SOC1-CREAD-04] ------------------------------------------------------

    @Test
    fun `SOC1-CREAD-04 Queries kt names none of the forbidden symbols`() {
        val text = java.io.File("src/main/kotlin/civictech/demo/social/Queries.kt").readText()
        for (forbidden in listOf("SnbSource", "SocialLoader", "SocialGraph", "keys()")) {
            (forbidden in text) shouldBe false
        }
    }

    // --- IC8 -------------------------------------------------------------------

    private class Ic8Fixture(
        val graph: SocialGraph,
        val reader: CountingReader,
        val reads: ComplexReads,
    )

    /**
     * flfkm's IC8 example: P=1 authored posts 10 (forum 100) and 11 (forum
     * 100); Q=2 comments 21 (date 5, reply of 10) and 23 (date 9, reply of
     * 11); R=3 comments 22 (date 5, reply of 11).
     */
    private fun ic8Graph(): Ic8Fixture {
        val host = ManagedHost(registry = LocationRegistry())
        val pipeline = SnbPipeline.build(host, journalDir = null)
        val graph = SocialGraph(host, pipeline)

        graph.addPerson(Person(1, "P", "One"))
        graph.addPerson(Person(2, "Q", "Two"))
        graph.addPerson(Person(3, "R", "Three"))
        graph.addForum(Forum(100, "f", moderatorId = 1))
        graph.addPost(Message(10, creatorId = 1, creationDate = 1, content = "post10", forumId = 100))
        graph.addPost(Message(11, creatorId = 1, creationDate = 2, content = "post11", forumId = 100))
        graph.addComment(Message(21, creatorId = 2, creationDate = 5, content = "c21", replyOfId = 10))
        graph.addComment(Message(22, creatorId = 3, creationDate = 5, content = "c22", replyOfId = 11))
        graph.addComment(Message(23, creatorId = 2, creationDate = 9, content = "c23", replyOfId = 11))

        awaitUntil("the IC8 example graph to settle", timeoutMs = 20_000) {
            graph.authored(1).size == 2 &&
                graph.messageFacts(10).any { it is MessageFact.Reply } &&
                graph.messageFacts(11).count { it is MessageFact.Reply } == 2 &&
                graph.messageFacts(21).any { it is MessageFact.Body } &&
                graph.messageFacts(22).any { it is MessageFact.Body } &&
                graph.messageFacts(23).any { it is MessageFact.Body }
        }

        val reader = CountingReader(HostBoundedReader(host))
        return Ic8Fixture(
            graph = graph,
            reader = reader,
            reads = ComplexReads(reader, GraphLocator(graph, pipeline.families)),
        )
    }

    @Test
    fun `SOC1-CREAD-01 ic8 answers the example graph date desc id asc at 1 plus 2 plus 3 reads`() {
        val f = ic8Graph()

        f.reader.reset()
        val result = f.reads.ic8(1, 20).answer()
        result shouldBe ReadOutcome.Found(
            listOf(
                Message(23, creatorId = 2, creationDate = 9, content = "c23", replyOfId = 11),
                Message(21, creatorId = 2, creationDate = 5, content = "c21", replyOfId = 10),
                Message(22, creatorId = 3, creationDate = 5, content = "c22", replyOfId = 11),
            )
        )
        f.reader.count shouldBe 1 + 2 + 3
    }

    @Test
    fun `SOC1-CREAD-01 ic8 limit takes the prefix`() {
        val f = ic8Graph()

        f.reads.ic8(1, 2).answer() shouldBe ReadOutcome.Found(
            listOf(
                Message(23, creatorId = 2, creationDate = 9, content = "c23", replyOfId = 11),
                Message(21, creatorId = 2, creationDate = 5, content = "c21", replyOfId = 10),
            )
        )
    }

    @Test
    fun `SOC1-CREAD-01 ic8 for an unknown person is Empty at zero reads`() {
        val f = ic8Graph()

        f.reader.reset()
        f.reads.ic8(99, 20).answer() shouldBe ReadOutcome.Empty
        f.reader.count shouldBe 0
    }

    @Test
    fun `SOC1-CREAD-01 ic8 for a known person with no messages is Found empty at zero reads`() {
        val f = ic8Graph()
        val cy = Person(4, "Cy", "Clone")
        f.graph.addPerson(cy)
        awaitUntil("person 4 to settle") { f.graph.personFacts(4).any { it is PersonFact.Profile } }

        f.reader.reset()
        f.reads.ic8(4, 20).answer() shouldBe ReadOutcome.Found(emptyList())
        f.reader.count shouldBe 0
    }

    // --- IC3 ---------------------------------------------------------------

    private class Ic3Fixture(
        val graph: SocialGraph,
        val families: SnbPipeline.Families,
        val reader: CountingReader,
        val reads: ComplexReads,
    )

    /**
     * flfkm's IC3 example: V=1 knows A=2, B=3; A knows C=4; B knows D=5 (the
     * undirected [SocialGraph.addKnows] edges already put V back into every
     * friend's `Knows` set, which is what makes "V is never a candidate" a
     * real assertion rather than a vacuous one). A: msg 10 (t=10, country 6),
     * msg 11 (t=20, country 7). C: msg 12 (t=30, country 6), msg 13 (t=30,
     * country 7) — both AT the `to = 30` boundary, deliberately, so the
     * half-open `[from, to)` mutation check (evidence.md "Mutation checks":
     * flip `<` to `<=` on `to`) actually discriminates: with `to` exclusive
     * both are dropped and C is filtered for `countB == 0`, but an inclusive
     * `to` would wrongly complete C's pair and pass this test's negative
     * assertion. D: msg 14 (t=40, country 6) only. B has no messages at all.
     */
    private fun ic3Graph(): Ic3Fixture {
        val host = ManagedHost(registry = LocationRegistry())
        val pipeline = SnbPipeline.build(host, journalDir = null)
        val graph = SocialGraph(host, pipeline)

        graph.addPerson(Person(1, "V", "One"))
        graph.addPerson(Person(2, "A", "Two"))
        graph.addPerson(Person(3, "B", "Three"))
        graph.addPerson(Person(4, "C", "Four"))
        graph.addPerson(Person(5, "D", "Five"))
        graph.addKnows(1, 2, 1)
        graph.addKnows(1, 3, 2)
        graph.addKnows(2, 4, 3)
        graph.addKnows(3, 5, 4)
        graph.addForum(Forum(900, "f", moderatorId = 1))
        graph.addPost(Message(10, creatorId = 2, creationDate = 10, content = "a1", forumId = 900, locationCountryId = 6))
        graph.addPost(Message(11, creatorId = 2, creationDate = 20, content = "a2", forumId = 900, locationCountryId = 7))
        graph.addPost(Message(12, creatorId = 4, creationDate = 30, content = "c1", forumId = 900, locationCountryId = 6))
        graph.addPost(Message(13, creatorId = 4, creationDate = 30, content = "c2", forumId = 900, locationCountryId = 7))
        graph.addPost(Message(14, creatorId = 5, creationDate = 40, content = "d1", forumId = 900, locationCountryId = 6))

        awaitUntil("the IC3 example graph to settle", timeoutMs = 20_000) {
            graph.personFacts(1).count { it is Knows } == 2 &&
                graph.personFacts(2).count { it is Knows } == 2 &&
                graph.personFacts(3).count { it is Knows } == 2 &&
                graph.authored(2).size == 2 &&
                graph.authored(4).size == 2 &&
                graph.authored(5).size == 1
        }

        val reader = CountingReader(HostBoundedReader(host))
        return Ic3Fixture(
            graph = graph,
            families = pipeline.families,
            reader = reader,
            reads = ComplexReads(reader, GraphLocator(graph, pipeline.families)),
        )
    }

    @Test
    fun `SOC1-CREAD-01 ic3 answers friends and friends-of-friends at 1 plus 2 plus 3 reads`() {
        val f = ic3Graph()

        f.reader.reset()
        val result = f.reads.ic3(1, 6, 7, from = 0, to = 100, limit = 20).answer()
        result shouldBe ReadOutcome.Found(listOf(Ic3Row(2, 1, 1), Ic3Row(4, 1, 1)))
        f.reader.count shouldBe 1 + 2 + 3
    }

    @Test
    fun `SOC1-CREAD-01 ic3 to is half-open, excluding a message exactly at the boundary`() {
        val f = ic3Graph()

        f.reads.ic3(1, 6, 7, from = 0, to = 30, limit = 20).answer() shouldBe
            ReadOutcome.Found(listOf(Ic3Row(2, 1, 1)))
    }

    @Test
    fun `SOC1-CREAD-01 ic3 never returns the viewer even with messages in both countries`() {
        val f = ic3Graph()
        f.graph.addPost(Message(50, creatorId = 1, creationDate = 15, content = "v1", forumId = 900, locationCountryId = 6))
        f.graph.addPost(Message(51, creatorId = 1, creationDate = 16, content = "v2", forumId = 900, locationCountryId = 7))
        awaitUntil("viewer's own messages to settle") { f.graph.authored(1).size == 2 }

        val result = f.reads.ic3(1, 6, 7, from = 0, to = 100, limit = 20).answer()
        (result as ReadOutcome.Found).value.none { it.personId == 1L } shouldBe true
    }

    @Test
    fun `SOC1-CREAD-01 ic3 refused on a candidate's authored cell ends the query with no partial rows`() {
        val f = ic3Graph()
        val cRef = f.families.authored.getOrSpawn(4).ref
        val refused = ConcurrentHashMap<CellRef, StateReadResult.Reason>()
        refused[cRef] = StateReadResult.Reason.READ_FAILED
        val refusing = ComplexReads(RefusingReader(f.reader, refused), GraphLocator(f.graph, f.families))

        refusing.ic3(1, 6, 7, from = 0, to = 100, limit = 20).answer() shouldBe
            ReadOutcome.Refused(StateReadResult.Reason.READ_FAILED)
    }

    // --- guards --------------------------------------------------------------

    @Test
    fun `ic8 and ic3 reject a non-positive limit`() {
        val f = ic8Graph()
        try {
            f.reads.ic8(1, 0)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `ic3 rejects equal countries and an inverted window`() {
        val f = ic3Graph()
        try {
            f.reads.ic3(1, 6, 6, from = 0, to = 10)
            throw AssertionError("expected IllegalArgumentException for equal countries")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        try {
            f.reads.ic3(1, 6, 7, from = 10, to = 0)
            throw AssertionError("expected IllegalArgumentException for from > to")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // --- HTTP half (task computenet-flfkm.4, design flfkm-D8) -----------------

    /** [Message.json]'s exact shape, independently reconstructed for the HTTP assertions below. */
    private fun messageJson(
        id: Long,
        creatorId: Long,
        creationDate: Long,
        content: String,
        forumId: Long? = null,
        replyOfId: Long? = null,
    ): String =
        """{"id":$id,"creatorId":$creatorId,"creationDate":$creationDate,"content":"$content",""" +
            """"forumId":${forumId ?: "null"},"replyOfId":${replyOfId ?: "null"}}"""

    // --- /feed (IC2) -----------------------------------------------------------

    @Test
    fun `SOC1-CREAD-01 feed returns the D1 order for a viewer with two friends and five messages`() {
        val app = SocialApp(port = 0).start()
        try {
            app.graph.addPerson(Person(1, "V", "One"))
            app.graph.addPerson(Person(2, "A", "Two"))
            app.graph.addPerson(Person(3, "B", "Three"))
            app.graph.addKnows(1, 2, 1)
            app.graph.addKnows(1, 3, 2)
            app.graph.addForum(Forum(100, "f", moderatorId = 1))
            app.graph.addPost(Message(10, creatorId = 2, creationDate = 100, content = "m10", forumId = 100))
            app.graph.addPost(Message(11, creatorId = 2, creationDate = 200, content = "m11", forumId = 100))
            app.graph.addPost(Message(12, creatorId = 3, creationDate = 150, content = "m12", forumId = 100))
            app.graph.addPost(Message(13, creatorId = 3, creationDate = 250, content = "m13", forumId = 100))
            app.graph.addPost(Message(14, creatorId = 2, creationDate = 50, content = "m14", forumId = 100))

            awaitUntil("the feed example graph to settle", timeoutMs = 20_000) {
                app.graph.personFacts(1).count { it is Knows } == 2 &&
                    app.graph.authored(2).size == 3 &&
                    app.graph.authored(3).size == 2
            }

            val probe = HttpProbe("http://localhost:${app.boundPort}")

            val full = probe.get("/feed?person=1&limit=20")
            full.statusCode() shouldBe 200
            full.body() shouldBe """{"found":true,"messages":[""" +
                "${messageJson(13, 3, 250, "m13", 100)}," +
                "${messageJson(11, 2, 200, "m11", 100)}," +
                "${messageJson(12, 3, 150, "m12", 100)}," +
                "${messageJson(10, 2, 100, "m10", 100)}," +
                "${messageJson(14, 2, 50, "m14", 100)}]}"

            probe.get("/feed?person=1&limit=2").body() shouldBe """{"found":true,"messages":[""" +
                "${messageJson(13, 3, 250, "m13", 100)}," +
                "${messageJson(11, 2, 200, "m11", 100)}]}"

            probe.get("/feed?person=1&limit=20&before=200").body() shouldBe """{"found":true,"messages":[""" +
                "${messageJson(12, 3, 150, "m12", 100)}," +
                "${messageJson(10, 2, 100, "m10", 100)}," +
                "${messageJson(14, 2, 50, "m14", 100)}]}"
        } finally {
            app.stop()
        }
    }

    @Test
    fun `SOC1-CREAD-01 feed for an unknown person is found false`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val resp = probe.get("/feed?person=999&limit=20")
            resp.statusCode() shouldBe 200
            resp.body() shouldBe """{"found":false}"""
        } finally {
            app.stop()
        }
    }

    @Test
    fun `feed rejects a non-numeric person and a non-positive limit`() {
        val app = SocialApp(port = 0).start()
        try {
            app.graph.addPerson(Person(1, "V", "One"))
            awaitUntil("person 1 to settle") { app.graph.personFacts(1).any { it is PersonFact.Profile } }

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.get("/feed?person=x&limit=20").statusCode() shouldBe 400
            probe.get("/feed?person=1&limit=0").statusCode() shouldBe 400
            probe.get("/feed?limit=20").statusCode() shouldBe 400 // missing person
        } finally {
            app.stop()
        }
    }

    @Test
    fun `feed for a viewer with no friends is found true with empty messages`() {
        val app = SocialApp(port = 0).start()
        try {
            app.graph.addPerson(Person(1, "Lonely", "One"))
            awaitUntil("person 1 to settle") { app.graph.personFacts(1).any { it is PersonFact.Profile } }

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val resp = probe.get("/feed?person=1&limit=20")
            resp.statusCode() shouldBe 200
            resp.body() shouldBe """{"found":true,"messages":[]}"""
        } finally {
            app.stop()
        }
    }

    @Test
    fun `two consecutive feed calls after a new post both reflect it via the cached session`() {
        val app = SocialApp(port = 0).start()
        try {
            app.graph.addPerson(Person(1, "V", "One"))
            app.graph.addPerson(Person(2, "A", "Two"))
            app.graph.addKnows(1, 2, 1)
            app.graph.addForum(Forum(100, "f", moderatorId = 1))
            app.graph.addPost(Message(10, creatorId = 2, creationDate = 100, content = "m10", forumId = 100))
            awaitUntil("initial post to settle") { app.graph.authored(2).size == 1 }

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.get("/feed?person=1&limit=20").body() shouldBe
                """{"found":true,"messages":[${messageJson(10, 2, 100, "m10", 100)}]}"""

            app.graph.addPost(Message(11, creatorId = 2, creationDate = 200, content = "m11", forumId = 100))
            awaitUntil("second post to settle") { app.graph.authored(2).size == 2 }

            val expected = """{"found":true,"messages":[${messageJson(11, 2, 200, "m11", 100)},${messageJson(10, 2, 100, "m10", 100)}]}"""
            probe.get("/feed?person=1&limit=20").body() shouldBe expected
            probe.get("/feed?person=1&limit=20").body() shouldBe expected
        } finally {
            app.stop()
        }
    }

    @Test
    fun `a new friend after the session is cached is picked up on the next feed call`() {
        // computenet-4q9is.3: /feed's cached FeedSession is never rebuilt on a
        // friend change (SOC1-FEED-09 is now met by per-pull scope derivation,
        // ViewerInterest, not by comparing a cached scope) — the session's own
        // ScopeSource re-reads the viewer's `knows` set at the start of every
        // pull, so a friend added between two /feed calls is picked up on the
        // second call's pull with no session rebuild. The mutation this test
        // catches: a FeedSession built with a one-shot fixed scope (the pre-
        // 4q9is-D8 shape) captured at first use instead of ViewerInterest's
        // derive-on-every-pull source. Under that mutation person 3's message
        // would never be read, since the scope was fixed to {2} at construction.
        val app = SocialApp(port = 0).start()
        try {
            app.graph.addPerson(Person(1, "V", "One"))
            app.graph.addPerson(Person(2, "A", "Two"))
            app.graph.addPerson(Person(3, "B", "Three"))
            app.graph.addKnows(1, 2, 1)
            app.graph.addForum(Forum(100, "f", moderatorId = 1))
            app.graph.addPost(Message(10, creatorId = 2, creationDate = 100, content = "m10", forumId = 100))
            awaitUntil("initial post to settle") { app.graph.authored(2).size == 1 }

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.get("/feed?person=1&limit=20").body() shouldBe
                """{"found":true,"messages":[${messageJson(10, 2, 100, "m10", 100)}]}"""

            // Befriend 3 only after the session above was built and cached.
            app.graph.addKnows(1, 3, 2)
            app.graph.addPost(Message(20, creatorId = 3, creationDate = 300, content = "m20", forumId = 100))
            awaitUntil("new friend's post to settle") { app.graph.authored(3).size == 1 }

            val expected = """{"found":true,"messages":[${messageJson(20, 3, 300, "m20", 100)},${messageJson(10, 2, 100, "m10", 100)}]}"""
            probe.get("/feed?person=1&limit=20").body() shouldBe expected
        } finally {
            app.stop()
        }
    }

    @Test
    fun `an unfriended author's messages leave the feed and a new post by them is never read`() {
        val app = SocialApp(port = 0).start()
        try {
            app.graph.addPerson(Person(1, "V", "One"))
            app.graph.addPerson(Person(2, "A", "Two"))
            app.graph.addPerson(Person(3, "B", "Three"))
            app.graph.addKnows(1, 2, 1)
            app.graph.addKnows(1, 3, 2)
            app.graph.addForum(Forum(100, "f", moderatorId = 1))
            app.graph.addPost(Message(10, creatorId = 2, creationDate = 100, content = "m10", forumId = 100))
            app.graph.addPost(Message(20, creatorId = 3, creationDate = 200, content = "m20", forumId = 100))
            awaitUntil("both initial posts to settle") {
                app.graph.authored(2).size == 1 && app.graph.authored(3).size == 1
            }

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.get("/feed?person=1&limit=20").body() shouldBe
                """{"found":true,"messages":[${messageJson(20, 3, 200, "m20", 100)},${messageJson(10, 2, 100, "m10", 100)}]}"""

            // Unfriend 3, then have 3 post again: the removal narrows the
            // NEXT pull's scope (4q9is-D6), so 3's leg is not read at all and
            // the new post is never seen. Same date as the add: SocialGraph's
            // undirected removal removes by element equality (SocialInterestTest
            // pins the same idiom).
            app.graph.removeKnows(1, 3, 2)
            app.graph.addPost(Message(21, creatorId = 3, creationDate = 300, content = "m21", forumId = 100))
            awaitUntil("the unfriend and the new post to settle") {
                app.graph.personFacts(1).none { it is Knows && it.otherId == 3L } &&
                    app.graph.authored(3).size == 2
            }

            probe.get("/feed?person=1&limit=20").body() shouldBe
                """{"found":true,"messages":[${messageJson(10, 2, 100, "m10", 100)}]}"""
        } finally {
            app.stop()
        }
    }

    @Test
    fun `a friend change keeps the cached session's retained frontiers`() {
        val readers = mutableListOf<RecordingReader>()
        val app = SocialApp(port = 0, reader = { host -> RecordingReader(HostBoundedReader(host)).also { readers += it } }).start()
        try {
            app.graph.addPerson(Person(1, "V", "One"))
            app.graph.addPerson(Person(2, "A", "Two"))
            app.graph.addPerson(Person(3, "B", "Three"))
            app.graph.addKnows(1, 2, 1)
            app.graph.addForum(Forum(100, "f", moderatorId = 1))
            app.graph.addPost(Message(10, creatorId = 2, creationDate = 100, content = "m10", forumId = 100))
            awaitUntil("initial post to settle") { app.graph.authored(2).size == 1 }

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            probe.get("/feed?person=1&limit=20").body() shouldBe
                """{"found":true,"messages":[${messageJson(10, 2, 100, "m10", 100)}]}"""

            app.graph.addKnows(1, 3, 2)
            app.graph.addPost(Message(20, creatorId = 3, creationDate = 300, content = "m20", forumId = 100))
            awaitUntil("the new friend and post to settle") {
                app.graph.personFacts(1).count { it is Knows } == 2 && app.graph.authored(3).size == 1
            }

            probe.get("/feed?person=1&limit=20").body() shouldBe
                """{"found":true,"messages":[${messageJson(20, 3, 300, "m20", 100)},${messageJson(10, 2, 100, "m10", 100)}]}"""

            // A's leg (person 2, an unchanged friend across both calls) must
            // have been asked with a retained `since` on this second pull —
            // under flfkm.4's rebuild-on-scope-change, the whole session (and
            // every leg's frontier) was replaced, so this would have been null.
            val authoredARef = app.pipeline.families.authored.getOrSpawn(2).ref
            val lastRequestForA = readers.single().requestsFor(authoredARef).last()
            (lastRequestForA.since != null) shouldBe true
        } finally {
            app.stop()
        }
    }

    /**
     * Wraps a [BoundedReader], answering with a [CompletableFuture] that is
     * never completed for any ref present in [stuck] and delegating
     * otherwise. Copy of `SocialReadRefusalTest.StuckReader`'s shape
     * (deliberately duplicated — that class is `private` to its own file),
     * used here to hold a `/feed` pull's leg read open indefinitely rather
     * than to hold a short read open.
     */
    private class StuckLegReader(
        private val delegate: BoundedReader,
        private val stuck: MutableMap<CellRef, CompletableFuture<StateReadResult>>,
    ) : BoundedReader {
        override fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> =
            stuck[ref] ?: delegate.read(ref, request)
    }

    /**
     * `computenet-1iz73`: two `/feed` requests for the same viewer, the
     * second arriving while the first's pull legs are still in flight, must
     * never surface `FeedSession.pull()`'s reentrancy `IllegalStateException`
     * as an uncaught `ExecutionException`. `feedSessions.compute(person)` is
     * atomic and hands both requests the same cached [FeedSession] once its
     * scope matches; the race is in what happens next, at `session.pull()`.
     *
     * Reproduced deterministically, no sleeps: [StuckLegReader] holds person
     * 2's `snb-authored` leg read open forever, so the first `/feed` call's
     * pull never finishes and its `FeedSession.inFlight` never resets. A
     * short [SocialApp.shortReadTimeoutSeconds] override means the first call
     * itself answers `503 TIMEOUT` promptly (documented behavior, unrelated
     * to this bug) — DemoShell's single dispatcher thread only frees up once
     * `respondOutcome` gives up waiting, which is exactly how the orchestrator's
     * race analysis says the second call reaches the still-in-flight session in
     * practice. The second call then reaches the SAME cached session while its
     * first pull is still stuck: before the fix, `session.pull()` throws
     * `IllegalStateException` inside the composed future, which
     * `respondOutcome` (only catching `TimeoutException`) does not catch —
     * an uncaught `ExecutionException` propagates out of `handleFeed`, and
     * DemoShell's JDK `HttpServer` closes the connection without a response,
     * which surfaces to [HttpProbe] as a thrown exception rather than any
     * HTTP status. After the fix (`FeedSession.pullShared`), the second call
     * also just answers `503 TIMEOUT` — the leg is still stuck, so neither
     * call can ever get a real board, but neither throws.
     */
    @Test
    fun `computenet-1iz73 overlapping same-viewer feed pulls never surface FeedSession pull reentrancy`() {
        val stuck = ConcurrentHashMap<CellRef, CompletableFuture<StateReadResult>>()
        val app = SocialApp(
            port = 0,
            reader = { host -> StuckLegReader(HostBoundedReader(host), stuck) },
            shortReadTimeoutSeconds = 1L,
        ).start()
        try {
            app.graph.addPerson(Person(1, "V", "One"))
            app.graph.addPerson(Person(2, "A", "Two"))
            app.graph.addKnows(1, 2, 1)
            awaitUntil("the knows edge to settle") { app.graph.personFacts(1).any { it is Knows } }

            // The ref cannot be named before the cell it names has been
            // spawned (same constraint SocialReadRefusalTest documents for
            // `person`); force the spawn directly, mirroring how `pull()`
            // itself resolves a friend's leg via `families.authored.getOrSpawn`.
            val authoredRef = app.pipeline.families.authored.getOrSpawn(2).ref
            stuck[authoredRef] = CompletableFuture() // never completes: the leg is stuck forever

            val probe = HttpProbe("http://localhost:${app.boundPort}")

            val first = probe.get("/feed?person=1&limit=20")
            first.statusCode() shouldBe 503
            (""""refused":"TIMEOUT"""" in first.body()) shouldBe true

            // Overlapping request for the same viewer: reaches the same
            // cached FeedSession (same scope, still holding its first pull's
            // stuck leg). Must not throw.
            val second = probe.get("/feed?person=1&limit=20")
            second.statusCode() shouldBe 503
            (""""refused":"TIMEOUT"""" in second.body()) shouldBe true
        } finally {
            app.stop()
        }
    }

    // --- /replies (IC8) ---------------------------------------------------------

    @Test
    fun `SOC1-CREAD-01 replies returns 23 21 22 for the IC8 example graph rebuilt through the app`() {
        val app = SocialApp(port = 0).start()
        try {
            app.graph.addPerson(Person(1, "P", "One"))
            app.graph.addPerson(Person(2, "Q", "Two"))
            app.graph.addPerson(Person(3, "R", "Three"))
            app.graph.addForum(Forum(100, "f", moderatorId = 1))
            app.graph.addPost(Message(10, creatorId = 1, creationDate = 1, content = "post10", forumId = 100))
            app.graph.addPost(Message(11, creatorId = 1, creationDate = 2, content = "post11", forumId = 100))
            app.graph.addComment(Message(21, creatorId = 2, creationDate = 5, content = "c21", replyOfId = 10))
            app.graph.addComment(Message(22, creatorId = 3, creationDate = 5, content = "c22", replyOfId = 11))
            app.graph.addComment(Message(23, creatorId = 2, creationDate = 9, content = "c23", replyOfId = 11))

            awaitUntil("the IC8 example graph to settle", timeoutMs = 20_000) {
                app.graph.authored(1).size == 2 &&
                    app.graph.messageFacts(21).any { it is MessageFact.Body } &&
                    app.graph.messageFacts(22).any { it is MessageFact.Body } &&
                    app.graph.messageFacts(23).any { it is MessageFact.Body }
            }

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val resp = probe.get("/replies?person=1&limit=20")
            resp.statusCode() shouldBe 200
            resp.body() shouldBe """{"found":true,"messages":[""" +
                "${messageJson(23, 2, 9, "c23", replyOfId = 11)}," +
                "${messageJson(21, 2, 5, "c21", replyOfId = 10)}," +
                "${messageJson(22, 3, 5, "c22", replyOfId = 11)}]}"
        } finally {
            app.stop()
        }
    }

    // --- /fof (IC3) --------------------------------------------------------------

    @Test
    fun `SOC1-CREAD-01 fof returns the IC3 example rows, and 400s on missing to or equal countries`() {
        val app = SocialApp(port = 0).start()
        try {
            app.graph.addPerson(Person(1, "V", "One"))
            app.graph.addPerson(Person(2, "A", "Two"))
            app.graph.addPerson(Person(3, "B", "Three"))
            app.graph.addPerson(Person(4, "C", "Four"))
            app.graph.addPerson(Person(5, "D", "Five"))
            app.graph.addKnows(1, 2, 1)
            app.graph.addKnows(1, 3, 2)
            app.graph.addKnows(2, 4, 3)
            app.graph.addKnows(3, 5, 4)
            app.graph.addForum(Forum(900, "f", moderatorId = 1))
            app.graph.addPost(Message(10, creatorId = 2, creationDate = 10, content = "a1", forumId = 900, locationCountryId = 6))
            app.graph.addPost(Message(11, creatorId = 2, creationDate = 20, content = "a2", forumId = 900, locationCountryId = 7))
            app.graph.addPost(Message(12, creatorId = 4, creationDate = 30, content = "c1", forumId = 900, locationCountryId = 6))
            app.graph.addPost(Message(13, creatorId = 4, creationDate = 30, content = "c2", forumId = 900, locationCountryId = 7))
            app.graph.addPost(Message(14, creatorId = 5, creationDate = 40, content = "d1", forumId = 900, locationCountryId = 6))

            awaitUntil("the IC3 example graph to settle", timeoutMs = 20_000) {
                app.graph.personFacts(1).count { it is Knows } == 2 &&
                    app.graph.authored(2).size == 2 &&
                    app.graph.authored(4).size == 2 &&
                    app.graph.authored(5).size == 1
            }

            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val resp = probe.get("/fof?person=1&countryA=6&countryB=7&from=0&to=100")
            resp.statusCode() shouldBe 200
            resp.body() shouldBe """{"found":true,"rows":[{"personId":2,"countA":1,"countB":1},{"personId":4,"countA":1,"countB":1}]}"""

            probe.get("/fof?person=1&countryA=6&countryB=7&from=0").statusCode() shouldBe 400 // missing to
            probe.get("/fof?person=1&countryA=6&countryB=6&from=0&to=100").statusCode() shouldBe 400 // equal countries
            // review repair (computenet-flfkm.4): the acceptance criterion also names
            // `from > to` as a 400 case; nothing in this file asserted it, and a mutant
            // that disabled SocialApp.kt's `if (from > to) throw Bad(...)` guard still
            // passed every test (mutation observed 2026-09-23).
            probe.get("/fof?person=1&countryA=6&countryB=7&from=100&to=0").statusCode() shouldBe 400 // from > to
        } finally {
            app.stop()
        }
    }

    // --- [SOC1-CREAD-03] 404 guard -----------------------------------------------

    @Test
    fun `SOC1-CREAD-03 shortest ic1 ic13 ic14 are 404 and slash stays 200`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            for (path in listOf("/shortest", "/ic1", "/ic13", "/ic14")) {
                probe.get(path).statusCode() shouldBe 404
            }
            probe.get("/").statusCode() shouldBe 200
        } finally {
            app.stop()
        }
    }

    // --- [SOC1-CREAD-02] /state surface half --------------------------------------

    @Test
    fun `SOC1-CREAD-02 state contains the exact queries literal`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val state = probe.state()
            ("""queries":{"ic2":"served","ic8":"served","ic3":"served","ic5":"finding","ic6":"finding","ic12":"finding"}""" in state) shouldBe true
        } finally {
            app.stop()
        }
    }
}
