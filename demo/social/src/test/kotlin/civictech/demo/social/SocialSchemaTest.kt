package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID

/**
 * Cell/pipeline halves of the SOC1-SCHEMA rules (feature `computenet-jo2jk`,
 * task `computenet-jo2jk.2`, design jo2jk-D9), driven directly against
 * [SocialGraph] on a deterministic [SimulationController] host — no HTTP.
 *
 * task `computenet-jo2jk.3` AMENDS this file with the `/state` half of
 * SOC1-SCHEMA-02 and the two-`SocialApp` half of SOC1-SCHEMA-05.
 */
class SocialSchemaTest {

    private fun newHost(): Pair<SimulationController, ManagedHost> {
        val controller = SimulationController(1)
        val host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry())
        return controller to host
    }

    private fun newGraph(): Triple<SimulationController, ManagedHost, SocialGraph> {
        val (controller, host) = newHost()
        val pipeline = SnbPipeline.build(host, journalDir = null)
        return Triple(controller, host, SocialGraph(host, pipeline))
    }

    private fun <T : java.io.Serializable> roundTrip(value: T): T {
        val bytes = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use { out -> out.writeObject(value) } }
            .toByteArray()
        @Suppress("UNCHECKED_CAST")
        return ObjectInputStream(ByteArrayInputStream(bytes)).readObject() as T
    }

    // --- SOC1-SCHEMA-01 -----------------------------------------------------

    @Test
    fun `SOC1-SCHEMA-01 Person Knows Forum Message Like and Tag are Serializable and round-trip`() {
        val person = Person(1, "Ada", "Lovelace")
        val knows = Knows(2, 5L)
        val forum = Forum(100, "f", 1)
        val message = Message(10, 1, 7L, "hi", forumId = 100)
        val like = Like(1, 10, 9L)
        val tag = Tag(5, "t", 1)

        ((person as Any) is java.io.Serializable) shouldBe true
        ((knows as Any) is java.io.Serializable) shouldBe true
        ((forum as Any) is java.io.Serializable) shouldBe true
        ((message as Any) is java.io.Serializable) shouldBe true
        ((like as Any) is java.io.Serializable) shouldBe true
        ((tag as Any) is java.io.Serializable) shouldBe true

        roundTrip(person) shouldBe person
        roundTrip(knows) shouldBe knows
        roundTrip(forum) shouldBe forum
        roundTrip(message) shouldBe message
        roundTrip(like) shouldBe like
        roundTrip(tag) shouldBe tag
    }

    // --- SOC1-SCHEMA-02 (cell half) -----------------------------------------

    @Test
    fun `SOC1-SCHEMA-02 a big SNB id is preserved verbatim in keys and cell`() {
        val (controller, _, graph) = newGraph()
        val bigId = 8796093022390L
        val person = Person(bigId, "Big", "Id")

        graph.addPerson(person)
        controller.runToIdle()

        graph.personIds() shouldBe sortedSetOf(bigId)
        graph.personFacts(bigId) shouldBe setOf(PersonFact.Profile(person))
    }

    // --- SOC1-SCHEMA-03 ------------------------------------------------------

    @Test
    fun `SOC1-SCHEMA-03 knows adjacency lives in each owner's own cell, undirected, no cell holds it all`() {
        val (controller, _, graph) = newGraph()
        listOf(1L, 2L, 3L).forEach { graph.addPerson(Person(it, "p$it", "l$it")) }
        graph.addKnows(1, 2, 0)
        graph.addKnows(2, 3, 0)
        controller.runToIdle()

        fun knowsOf(id: Long) = graph.personFacts(id).filterIsInstance<Knows>().toSet()

        knowsOf(1) shouldBe setOf(Knows(2, 0))
        knowsOf(2) shouldBe setOf(Knows(1, 0), Knows(3, 0))
        knowsOf(3) shouldBe setOf(Knows(2, 0))

        val allFacts = listOf(1L, 2L, 3L).map { knowsOf(it) }

        // no Knows.otherId equals its own cell's owner
        listOf(1L to graph.personFacts(1), 2L to graph.personFacts(2), 3L to graph.personFacts(3))
            .forEach { (owner, facts) ->
                facts.filterIsInstance<Knows>().forEach { it.otherId shouldNotBe owner }
            }

        // no single cell holds all four Knows facts (2 total distinct edges written twice = 4 facts)
        val totalKnowsFacts = allFacts.sumOf { it.filterIsInstance<Knows>().size }
        totalKnowsFacts shouldBe 4
        allFacts.forEach { facts -> (facts.filterIsInstance<Knows>().size < totalKnowsFacts) shouldBe true }
    }

    // --- SOC1-SCHEMA-04 ------------------------------------------------------

    @Test
    fun `SOC1-SCHEMA-04 addKnows writes both cells, removeKnows removes both`() {
        val (controller, _, graph) = newGraph()
        graph.addPerson(Person(1, "a", "a"))
        graph.addPerson(Person(2, "b", "b"))

        fun knowsOf(id: Long) = graph.personFacts(id).filterIsInstance<Knows>().toSet()

        graph.addKnows(1, 2, 0)
        controller.runToIdle()
        knowsOf(1) shouldBe setOf(Knows(2, 0))
        knowsOf(2) shouldBe setOf(Knows(1, 0))

        graph.removeKnows(1, 2, 0)
        controller.runToIdle()
        knowsOf(1) shouldBe emptySet()
        knowsOf(2) shouldBe emptySet()
    }

    // --- SOC1-SCHEMA-05 (pipeline half) --------------------------------------

    @Test
    fun `SOC1-SCHEMA-05 the per-person family ref formula and cross-host determinism`() {
        val expectedRef = CellRef(UUID.nameUUIDFromBytes("snb-person:1".toByteArray()))

        val (controller1, host1) = newHost()
        val pipeline1 = SnbPipeline.build(host1, journalDir = null)
        pipeline1.families.person.getOrSpawn(1).ref shouldBe expectedRef
        controller1.runToIdle()

        // a second SnbPipeline.build on a SECOND host mints the identical ref for person 1
        val (controller2, host2) = newHost()
        val pipeline2 = SnbPipeline.build(host2, journalDir = null)
        pipeline2.families.person.getOrSpawn(1).ref shouldBe expectedRef
        controller2.runToIdle()
    }

    // --- unknown id ------------------------------------------------------------

    @Test
    fun `an unknown referenced id throws before any write and leaves every family's keys unchanged`() {
        val (controller, host, graph) = newGraph()
        graph.addPerson(Person(1, "a", "a"))
        controller.runToIdle()

        val pipeline = SnbPipeline.build(host, journalDir = null)
        fun snapshot() = Triple(
            pipeline.families.person.keys(),
            Pair(pipeline.families.forum.keys(), pipeline.families.message.keys()),
            pipeline.families.authored.keys(),
        )
        val before = snapshot()

        assertThrowsIllegalArgument { graph.addKnows(1, 999, 0) }
        assertThrowsIllegalArgument { graph.joinForum(1, 999, 0) }
        assertThrowsIllegalArgument { graph.addForum(Forum(1, "f", 999)) }
        assertThrowsIllegalArgument {
            graph.addPost(Message(1, 999, 0, "x", forumId = 1))
        }
        assertThrowsIllegalArgument { graph.addLike(Like(1, 999, 0)) }

        snapshot() shouldBe before
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException, none was thrown")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // --- addPost writes three cells --------------------------------------------

    @Test
    fun `addPost writes the author's authored cell, the message cell and the forum's Contains`() {
        val (controller, _, graph) = newGraph()
        graph.addPerson(Person(1, "a", "a"))
        graph.addForum(Forum(100, "f", 1))
        controller.runToIdle()

        val post = Message(10, 1, 7L, "hi", forumId = 100)
        graph.addPost(post)
        controller.runToIdle()

        graph.authored(1) shouldBe setOf(post)
        graph.messageFacts(10) shouldBe setOf(MessageFact.Body(post))
        graph.forumFacts(100) shouldBe setOf(ForumFact.Info(Forum(100, "f", 1)), ForumFact.Contains(10))
    }
}
