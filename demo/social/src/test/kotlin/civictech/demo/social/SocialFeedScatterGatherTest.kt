package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.Cursor
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.concurrent.TimeUnit

/**
 * `[SOC1-FEED-01]`, `[SOC1-FEED-02]`, `[SOC1-FEED-08]` (epic `computenet-07k`
 * B5, B6) — the fan-out half of PN-5, for feature `computenet-8eb53` task
 * `computenet-8eb53.2`. Task 1 (`computenet-8eb53.1`, `Feed.kt`) already
 * implements leg enumeration from the viewer's [Interest.Ranges] scope
 * (8eb53-D3); this pins that the leg count is bounded by the scope and not by
 * the graph, and that no single cell answers a pull on its own.
 *
 * **Amended by the review of task 1** (bead comment on `computenet-8eb53.2`):
 * a scope key not in the per-pull `families.authored.keys()` snapshot gets
 * NO read, no `getOrSpawn`, and no entry in [PullReport.legs] — there is no
 * `LegOutcome.Deferred(NOT_HOSTED)`, contrary to the feature design prose.
 * The never-posted-friend test below pins that behaviour instead.
 *
 * Rig (8eb53-D8): one [SimulationController] host, so a pull's pages land on
 * `runToIdle()` and every assertion is deterministic.
 */
class SocialFeedScatterGatherTest {

    private companion object {
        const val V = 1L
        const val A = 2L
        const val B = 3L
        const val C = 4L
        const val D = 5L
        const val FORUM = 100L
    }

    private class Rig(scope: List<Long> = listOf(A, B, C), pageLimit: Int = 200) {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val pipeline = SnbPipeline.build(host, journalDir = null, registry = registry)
        val graph = SocialGraph(host, pipeline)
        val plainReader = HostBoundedReader(host)
        val recorder = RecordingReader(plainReader)
        val session: FeedSession by lazy {
            FeedSession(
                viewer = V,
                scope = Interest.Ranges(scope.map { Interest.Ranges.Range(it, it + 1) }),
                families = pipeline.families,
                registry = registry,
                reader = recorder,
                pageLimit = pageLimit,
            )
        }

        init {
            graph.addPerson(Person(V, "V", "viewer"))
            graph.addPerson(Person(A, "A", "author"))
            graph.addPerson(Person(B, "B", "author"))
            graph.addPerson(Person(C, "C", "author"))
            graph.addPerson(Person(D, "D", "author"))
            graph.addForum(Forum(FORUM, "forum", V))
            controller.runToIdle()
        }

        fun post(id: Long, author: Long) {
            graph.addPost(Message(id, author, id, "m$id", forumId = FORUM))
            controller.runToIdle()
        }

        fun ref(id: Long): CellRef = pipeline.families.authored.getOrSpawn(id).ref

        fun pull(): PullReport {
            val future = session.pull()
            controller.runToIdle()
            return future.get(20, TimeUnit.SECONDS)
        }

        fun ids(board: Set<Message>): Set<Long> = board.map { it.id }.toSet()

        /** Every present [Message] element the recorder saw answered for [ref], across all its pages. */
        fun presentMessages(ref: CellRef): Set<Message> =
            recorder.resultsFor(ref)
                .filterIsInstance<StateReadResult.Page>()
                .flatMap { it.page.entries }
                .filterIsInstance<SetCell.SetStateEntry<*>>()
                .filter { it.present }
                .mapNotNull { it.element as? Message }
                .toSet()

        /**
         * A synchronous full walk of [ref] through [plainReader] (test-side
         * O(total) is fine; production leg enumeration never does this,
         * per 8eb53-D3). Follows `next` cursors to exhaustion under one
         * `since = null` request.
         */
        fun walkToExhaustion(ref: CellRef): List<Serializable> {
            val out = mutableListOf<Serializable>()
            var cursor: Cursor? = null
            while (true) {
                val future = plainReader.read(ref, StateRead(cursor = cursor, limit = 200))
                controller.runToIdle()
                val result = future.get(20, TimeUnit.SECONDS)
                val page = (result as StateReadResult.Page).page
                out += page.entries
                cursor = page.next ?: return out
            }
        }
    }

    // --- [SOC1-FEED-01] -----------------------------------------------------

    @Test
    fun `SOC1-FEED-01 one read per friend holding posts, refs the three authored refs, board the union of their pages`() {
        val rig = Rig()
        rig.post(10, A); rig.post(11, A); rig.post(20, B); rig.post(30, C); rig.post(31, C); rig.post(32, C)
        rig.recorder.reset()

        val report = rig.pull()

        val refA = rig.ref(A)
        val refB = rig.ref(B)
        val refC = rig.ref(C)
        rig.recorder.count shouldBe 3
        rig.recorder.calls().map { it.ref }.toSet() shouldBe setOf(refA, refB, refC)
        // getOrSpawn on an already-live key mints nothing (GraphLocator idiom): each ref is the
        // same one families.authored already holds for that author's posts.
        report.legs.keys shouldBe setOf(refA, refB, refC)

        val union = rig.presentMessages(refA) + rig.presentMessages(refB) + rig.presentMessages(refC)
        rig.ids(union) shouldBe setOf(10L, 11L, 20L, 30L, 31L, 32L)
        rig.session.board() shouldBe union
    }

    // --- [SOC1-FEED-08] -----------------------------------------------------

    @Test
    fun `SOC1-FEED-08 1000 unrelated persons, 20 of them posting, leave the leg count and refs unchanged`() {
        val rig = Rig()
        rig.post(10, A); rig.post(11, A); rig.post(20, B); rig.post(30, C); rig.post(31, C); rig.post(32, C)
        rig.recorder.reset()
        val first = rig.pull()
        val expectedRefs = setOf(rig.ref(A), rig.ref(B), rig.ref(C))
        rig.recorder.count shouldBe 3
        first.legs.keys shouldBe expectedRefs

        // 1000 strangers: persons only, no `knows` edges, so V's scope (fixed at construction) is
        // untouched. 20 of them HOLD authored cells by the time of the next pull — a leg
        // enumeration that scanned families.authored.keys() would then see 23 live keys.
        for (i in 0 until 1000) rig.graph.addPerson(Person(10_000L + i, "p$i", "x"))
        rig.controller.runToIdle()
        for (i in 0 until 20) {
            val strangerId = 10_000L + i
            rig.graph.addPost(Message(20_000L + i, strangerId, 20_000L + i, "s$i", forumId = FORUM))
        }
        rig.controller.runToIdle()

        rig.recorder.reset()
        val second = rig.pull()

        rig.recorder.count shouldBe 3
        rig.recorder.calls().map { it.ref }.toSet() shouldBe expectedRefs
        second.legs.keys shouldBe expectedRefs
    }

    // --- [SOC1-FEED-02] -----------------------------------------------------

    @Test
    fun `SOC1-FEED-02 no cell holds Message elements from more than one author, and no single leg equals board`() {
        val rig = Rig()
        rig.post(10, A); rig.post(11, A); rig.post(20, B); rig.post(30, C); rig.post(31, C); rig.post(32, C)
        rig.recorder.reset()
        rig.pull()
        val board = rig.session.board()
        board.size shouldBe 6

        val allRefs = buildList {
            rig.pipeline.families.person.keys().forEach { add(rig.pipeline.families.person.getOrSpawn(it).ref) }
            rig.pipeline.families.authored.keys().forEach { add(rig.pipeline.families.authored.getOrSpawn(it).ref) }
            rig.pipeline.families.forum.keys().forEach { add(rig.pipeline.families.forum.getOrSpawn(it).ref) }
            rig.pipeline.families.message.keys().forEach { add(rig.pipeline.families.message.getOrSpawn(it).ref) }
            add(rig.pipeline.statics.tags.ref)
            add(rig.pipeline.statics.tagClasses.ref)
            add(rig.pipeline.statics.places.ref)
            add(rig.pipeline.statics.organisations.ref)
        }
        // Non-vacuity: the fixture posted 6 messages by 3 authors, so at least the 3 authored
        // cells and the 6 per-message `snb-message` cells must be visited and carry Message content.
        (allRefs.size >= 9) shouldBe true

        var cellsWithMessages = 0
        for (ref in allRefs) {
            val entries = rig.walkToExhaustion(ref)
            val messages = entries
                .filterIsInstance<SetCell.SetStateEntry<*>>()
                .filter { it.present }
                .mapNotNull { entry ->
                    when (val element = entry.element) {
                        is Message -> element
                        is MessageFact.Body -> element.message
                        else -> null
                    }
                }
            if (messages.isEmpty()) continue
            cellsWithMessages++

            val creatorIds = messages.map { it.creatorId }.toSet()
            (creatorIds.size <= 1) shouldBe true

            val elementSet = messages.toSet()
            board.containsAll(elementSet) shouldBe true
            (elementSet == board) shouldBe false
        }
        (cellsWithMessages >= 9) shouldBe true
    }

    // --- AMENDS (task 1 review): never-posted friend --------------------------

    @Test
    fun `AMENDS a scope friend who never posted gets no read, no spawn, and no report entry, then joins after their first post`() {
        val rig = Rig(scope = listOf(A, D))
        rig.post(10, A)
        rig.recorder.reset()
        val keysBefore = rig.pipeline.families.authored.keys()
        (D in keysBefore) shouldBe false

        val first = rig.pull()

        val refA = rig.ref(A)
        rig.recorder.calls().map { it.ref }.toSet() shouldBe setOf(refA)
        rig.pipeline.families.authored.keys() shouldBe keysBefore
        first.legs.keys shouldBe setOf(refA)

        rig.post(50, D) // spawns D's authored cell as a side effect of the write, not of the pull
        rig.recorder.reset()
        val refD = rig.ref(D) // idempotent: D's cell is already live from the post above
        val second = rig.pull()

        rig.recorder.requestsFor(refD).single().since shouldBe null
        second.legs.keys shouldBe setOf(refA, refD)
        (50L in rig.ids(rig.session.board())) shouldBe true
    }
}
