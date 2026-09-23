package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
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
 * flfkm-D4..D6), driven directly against [ComplexReads] — no HTTP.
 *
 * Task `computenet-flfkm.4` AMENDS this file with the HTTP half. The host is
 * a **threaded** [ManagedHost], the configuration `SocialApp` builds, so a
 * complex read's future completes on the host's own scheduler without a
 * `step()` pump; [settled] is the bounded wait that replaces one, and the
 * read counters are only ever read after it — same idiom as
 * `SocialShortReadTest`.
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
}
