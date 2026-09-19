package civictech.demo.social

import civictech.testkit.SimWorld
import civictech.testkit.forEachSeed
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val graph = SocialGraph(world.host, SnbPipeline.build(world.host, journalDir = null))
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

    private companion object {
        const val SCALE = 0.02
    }
}
