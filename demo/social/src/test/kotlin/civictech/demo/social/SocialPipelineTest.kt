package civictech.demo.social

import civictech.cell.link.Interest
import civictech.testkit.SimWorld
import civictech.testkit.forEachSeed
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * B14/B15 for SOC1's update stream (epic `computenet-07k`, feature
 * `computenet-99qcg`, task `computenet-99qcg.3`, design 99qcg-D1/D10/D11):
 * the incremental cells, fed one [UpdateEvent] at a time through
 * [UpdateStream], equal a [BatchModel] fold of the same prefix after EVERY
 * event, over a 20-seed sweep ([SOC1-UPD-02], [SOC1-VER-02]); events apply in
 * stream order ([SOC1-UPD-03]); every IU arm reaches the cells
 * ([SOC1-UPD-01]); and a replayed event is a no-op ([SOC1-UPD-04]).
 *
 * The internals are built directly on a [SimWorld] host — `SnbPipeline.build`
 * + [SocialGraph] + [UpdateStream], the `SocialSchemaTest.newGraph()` idiom —
 * not through `SocialApp` (99qcg-D1). Every seed of `0L until 20L` runs;
 * a failing seed is a finding, never filtered.
 *
 * The scale is 0.02 (20 persons), which 99qcg-D7 sized for this loop: a full
 * [BatchModel] compare after each of the stream's events, for 20 seeds.
 */
class SocialPipelineTest {

    private class Rig(seed: Long) {
        val world = SimWorld(seed)
        val pipeline = SnbPipeline.build(world.host, journalDir = null, registry = world.registry)
        val graph = SocialGraph(world.host, pipeline)
        val source = SnbGenerator(seed, SCALE)

        /** Loads the static slice once and runs to idle. */
        fun load(): Rig = apply {
            SocialLoader.load(source, graph)
            world.runToIdle()
        }
    }

    // --- [SOC1-UPD-02] / [SOC1-VER-02] ---------------------------------------

    @Test
    fun `SOC1-UPD-02 cells equal the batch fold after every event over a 20-seed sweep`() {
        forEachSeed(0L until 20L) { seed ->
            val rig = Rig(seed).load()
            val model = BatchModel().also { it.load(rig.source.staticSlice()) }
            model.assertEquals(rig.graph, "seed=$seed event=-1")

            val stream = UpdateStream(rig.source, rig.graph)
            assertTrue(stream.events.isNotEmpty(), "seed=$seed: empty update stream")
            var i = 0
            while (stream.step()) {
                rig.world.runToIdle()
                model.apply(stream.events[i])
                model.assertEquals(rig.graph, "seed=$seed event=$i")
                i++
            }
            assertEquals(stream.events.size, i, "seed=$seed: step() count")
            assertEquals(stream.events.size, stream.applied, "seed=$seed: applied")
            assertEquals(0, stream.remaining, "seed=$seed: remaining")
        }
    }

    @Test
    fun `SOC1-UPD-02 UpdateStream neither reloads nor recomputes from the source`() {
        val text = File("src/main/kotlin/civictech/demo/social/UpdateStream.kt").readText()
        assertFalse("SocialLoader" in text, "UpdateStream.kt must not reference SocialLoader")
        assertFalse("staticSlice" in text, "UpdateStream.kt must not read the source's static slice")
    }

    // --- [SOC1-UPD-03] -------------------------------------------------------

    @Test
    fun `SOC1-UPD-03 every IU7 comment's parent is created by the slice or an earlier event`() {
        forEachSeed(0L until 20L) { seed ->
            val source = SnbGenerator(seed, SCALE)
            val events = UpdateStream(source, Rig(seed).graph).events
            val staticMessages = source.staticSlice().messages.mapTo(HashSet()) { it.id }
            val createdAt = HashMap<Long, Int>()
            events.forEachIndexed { j, e ->
                when (e) {
                    is IU6AddPost -> createdAt.putIfAbsent(e.message.id, j)
                    is IU7AddComment -> createdAt.putIfAbsent(e.message.id, j)
                    else -> {}
                }
            }
            val comments = events.withIndex().filter { it.value is IU7AddComment }
            assertTrue(comments.isNotEmpty(), "seed=$seed: no IU7 in the stream")
            comments.forEach { (i, e) ->
                val parent = (e as IU7AddComment).message.replyOfId!!
                val j = createdAt[parent]
                assertTrue(
                    parent in staticMessages || (j != null && j < i),
                    "seed=$seed event=$i: IU7 comment ${e.message.id} replies to $parent, " +
                        "created neither by the slice nor an earlier event (created at ${j ?: "never"})",
                )
            }
        }
    }

    // --- [SOC1-UPD-04] -------------------------------------------------------

    @Test
    fun `SOC1-UPD-04 replaying an applied event leaves state and applied unchanged`() {
        forEachSeed(0L until 20L) { seed ->
            val rig = Rig(seed).load()
            val stream = UpdateStream(rig.source, rig.graph)
            stream.stepAll()
            rig.world.runToIdle()

            val snapshot = BatchModel.observe(rig.graph)
            val appliedBefore = stream.applied
            val index = Random(seed).nextInt(stream.events.size)
            stream.apply(stream.events[index])
            rig.world.runToIdle()

            assertEquals(appliedBefore, stream.applied, "seed=$seed event=$index: applied moved on replay")
            val after = BatchModel.observe(rig.graph)
            snapshot.byName().zip(after.byName()).forEach { (b, a) ->
                assertEquals(b.second, a.second, "seed=$seed event=$index relation=${b.first}: replay changed state")
            }
        }
    }

    // --- [SOC1-UPD-01] -------------------------------------------------------

    @Test
    fun `SOC1-UPD-01 every IU arm is in the seed-42 stream and one sampled event per arm lands in the cells`() {
        val rig = Rig(42).load()
        val stream = UpdateStream(rig.source, rig.graph)
        stream.stepAll()
        rig.world.runToIdle()
        val g = rig.graph

        fun <T : UpdateEvent> sample(arm: Class<T>): T {
            val found = stream.events.filterIsInstance(arm)
            assertTrue(found.isNotEmpty(), "no ${arm.simpleName} in the seed-42 scale-$SCALE stream")
            return found[Random(42).nextInt(found.size)]
        }

        val iu1 = sample(IU1AddPerson::class.java)
        assertTrue(PersonFact.Profile(iu1.person) in g.personFacts(iu1.person.id), "IU1 $iu1 not in cells")

        val iu2 = sample(IU2LikePost::class.java)
        assertTrue(
            MessageFact.LikedBy(iu2.personId, iu2.creationDate) in g.messageFacts(iu2.postId),
            "IU2 $iu2 not in cells",
        )

        val iu3 = sample(IU3LikeComment::class.java)
        assertTrue(
            MessageFact.LikedBy(iu3.personId, iu3.creationDate) in g.messageFacts(iu3.commentId),
            "IU3 $iu3 not in cells",
        )

        val iu4 = sample(IU4AddForum::class.java)
        assertTrue(ForumFact.Info(iu4.forum) in g.forumFacts(iu4.forum.id), "IU4 $iu4 not in cells")

        val iu5 = sample(IU5AddMembership::class.java)
        assertTrue(
            ForumFact.Member(iu5.personId, iu5.creationDate) in g.forumFacts(iu5.forumId),
            "IU5 $iu5 not in cells",
        )

        val iu6 = sample(IU6AddPost::class.java)
        assertTrue(ForumFact.Contains(iu6.message.id) in g.forumFacts(iu6.message.forumId!!), "IU6 $iu6 Contains")
        assertTrue(MessageFact.Body(iu6.message) in g.messageFacts(iu6.message.id), "IU6 $iu6 Body")

        val iu7 = sample(IU7AddComment::class.java)
        assertTrue(MessageFact.Reply(iu7.message.id) in g.messageFacts(iu7.message.replyOfId!!), "IU7 $iu7 Reply")

        val iu8 = sample(IU8AddFriendship::class.java)
        assertTrue(Knows(iu8.b, iu8.creationDate) in g.personFacts(iu8.a), "IU8 $iu8 on a")
        assertTrue(Knows(iu8.a, iu8.creationDate) in g.personFacts(iu8.b), "IU8 $iu8 on b")
    }

    // --- [SOC1-CREAD-04] ------------------------------------------------------

    /**
     * SOC1 F6 (feature `computenet-flfkm`, design flfkm-D9): `BatchModel.ic2/
     * ic8/ic3` equal the live incremental paths — `FeedSession.board(20)`
     * (one session per viewer, retained across events, rebuilt only when the
     * viewer's `knows` set changes) and `ComplexReads.ic8`/`ic3` — after
     * EVERY event, for three `Random(seed)`-drawn viewers, over five seeds.
     */
    @Test
    fun `SOC1-CREAD-04 ic2, ic8 and ic3 equal the batch model after every event for three viewers over five seeds`() {
        forEachSeed(0L until 5L) { seed ->
            val rig = Rig(seed).load()
            val model = BatchModel().also { it.load(rig.source.staticSlice()) }

            val allPersonIds = rig.source.staticSlice().persons.map { it.id }
            val viewers = allPersonIds.shuffled(Random(seed)).take(3)

            val reader = HostBoundedReader(rig.world.host)
            val locator = GraphLocator(rig.graph, rig.pipeline.families)
            val reads = ComplexReads(reader, locator)

            val sessions = HashMap<Long, FeedSession>()
            val sessionScopes = HashMap<Long, Set<Long>>()

            fun sessionFor(viewer: Long): FeedSession {
                val currentKnows = model.knows[viewer]?.mapTo(HashSet()) { it.otherId } ?: emptySet()
                val existing = sessions[viewer]
                if (existing != null && sessionScopes[viewer] == currentKnows) return existing
                val session = FeedSession(
                    viewer = viewer,
                    scope = Interest.Ranges(currentKnows.map { Interest.Ranges.Range(it, it + 1) }),
                    families = rig.pipeline.families,
                    registry = rig.world.registry,
                    reader = reader,
                )
                sessions[viewer] = session
                sessionScopes[viewer] = currentKnows
                return session
            }

            fun window(): Pair<Long, Long> =
                if (model.messages.isEmpty()) {
                    0L to 1L
                } else {
                    model.messages.values.minOf { it.creationDate } to (model.messages.values.maxOf { it.creationDate } + 1)
                }

            fun <T> await(future: CompletableFuture<ReadOutcome<List<T>>>, label: String): List<T> {
                rig.world.runToIdle()
                return when (val outcome = future.get(20, TimeUnit.SECONDS)) {
                    is ReadOutcome.Found -> outcome.value
                    ReadOutcome.Empty -> emptyList()
                    is ReadOutcome.Refused -> fail("$label: refused (${outcome.reason})")
                }
            }

            fun compareAll(label: String) {
                val (from, to) = window()
                for (viewer in viewers) {
                    val vlabel = "$label viewer=$viewer"

                    val session = sessionFor(viewer)
                    val pullFuture = session.pull()
                    rig.world.runToIdle()
                    pullFuture.get(20, TimeUnit.SECONDS)
                    assertEquals(model.ic2(viewer, 20), session.board(20), "$vlabel query=ic2")

                    val liveIc8 = await(reads.ic8(viewer, 20), "$vlabel query=ic8")
                    assertEquals(model.ic8(viewer, 20), liveIc8, "$vlabel query=ic8")

                    val liveIc3 = await(reads.ic3(viewer, 6L, 7L, from, to, 20), "$vlabel query=ic3")
                    assertEquals(model.ic3(viewer, 6L, 7L, from, to), liveIc3, "$vlabel query=ic3")
                }
            }

            compareAll("seed=$seed event=-1")

            val stream = UpdateStream(rig.source, rig.graph)
            var i = 0
            while (stream.step()) {
                rig.world.runToIdle()
                model.apply(stream.events[i])
                compareAll("seed=$seed event=$i")
                i++
            }

            // Non-vacuity: at least one viewer non-empty per query, over the full window.
            val (from, to) = window()
            val ic2NonEmpty = viewers.any { model.ic2(it, 20).isNotEmpty() }
            val ic8NonEmpty = viewers.any { model.ic8(it, 20).isNotEmpty() }
            val ic3NonEmpty = viewers.any { model.ic3(it, 6L, 7L, from, to).isNotEmpty() }
            if (ic2NonEmpty && ic8NonEmpty && ic3NonEmpty) return@forEachSeed

            // Widen: the three drawn viewers were degenerate — check the rest of the
            // static persons (batch model only; this validates the fixture, it does
            // not re-run the live comparison for the widened set).
            val rest = allPersonIds - viewers.toSet()
            assertTrue(
                ic2NonEmpty || rest.any { model.ic2(it, 20).isNotEmpty() },
                "seed=$seed: no person among all ${allPersonIds.size} has a non-empty ic2",
            )
            assertTrue(
                ic8NonEmpty || rest.any { model.ic8(it, 20).isNotEmpty() },
                "seed=$seed: no person among all ${allPersonIds.size} has a non-empty ic8",
            )
            assertTrue(
                ic3NonEmpty || rest.any { model.ic3(it, 6L, 7L, from, to).isNotEmpty() },
                "seed=$seed: no person among all ${allPersonIds.size} has an ic3 row over the full window",
            )
        }
    }

    private companion object {
        const val SCALE = 0.02
    }
}
