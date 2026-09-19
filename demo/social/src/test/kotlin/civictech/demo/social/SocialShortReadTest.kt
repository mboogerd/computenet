package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The API half of the SOC1-SREAD rules (feature `computenet-rx8om`, task
 * `computenet-rx8om.1`, design rx8om-D2..D7), driven directly against
 * [ShortReads] — no HTTP.
 *
 * Task `computenet-rx8om.2` AMENDS this file with the HTTP half of
 * `[SOC1-SREAD-01]`/`[SOC1-SREAD-03]`; every helper here is `private` and the
 * graph builder ([exampleGraph]) is reusable from that half.
 *
 * The host is a **threaded** [ManagedHost], the configuration `SocialApp`
 * builds (`SocialApp.kt:42`), so a short read's future completes on the
 * host's own scheduler without a `step()` pump; [settled] is the bounded wait
 * that replaces one, and the read counters are only ever read after it.
 */
class SocialShortReadTest {

    // --- the example graph (feature design rx8om, "Examples") ---------------

    private val ada = Person(1, "Ada", "Lovelace")
    private val bob = Person(2, "Bob", "Brown")
    private val forum = Forum(100, "f", moderatorId = 1)
    private val post10 = Message(10, creatorId = 1, creationDate = 7, content = "hi", forumId = 100)
    private val comment11 = Message(11, creatorId = 2, creationDate = 8, content = "reply", replyOfId = 10)
    private val comment12 = Message(12, creatorId = 1, creationDate = 9, content = "reply2", replyOfId = 11)

    private class Fixture(
        val families: SnbPipeline.Families,
        val graph: SocialGraph,
        val reader: CountingReader,
        val reads: ShortReads,
    )

    /**
     * Records every `(ref, request)` pair passed through, so `[SOC1-SREAD-02]`
     * counts reads rather than timing them.
     */
    private class CountingReader(private val delegate: BoundedReader) : BoundedReader {
        val calls = CopyOnWriteArrayList<Pair<CellRef, StateRead>>()

        override fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> {
            calls += ref to request
            return delegate.read(ref, request)
        }

        fun reset() = calls.clear()
        val count: Int get() = calls.size
        fun refs(): List<CellRef> = calls.map { it.first }
    }

    private fun exampleGraph(): Fixture {
        val host = ManagedHost(registry = LocationRegistry())
        val pipeline = SnbPipeline.build(host, journalDir = null)
        val graph = SocialGraph(host, pipeline)

        graph.addPerson(ada)
        graph.addPerson(bob)
        graph.addKnows(1, 2, 5)
        graph.addForum(forum)
        graph.addPost(post10)
        graph.addComment(comment11)
        graph.addComment(comment12)

        settled(graph)
        val reader = CountingReader(HostBoundedReader(host))
        return Fixture(
            families = pipeline.families,
            graph = graph,
            reader = reader,
            reads = ShortReads(reader, GraphLocator(graph, pipeline.families)),
        )
    }

    /** Every fact the seven reads depend on has reached its cell's observation sink. */
    private fun settled(graph: SocialGraph) = awaitUntil("the example graph to settle", timeoutMs = 20_000) {
        graph.personFacts(1).any { it is PersonFact.Profile } &&
            graph.personFacts(1).any { it is Knows } &&
            graph.personFacts(2).any { it is PersonFact.Profile } &&
            graph.messageFacts(10).any { it is MessageFact.Body } &&
            graph.messageFacts(10).any { it is MessageFact.Reply } &&
            graph.messageFacts(11).any { it is MessageFact.Body } &&
            graph.messageFacts(11).any { it is MessageFact.Reply } &&
            graph.messageFacts(12).any { it is MessageFact.Body } &&
            graph.authored(1).size == 2 &&
            graph.authored(2).size == 1
    }

    private fun <T> CompletableFuture<T>.answer(): T = get(20, TimeUnit.SECONDS)

    // --- [SOC1-SREAD-01] ----------------------------------------------------

    @Test
    fun `SOC1-SREAD-01 IS1 to IS7 answer the example graph`() {
        val f = exampleGraph()

        f.reads.is1(1).answer() shouldBe ReadOutcome.Found(ada)
        f.reads.is2(1).answer() shouldBe ReadOutcome.Found(listOf(comment12, post10))
        f.reads.is3(1).answer() shouldBe ReadOutcome.Found(listOf(Knows(2, 5)))
        f.reads.is4(11).answer() shouldBe ReadOutcome.Found(comment11)
        f.reads.is5(11).answer() shouldBe ReadOutcome.Found(2L)
        f.reads.is6(12).answer() shouldBe ReadOutcome.Found(100L)
        f.reads.is7(10).answer() shouldBe ReadOutcome.Found(listOf(Reply(comment11, authorKnowsCreator = true)))
    }

    @Test
    fun `SOC1-SREAD-01 IS2 for a known person who has authored nothing is an empty list, not Empty`() {
        val f = exampleGraph()
        val cy = Person(3, "Cy", "Clone")
        f.graph.addPerson(cy)
        awaitUntil("person 3 to settle") { f.graph.personFacts(3).any { it is PersonFact.Profile } }

        f.reads.is2(3).answer() shouldBe ReadOutcome.Found(emptyList())
    }

    // --- [SOC1-SREAD-02] ----------------------------------------------------

    @Test
    fun `SOC1-SREAD-02 IS1 IS4 and IS5 each cost exactly one read of the owning cell`() {
        val f = exampleGraph()
        val personRef = f.families.person.getOrSpawn(1).ref
        val messageRef = f.families.message.getOrSpawn(11).ref

        f.reader.reset()
        f.reads.is1(1).answer() shouldBe ReadOutcome.Found(ada)
        f.reader.count shouldBe 1
        f.reader.refs() shouldBe listOf(personRef)

        f.reader.reset()
        f.reads.is4(11).answer() shouldBe ReadOutcome.Found(comment11)
        f.reader.count shouldBe 1
        f.reader.refs() shouldBe listOf(messageRef)

        f.reader.reset()
        f.reads.is5(11).answer() shouldBe ReadOutcome.Found(2L)
        f.reader.count shouldBe 1
        f.reader.refs() shouldBe listOf(messageRef)
    }

    @Test
    fun `SOC1-SREAD-02 IS6 for comment 12 costs one read per hop to the root post`() {
        val f = exampleGraph()
        val refs = listOf(12L, 11L, 10L).map { f.families.message.getOrSpawn(it).ref }

        f.reader.reset()
        f.reads.is6(12).answer() shouldBe ReadOutcome.Found(100L)
        f.reader.count shouldBe 3
        f.reader.refs() shouldBe refs
    }

    @Test
    fun `SOC1-SREAD-02 IS2 reads only the authored cell and IS3 only the person cell`() {
        val f = exampleGraph()
        val personRef = f.families.person.getOrSpawn(1).ref
        val authoredRef = f.families.authored.getOrSpawn(1).ref

        f.reader.reset()
        f.reads.is2(1).answer() shouldBe ReadOutcome.Found(listOf(comment12, post10))
        (f.reader.count >= 1) shouldBe true
        f.reader.refs().toSet() shouldBe setOf(authoredRef)

        f.reader.reset()
        f.reads.is3(1).answer() shouldBe ReadOutcome.Found(listOf(Knows(2, 5)))
        (f.reader.count >= 1) shouldBe true
        f.reader.refs().toSet() shouldBe setOf(personRef)
    }

    @Test
    fun `SOC1-SREAD-02 IS7 costs the parent plus one per reply plus the creator's person cell`() {
        val f = exampleGraph()
        val expected = listOf(
            f.families.message.getOrSpawn(10).ref,
            f.families.message.getOrSpawn(11).ref,
            f.families.person.getOrSpawn(1).ref,
        )

        f.reader.reset()
        f.reads.is7(10).answer() shouldBe ReadOutcome.Found(listOf(Reply(comment11, authorKnowsCreator = true)))
        f.reader.count shouldBe 3
        f.reader.refs() shouldBe expected
    }

    // --- [SOC1-SREAD-03] ----------------------------------------------------

    @Test
    fun `SOC1-SREAD-03 an unknown id is Empty, costs no read and spawns no cell`() {
        val f = exampleGraph()
        val before = listOf(
            f.families.person.keys().toSet(),
            f.families.authored.keys().toSet(),
            f.families.forum.keys().toSet(),
            f.families.message.keys().toSet(),
        )

        f.reader.reset()
        f.reads.is1(999).answer() shouldBe ReadOutcome.Empty
        f.reads.is2(999).answer() shouldBe ReadOutcome.Empty
        f.reads.is3(999).answer() shouldBe ReadOutcome.Empty
        f.reads.is4(999).answer() shouldBe ReadOutcome.Empty
        f.reads.is5(999).answer() shouldBe ReadOutcome.Empty
        f.reads.is6(999).answer() shouldBe ReadOutcome.Empty
        f.reads.is7(999).answer() shouldBe ReadOutcome.Empty

        f.reader.count shouldBe 0
        listOf(
            f.families.person.keys().toSet(),
            f.families.authored.keys().toSet(),
            f.families.forum.keys().toSet(),
            f.families.message.keys().toSet(),
        ) shouldBe before
    }

    // --- [SOC1-FIND-03] -----------------------------------------------------

    /** Answers [result] to every read, recording how many it was asked for. */
    private class StubReader(private val result: StateReadResult) : BoundedReader {
        var count = 0
            private set

        override fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> {
            count++
            return CompletableFuture.completedFuture(result)
        }
    }

    /** Answers [ref] (or null) for every id and every family, spawning nothing. */
    private class StubLocator(private val ref: CellRef?) : EntityLocator {
        override fun person(id: Long): CellRef? = ref
        override fun authored(id: Long): CellRef? = ref
        override fun message(id: Long): CellRef? = ref
    }

    @Test
    fun `SOC1-FIND-03 ShortReads runs on a stub reader and stub locator with no host at all`() {
        val ref = CellRef(UUID.randomUUID())
        val tag = Timestamp(UUID.randomUUID(), 1)
        val onePage = StateReadResult.Page(
            StatePage(
                entries = listOf(
                    SetCell.SetStateEntry(PersonFact.Profile(ada), setOf(tag), emptySet())
                )
            )
        )

        val paging = StubReader(onePage)
        ShortReads(paging, StubLocator(ref)).is1(1).answer() shouldBe ReadOutcome.Found(ada)
        paging.count shouldBe 1

        val refusing = StubReader(StateReadResult.Unavailable(StateReadResult.Reason.MIGRATING))
        ShortReads(refusing, StubLocator(ref)).is1(1).answer() shouldBe
            ReadOutcome.Refused(StateReadResult.Reason.MIGRATING)
        refusing.count shouldBe 1

        val unreached = StubReader(onePage)
        ShortReads(unreached, StubLocator(null)).is1(1).answer() shouldBe ReadOutcome.Empty
        unreached.count shouldBe 0
    }
}
