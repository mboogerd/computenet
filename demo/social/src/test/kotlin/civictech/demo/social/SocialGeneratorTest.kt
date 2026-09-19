package civictech.demo.social

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * [SnbSource], [SnbGenerator] and [SocialLoader] (SOC1, epic
 * `computenet-07k`, feature `computenet-99qcg`, task `computenet-99qcg.1`,
 * design 99qcg-D4..D8). Covers [SOC1-GEN-01], [SOC1-GEN-02], [SOC1-GEN-03],
 * [SOC1-GEN-06], the supporting "every IU arm at scale 0.02" property needed
 * by the sibling pipeline task's [SOC1-UPD-01], and the loader half of
 * [SOC1-GEN-04] (the CSV-swappability half is a sibling task).
 */
class SocialGeneratorTest {

    private fun newGraph(): SocialGraph {
        val controller = SimulationController(1)
        val host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry())
        val pipeline = SnbPipeline.build(host, journalDir = null)
        return SocialGraph(host, pipeline)
    }

    // --- [SOC1-GEN-01] --------------------------------------------------

    @Test
    fun `SOC1-GEN-01 two generators with the same seed and scale produce equal output`() {
        for (seed in listOf(42L, 7L, 12345L)) {
            val first = SnbGenerator(seed, 0.1)
            val second = SnbGenerator(seed, 0.1)

            first.staticSlice() shouldBe second.staticSlice()
            first.updates().toList() shouldBe second.updates().toList()
        }
    }

    // --- [SOC1-GEN-02] --------------------------------------------------

    @Test
    fun `SOC1-GEN-02 the knows relation is Zipf-skewed - top decile holds at least 40 percent of degree mass`() {
        val generator = SnbGenerator(42, 0.1)
        val slice = generator.staticSlice()
        val updates = generator.updates().toList()

        val degree = HashMap<Long, Int>()
        fun bump(id: Long) {
            degree[id] = (degree[id] ?: 0) + 1
        }
        slice.knows.forEach { bump(it.a); bump(it.b) }
        updates.filterIsInstance<IU8AddFriendship>().forEach { bump(it.a); bump(it.b) }

        assertTrue(degree.isNotEmpty(), "expected a non-empty knows relation")
        val sortedDegrees = degree.values.sortedDescending()
        val totalMass = sortedDegrees.sum().toDouble()
        val decileSize = (sortedDegrees.size / 10).coerceAtLeast(1)
        val topDecileMass = sortedDegrees.take(decileSize).sum().toDouble()
        val topDecileShare = topDecileMass / totalMass

        assertTrue(
            topDecileShare >= 0.40,
            "top decile should hold at least 40% of endpoint-degree mass, got ${topDecileShare * 100}%" +
                " (decileSize=$decileSize, persons=${sortedDegrees.size}, totalMass=$totalMass)",
        )

        val generatorSource = File("src/main/kotlin/civictech/demo/social/SnbGenerator.kt").readText()
        assertTrue(
            generatorSource.contains("top decile"),
            "SnbGenerator KDoc should state the asserted skew property using the words \"top decile\"",
        )
    }

    // --- [SOC1-GEN-03] --------------------------------------------------

    @Test
    fun `SOC1-GEN-03 updates are non-decreasing in creationDate with the D5 secondary key`() {
        val updates = SnbGenerator(42, 0.1).updates().toList()
        assertTrue(updates.isNotEmpty(), "expected a non-empty update stream")

        updates.zipWithNext().forEach { (a, b) ->
            assertTrue(a.creationDate <= b.creationDate, "creationDate regressed: $a -> $b")
            assertTrue(UpdateEventOrder.compare(a, b) <= 0, "UpdateEventOrder regressed: $a -> $b")
        }
    }

    // --- [SOC1-GEN-06] --------------------------------------------------

    @Test
    fun `SOC1-GEN-06 validateReferences throws on a hand-built dangling reference`() {
        val slice = StaticSlice(
            tagClasses = emptyList(),
            tags = emptyList(),
            places = emptyList(),
            organisations = emptyList(),
            persons = listOf(
                Person(id = 1, firstName = "A", lastName = "One"),
                Person(id = 2, firstName = "B", lastName = "Two"),
            ),
            knows = emptyList(),
            forums = emptyList(),
            memberships = emptyList(),
            messages = emptyList(),
            likes = emptyList(),
        )
        val danglingStream = sequenceOf(IU8AddFriendship(1, 99, 10L))

        val failure = try {
            validateReferences(slice, danglingStream)
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertTrue(failure != null, "expected validateReferences to throw IllegalStateException")
        val message = failure!!.message ?: ""
        assertTrue("IU8AddFriendship" in message, "message should name the event: $message")
        assertTrue("99" in message, "message should name the unknown id: $message")
    }

    @Test
    fun `SOC1-GEN-06 the generator's own updates already ran the validator successfully`() {
        val generator = SnbGenerator(42, 0.1)
        // updates() runs validateReferences internally before returning; running it again
        // here (against the same slice) must also succeed, proving the check bites on real
        // output without throwing on it.
        validateReferences(generator.staticSlice(), generator.updates())
    }

    // --- supporting property for the pipeline task's [SOC1-UPD-01] -----

    @Test
    fun `every IU arm occurs in the update stream at scale 0-02 across seeds 0 to 19 and 42`() {
        val requiredArms = setOf(
            IU1AddPerson::class,
            IU2LikePost::class,
            IU3LikeComment::class,
            IU4AddForum::class,
            IU5AddMembership::class,
            IU6AddPost::class,
            IU7AddComment::class,
            IU8AddFriendship::class,
        )
        val seeds = (0L until 20L).toList() + listOf(42L)
        val missingBySeed = seeds.associateWith { seed ->
            val kinds = SnbGenerator(seed, 0.02).updates().map { it::class }.toSet()
            requiredArms - kinds
        }.filterValues { it.isNotEmpty() }

        assertTrue(missingBySeed.isEmpty(), "seeds missing arms: $missingBySeed")
    }

    // --- membership/like uniqueness (computenet-hn8qo) ------------------

    @Test
    fun `memberships and likes are unique per (person, forum) and (person, message) across seeds`() {
        val cases = ((0L until 20L).toList() + listOf(42L)).map { it to 0.02 } + listOf(42L to 0.1)

        for ((seed, scale) in cases) {
            val generator = SnbGenerator(seed, scale)
            val slice = generator.staticSlice()
            val updates = generator.updates().toList()

            val membershipPairs = ArrayList<Pair<Long, Long>>()
            slice.memberships.forEach { membershipPairs += it.personId to it.forumId }
            updates.filterIsInstance<IU5AddMembership>().forEach { membershipPairs += it.personId to it.forumId }
            val duplicateMemberships = membershipPairs.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertTrue(
                duplicateMemberships.isEmpty(),
                "seed=$seed scale=$scale: duplicate (personId, forumId) memberships: $duplicateMemberships",
            )

            val likePairs = ArrayList<Pair<Long, Long>>()
            slice.likes.forEach { likePairs += it.personId to it.messageId }
            updates.filterIsInstance<IU2LikePost>().forEach { likePairs += it.personId to it.postId }
            updates.filterIsInstance<IU3LikeComment>().forEach { likePairs += it.personId to it.commentId }
            val duplicateLikes = likePairs.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertTrue(
                duplicateLikes.isEmpty(),
                "seed=$seed scale=$scale: duplicate (personId, messageId) likes: $duplicateLikes",
            )
        }
    }

    // --- SocialLoader (loader half of SOC1-GEN-04) ----------------------

    @Test
    fun `SocialLoader loads a generator's static slice into SocialGraph in D4 field order`() {
        val graph = newGraph()
        val generator = SnbGenerator(3, 0.02)

        SocialLoader.load(generator, graph)

        val slice = generator.staticSlice()
        graph.personIds().toList() shouldBe slice.persons.map { it.id }.sorted()
        graph.forumIds().toList() shouldBe slice.forums.map { it.id }.sorted()
        graph.messageIds().toList() shouldBe slice.messages.map { it.id }.sorted()
    }

    @Test
    fun `SocialLoader contains no branch on the source runtime type`() {
        val loaderSource = File("src/main/kotlin/civictech/demo/social/SocialLoader.kt").readText()
        val branchOnSourceType = Regex("""is\s+(SnbGenerator|DatagenCsvSource)|when\s*\(\s*source\b""")
        assertTrue(
            !branchOnSourceType.containsMatchIn(loaderSource),
            "SocialLoader.kt should not branch on the source's runtime type",
        )
    }
}
