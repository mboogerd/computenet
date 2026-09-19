package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.StateReadResult
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.observe.View
import civictech.cell.observe.observe
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * `[SOC1-FEED-03..07]` — B7, B8, B9 of epic `computenet-07k` — plus the
 * opening-frontier pin (8eb53-D2) and the interest-registration pin
 * (8eb53-D4), for feature `computenet-8eb53` task `computenet-8eb53.1`.
 *
 * Rig (8eb53-D8): one [SimulationController] host, so a pull's pages land on
 * `runToIdle()` and every assertion is deterministic. Viewer V = 1 knows A = 2
 * and B = 3; their posts are interleaved A, B, A, B, A (B7's fixture).
 *
 * **What B7 can and cannot show here (8eb53-D9).** Each `snb-authored` cell
 * mints tags from its own source, so a frontier merged across legs would drop
 * nothing on this data — the spec's drop needs one shared source. B7's teeth
 * are therefore the REQUEST: pull 2's `since` to B must be B's own retained
 * frontier, naming only B's source. A merged implementation sends A's source
 * to B and fails that equality.
 */
class SocialFeedFrontierTest {

    private companion object {
        const val V = 1L
        const val A = 2L
        const val B = 3L
        const val FORUM = 100L
    }

    private class Rig(pageLimit: Int = 200) {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val pipeline = SnbPipeline.build(host, journalDir = null, registry = registry)
        val graph = SocialGraph(host, pipeline)
        val refused: MutableMap<CellRef, StateReadResult.Reason> = ConcurrentHashMap()
        val recorder = RecordingReader(RefusingReader(HostBoundedReader(host), refused))
        val session: FeedSession by lazy {
            FeedSession(
                viewer = V,
                scope = Interest.Ranges(listOf(A, B).map { Interest.Ranges.Range(it, it + 1) }),
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
            graph.addForum(Forum(FORUM, "forum", V))
            controller.runToIdle()
        }

        fun post(id: Long, author: Long) {
            graph.addPost(Message(id, author, id, "m$id", forumId = FORUM))
            controller.runToIdle()
        }

        /** B7's interleaved history: A, B, A, B, A. */
        fun interleaved() {
            post(10, A); post(20, B); post(11, A); post(21, B); post(12, A)
        }

        fun ref(id: Long): CellRef = pipeline.families.authored.getOrSpawn(id).ref

        fun pull(): PullReport {
            val future = session.pull()
            controller.runToIdle()
            return future.get(20, TimeUnit.SECONDS)
        }

        /** Every tag source on the pages [ref]'s recorded reads answered with. */
        fun pageSources(ref: CellRef): Set<UUID> =
            recorder.resultsFor(ref)
                .filterIsInstance<StateReadResult.Page>()
                .flatMap { it.page.entries }
                .filterIsInstance<SetCell.SetStateEntry<*>>()
                .flatMap { it.addTags + it.delTags }
                .map { it.sourceId }
                .toSet()

        fun ids(board: Set<Message>): Set<Long> = board.map { it.id }.toSet()
    }

    // --- [SOC1-FEED-03] ---------------------------------------------------------

    @Test
    fun `SOC1-FEED-03 one retained frontier per answered leg and no frontier construction or per-source read in Feed kt`() {
        val rig = Rig()
        rig.interleaved()
        val report = rig.pull()

        report.legs.keys shouldBe setOf(rig.ref(A), rig.ref(B))
        report.legs.values.forEach { it.shouldBeInstanceOf<LegOutcome.Answered>() }
        rig.session.frontiers().keys shouldBe setOf(rig.ref(A), rig.ref(B))
        rig.ids(rig.session.board()) shouldBe setOf(10L, 11L, 12L, 20L, 21L)

        // A Gradle test's working directory is the project directory (ModuleDependencyTest relies on the same).
        val source = File("src/main/kotlin/civictech/demo/social/Feed.kt").readText()
        ("TagFrontier(" in source) shouldBe false
        (".perSource" in source) shouldBe false
    }

    // --- [SOC1-FEED-04] ---------------------------------------------------------

    @Test
    fun `SOC1-FEED-04 each leg is sent its own retained frontier and later posts on either leg arrive`() {
        val rig = Rig()
        rig.interleaved()
        rig.pull()
        val refA = rig.ref(A)
        val refB = rig.ref(B)
        val retainedA = rig.session.frontiers().getValue(refA)
        val retainedB = rig.session.frontiers().getValue(refB)
        val sourcesA = rig.pageSources(refA)
        val sourcesB = rig.pageSources(refB)
        sourcesB.isNotEmpty() shouldBe true
        sourcesA.intersect(sourcesB) shouldBe emptySet()

        rig.post(13, A)
        rig.recorder.reset()
        val second = rig.pull()

        val sinceB = rig.recorder.requestsFor(refB).single().since
        sinceB shouldBe retainedB
        sinceB!!.perSource.keys shouldBe sourcesB
        sinceB.perSource.keys.intersect(sourcesA) shouldBe emptySet()
        rig.recorder.requestsFor(refA).single().since shouldBe retainedA
        (second.legs.getValue(refA) as LegOutcome.Answered).delivered shouldBe 1
        (second.legs.getValue(refB) as LegOutcome.Answered).delivered shouldBe 0
        (13L in rig.ids(rig.session.board())) shouldBe true

        rig.post(22, B)
        val third = rig.pull()
        (22L in rig.ids(rig.session.board())) shouldBe true
        (third.legs.getValue(refB) as LegOutcome.Answered).delivered shouldBe 1
        (third.legs.getValue(refA) as LegOutcome.Answered).delivered shouldBe 0
    }

    // --- [SOC1-FEED-05] ---------------------------------------------------------

    @Test
    fun `SOC1-FEED-05 a MIGRATING leg is deferred without a frontier and later delivers everything it authored`() {
        val rig = Rig()
        rig.interleaved()
        val refA = rig.ref(A)
        val refB = rig.ref(B)
        rig.refused[refB] = StateReadResult.Reason.MIGRATING

        val first = rig.pull()
        first.legs.getValue(refB) shouldBe LegOutcome.Deferred(StateReadResult.Reason.MIGRATING)
        first.legs.getValue(refA).shouldBeInstanceOf<LegOutcome.Answered>()
        rig.ids(rig.session.board()) shouldBe setOf(10L, 11L, 12L)
        rig.session.frontiers().keys shouldBe setOf(refA)

        rig.post(25, B) // authored while refused
        rig.refused.remove(refB)
        rig.recorder.reset()
        val second = rig.pull()

        rig.recorder.requestsFor(refB).single().since shouldBe null
        second.legs.getValue(refB).shouldBeInstanceOf<LegOutcome.Answered>()
        rig.ids(rig.session.board()) shouldBe setOf(10L, 11L, 12L, 20L, 21L, 25L)
        rig.session.frontiers().keys shouldBe setOf(refA, refB)
    }

    // --- [SOC1-FEED-06] ---------------------------------------------------------

    @Test
    fun `SOC1-FEED-06 PullReport has exactly one property, legs, and one own frontier per answered leg`() {
        // Java reflection: kotlin-reflect is not on :demo:social's test classpath. A Kotlin
        // property of a data class is one private instance field plus its getter.
        val fields = PullReport::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }
        fields shouldBe listOf("legs")
        val getters = PullReport::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) }
            .map { it.name }
        getters shouldBe listOf("getLegs")

        val rig = Rig()
        rig.interleaved()
        val report = rig.pull()
        val frontiers = report.legs.values.map { (it as LegOutcome.Answered).frontier }
        frontiers.size shouldBe 2
        frontiers.forEach { it shouldNotBe null }
        frontiers[0] shouldNotBe frontiers[1]
        report.legs.mapValues { (it.value as LegOutcome.Answered).frontier } shouldBe rig.session.frontiers()
    }

    // --- [SOC1-FEED-07] ---------------------------------------------------------

    private class Probe(rig: Rig, id: Long) {
        @Suppress("UNCHECKED_CAST")
        val cell = rig.pipeline.families.authored.getOrSpawn(id) as SetCell<Message>
        val taps = AtomicInteger()
        val observed = rig.host.observe(cell.ref, View.set<Message>())

        init {
            cell.outlet.tap(Use.fixed(Propagate<SetDelta<Message>> { taps.incrementAndGet() }, PortRef.generate()))
        }

        fun reading(): Triple<Any, Int, Set<Message>> = Triple(cell.outlet.waveState(), taps.get(), observed.current())
    }

    @Test
    fun `SOC1-FEED-07 a pull moves no wave state, no tap, no observed value and no dead letter`() {
        val rig = Rig()
        rig.interleaved()
        val probes = listOf(Probe(rig, A), Probe(rig, B))
        rig.controller.runToIdle()

        val before = probes.map { it.reading() }
        val deadLettersBefore = rig.host.supervisionAccounting().deadLetters
        val report = rig.pull()
        report.legs.size shouldBe 2
        rig.ids(rig.session.board()) shouldBe setOf(10L, 11L, 12L, 20L, 21L)

        probes.map { it.reading() } shouldBe before
        rig.host.supervisionAccounting().deadLetters shouldBe deadLettersBefore

        // the probes are live, so their stillness above is evidence: a write moves A's tap and view
        rig.post(13, A)
        probes[0].taps.get() shouldBe before[0].second + 1
        (probes[0].observed.current().map { it.id }.toSet()) shouldBe setOf(10L, 11L, 12L, 13L)
    }

    // --- 8eb53-D2: the opening frontier is retained, not the closing one ------------

    @Test
    fun `D2 a multi-page walk retains its first page's frontier, so a post landing mid-walk arrives next pull`() {
        val rig = Rig(pageLimit = 1)
        rig.post(10, A); rig.post(11, A); rig.post(12, A)
        val refA = rig.ref(A)
        // Land a fourth message in A's cell after page 1 of A's walk has answered: it is not in
        // the walk's frozen order (so on no page) but it is under the walk's closing stamp.
        // Applied directly on A's cell instance, synchronously, between two steps. Neither routed
        // path can land it mid-walk: a hosted inlet call is a data-band task, which drains only
        // after every priority-0 read task (ManagedHost.submitRead), so the walk would finish
        // first; and SocialGraph.addPost also spawns the message's own cell, driving the
        // simulation before the write is submitted.
        @Suppress("UNCHECKED_CAST")
        val cellA = rig.pipeline.families.authored.getOrSpawn(A) as SetCell<Message>
        val future = rig.session.pull()
        while (rig.recorder.resultsFor(refA).isEmpty()) check(rig.controller.step()) { "A's first page never answered" }
        cellA.inlet.call.add(Message(13, A, 13, "mid-walk", forumId = FORUM))
        rig.controller.runToIdle()
        val report = future.get(20, TimeUnit.SECONDS)

        val pages = rig.recorder.resultsFor(refA).map { (it as StateReadResult.Page).page }
        pages.size shouldBe 3
        rig.recorder.requestsFor(refA).map { it.cursor == null } shouldBe listOf(true, false, false)
        rig.ids(rig.session.board()) shouldBe setOf(10L, 11L, 12L)
        pages.first().caveats shouldBe emptySet()
        // precondition: the walk's closing stamp really is ahead of its opening one
        pages.last().frontier shouldNotBe pages.first().frontier

        rig.session.frontiers()[refA] shouldBe pages.first().frontier
        (report.legs.getValue(refA) as LegOutcome.Answered).frontier shouldBe pages.first().frontier
        rig.session.frontiers()[refA] shouldNotBe pages.last().frontier

        rig.pull()
        rig.ids(rig.session.board()) shouldBe setOf(10L, 11L, 12L, 13L)
    }

    // --- 8eb53-D4: per-author interest registration ------------------------------

    @Test
    fun `D4 each snb-authored cell registers its author's singleton range with the registry`() {
        val rig = Rig()
        rig.interleaved()
        rig.registry.interestOf(rig.ref(A)) shouldBe Interest.Ranges(listOf(Interest.Ranges.Range(A, A + 1)))
        rig.registry.interestOf(rig.ref(B)) shouldBe Interest.Ranges(listOf(Interest.Ranges.Range(B, B + 1)))
        rig.registry.interestOf(rig.ref(A)).overlaps(rig.registry.interestOf(rig.ref(B))) shouldBe false
    }
}
