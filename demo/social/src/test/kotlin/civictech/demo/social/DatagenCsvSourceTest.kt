package civictech.demo.social

import civictech.testkit.SimWorld
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * [DatagenCsvSource] (SOC1, epic `computenet-07k`, feature `computenet-99qcg`,
 * task `computenet-99qcg.2`, design 99qcg-D9). Covers the CSV-swappability
 * half of [SOC1-GEN-04] — the loader half is covered by the sibling task's
 * `SocialGeneratorTest`.
 */
class DatagenCsvSourceTest {

    private val fixtureDir = File("src/test/resources/datagen-mini")

    private fun newGraph(world: SimWorld): SocialGraph {
        val pipeline = SnbPipeline.build(world.host, journalDir = null)
        return SocialGraph(world.host, pipeline)
    }

    /** Copies [fixtureDir] into a fresh temp dir, applying [mutate] to one file's lines. */
    private fun copyFixtureWith(fileName: String, mutate: (List<String>) -> List<String>): File {
        val target = kotlin.io.path.createTempDirectory("datagen-mini-mutated").toFile()
        fixtureDir.listFiles()!!.forEach { source ->
            val destination = File(target, source.name)
            if (source.name == fileName) {
                destination.writeText(mutate(source.readLines()).joinToString("\n"))
            } else {
                source.copyTo(destination)
            }
        }
        return target
    }

    // --- [SOC1-GEN-04] ---------------------------------------------------

    @Test
    fun `SOC1-GEN-04 datagen-mini fixture and a generator load through the same SocialLoader call`() {
        val csvWorld = SimWorld(seed = 1)
        val csvGraph = newGraph(csvWorld)
        val csvSource = DatagenCsvSource(fixtureDir)
        SocialLoader.load(csvSource, csvGraph)
        csvWorld.runToIdle()

        val genWorld = SimWorld(seed = 2)
        val genGraph = newGraph(genWorld)
        val generator = SnbGenerator(1, 0.02)
        SocialLoader.load(generator, genGraph)
        genWorld.runToIdle()

        val slice = csvSource.staticSlice()
        csvGraph.personIds().toList() shouldBe slice.persons.map { it.id }.sorted()
        csvGraph.forumIds().toList() shouldBe slice.forums.map { it.id }.sorted()
        csvGraph.messageIds().toList() shouldBe slice.messages.map { it.id }.sorted()

        val genSlice = generator.staticSlice()
        genGraph.personIds().toList() shouldBe genSlice.persons.map { it.id }.sorted()
        genGraph.forumIds().toList() shouldBe genSlice.forums.map { it.id }.sorted()
        genGraph.messageIds().toList() shouldBe genSlice.messages.map { it.id }.sorted()

        val loaderSource = File("src/main/kotlin/civictech/demo/social/SocialLoader.kt").readText()
        val branchOnSourceType = Regex("""is\s+(SnbGenerator|DatagenCsvSource)|when\s*\(\s*source\b""")
        assertTrue(
            !branchOnSourceType.containsMatchIn(loaderSource),
            "SocialLoader.kt should not branch on the source's runtime type",
        )
    }

    // --- update stream -----------------------------------------------------

    @Test
    fun `the fixture's update stream parses into all eight IU arms in creationDate order`() {
        val source = DatagenCsvSource(fixtureDir)
        val updates = source.updates().toList()

        val requiredArms = setOf(
            IU1AddPerson::class, IU2LikePost::class, IU3LikeComment::class, IU4AddForum::class,
            IU5AddMembership::class, IU6AddPost::class, IU7AddComment::class, IU8AddFriendship::class,
        )
        assertTrue(
            updates.map { it::class }.toSet() == requiredArms,
            "expected exactly the eight IU arms, got ${updates.map { it::class }}",
        )

        updates.zipWithNext().forEach { (a, b) ->
            assertTrue(a.creationDate <= b.creationDate, "creationDate regressed: $a -> $b")
            assertTrue(UpdateEventOrder.compare(a, b) <= 0, "UpdateEventOrder regressed: $a -> $b")
        }
    }

    // --- missing required column -------------------------------------------

    @Test
    fun `a renamed header column throws naming the file and the column`() {
        val mutated = copyFixtureWith("person_0_0.csv") { lines ->
            listOf(lines.first().replace("firstName", "givenName")) + lines.drop(1)
        }

        val failure = try {
            DatagenCsvSource(mutated).staticSlice()
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(failure != null, "expected IllegalStateException for a missing column")
        val message = failure!!.message ?: ""
        assertTrue("person_0_0.csv" in message, "message should name the file: $message")
        assertTrue("firstName" in message, "message should name the missing column: $message")
    }

    // --- dangling reference in the update stream ----------------------------

    @Test
    fun `a dangling reference in the update stream throws from validateReferences`() {
        val mutated = copyFixtureWith("updateStream_0_0_person.csv") { lines ->
            // IU8 friendship row: replace the "otherPersonId" (last-but-one field, "9") with an
            // id nothing else in the fixture ever introduces.
            lines.map { line -> if (line.startsWith("IU8|")) line.replace("|9|||||||", "|9999|||||||") else line }
        }

        val failure = try {
            DatagenCsvSource(mutated).updates().toList()
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(failure != null, "expected IllegalStateException from validateReferences")
        val message = failure!!.message ?: ""
        assertTrue("9999" in message, "message should name the unknown id: $message")
    }
}
