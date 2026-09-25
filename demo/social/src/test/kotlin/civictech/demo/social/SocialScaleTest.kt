package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.testkit.SimWorld
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SOC1's gated placement-pressure probe (epic `computenet-07k`, feature
 * `computenet-74yvm`, design 74yvm-D1..D4), against `G-24`
 * (`doc/spec/90-roadmap/91-gap-analysis.md`, "placement pressure on one
 * instance") and epic §4 "Placement pressure and findings".
 *
 * **Invocation** (74yvm-D1): `SOCIAL_SCALE` is an environment variable, not a
 * system property — observed: `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`
 * forwards no `-D` from the Gradle command line to the test fork (its only
 * `systemProperty(...)` call is the junit method timeout), so a test fork
 * only ever sees an environment variable set on the invocation itself:
 *
 * ```
 * SOCIAL_SCALE=1.0 ./gradlew :demo:social:test --tests 'civictech.demo.social.SocialScaleTest' --rerun
 * ```
 *
 * **Per-run cost**: one static load at the given scale, through the
 * `SocialPipelineTest.Rig` idiom (`SimWorld` + `SnbPipeline.build` +
 * `SocialGraph` + `SocialLoader.load` + `runToIdle`) — never through
 * `SocialApp`, so no HTTP shell or observe sink skews the heap number. At
 * scale 0.05 this is under 10 s on darwin/arm64, the same bound
 * `SocialAppLoadTest` asserts for its own 0.05 construction; larger scales are
 * unverified here — the sweep the task ran records what was actually
 * measured, in the task's bead comment.
 *
 * **Pressure criterion** (74yvm-D4): heap-per-cell extrapolated to the next
 * scale step (x2) would exceed 2 GiB, or any family exceeds 100_000 cells, or
 * `loadWallMs` exceeds 60_000. This test does not evaluate the criterion or
 * write a finding — it only prints the numbers a human or a sibling task
 * reads to decide.
 */
class SocialScaleTest {

    private class Rig(seed: Long, scale: Double) {
        val world = SimWorld(seed)
        val pipeline = SnbPipeline.build(world.host, journalDir = null, registry = world.registry)
        val graph = SocialGraph(world.host, pipeline)
        val source = SnbGenerator(seed, scale)
    }

    @Test
    fun `SOC1-PLC-01 gated scale load reports cell count and per-cell footprint`() {
        val scaleEnv = System.getenv("SOCIAL_SCALE")
        assumeTrue(scaleEnv != null) { "set SOCIAL_SCALE=<scale factor> to run the gated scale load" }
        val scale = scaleEnv!!.toDoubleOrNull()
            ?: fail("SOCIAL_SCALE must be a numeric scale factor, got '$scaleEnv'")

        val seed = 42L
        val rig = Rig(seed, scale)
        val budget = maxOf(200_000, (2_000_000 * scale).toInt())

        val loadWallMs = measureTimeMillis {
            SocialLoader.load(rig.source, rig.graph)
            rig.world.runToIdle(budget = budget)
        }

        // [SOC1-PLC-01] not bypassable: the loaded person count equals the
        // source's own static slice, the same identity SocialAppLoadTest
        // asserts at scale 0.05.
        val expectedPersons = rig.source.staticSlice().persons.size

        val persons = rig.pipeline.families.person.keys().size
        val authored = rig.pipeline.families.authored.keys().size
        val forums = rig.pipeline.families.forum.keys().size
        val messages = rig.pipeline.families.message.keys().size
        val totalCells = persons + authored + forums + messages

        System.gc()
        System.gc()
        val runtime = Runtime.getRuntime()
        val usedHeapBytes = runtime.totalMemory() - runtime.freeMemory()
        val heapPerCellBytes = if (totalCells > 0) usedHeapBytes / totalCells else 0L

        val reader = HostBoundedReader(rig.world.host)
        var largestAuthoredCellElements = 0
        var largestAuthoredCellPages = 0
        for (key in rig.pipeline.families.authored.keys()) {
            val ref = rig.pipeline.families.authored.getOrSpawn(key).ref
            val (elements, pages) = walk(reader, rig.world, ref)
            if (elements > largestAuthoredCellElements) {
                largestAuthoredCellElements = elements
                largestAuthoredCellPages = pages
            }
        }

        val report = "SOCIAL_SCALE_REPORT {" +
            "\"scale\":$scale," +
            "\"seed\":$seed," +
            "\"persons\":$persons," +
            "\"authored\":$authored," +
            "\"forums\":$forums," +
            "\"messages\":$messages," +
            "\"totalCells\":$totalCells," +
            "\"usedHeapBytes\":$usedHeapBytes," +
            "\"heapPerCellBytes\":$heapPerCellBytes," +
            "\"largestAuthoredCellElements\":$largestAuthoredCellElements," +
            "\"largestAuthoredCellPages\":$largestAuthoredCellPages," +
            "\"loadWallMs\":$loadWallMs}"
        println(report)

        assertEquals(expectedPersons, persons, "loaded persons should equal the source's static slice person count")
        assertTrue(persons > 0, "persons should be > 0: $report")
        assertTrue(authored > 0, "authored should be > 0: $report")
        assertTrue(forums > 0, "forums should be > 0: $report")
        assertTrue(messages > 0, "messages should be > 0: $report")
        assertTrue(totalCells > 0, "totalCells should be > 0: $report")
        assertTrue(usedHeapBytes > 0, "usedHeapBytes should be > 0: $report")
        assertTrue(heapPerCellBytes > 0, "heapPerCellBytes should be > 0: $report")
        assertTrue(largestAuthoredCellElements > 0, "largestAuthoredCellElements should be > 0: $report")
        assertTrue(largestAuthoredCellPages > 0, "largestAuthoredCellPages should be > 0: $report")
        assertTrue(loadWallMs >= 0, "loadWallMs should be >= 0: $report")
    }

    /**
     * Pages [ref] to exhaustion through [reader] at the default
     * `StateRead(byteBudget = 50_000)`, pumping [world] to idle after every
     * read so the read's future — which lands on the next scheduler step —
     * actually completes. Returns (total elements across every page's
     * entries, page count).
     */
    private fun walk(reader: HostBoundedReader, world: SimWorld, ref: CellRef): Pair<Int, Int> {
        var elements = 0
        var pages = 0
        var cursor: civictech.cell.Cursor? = null
        while (true) {
            val future = reader.read(ref, StateRead(cursor = cursor, byteBudget = 50_000))
            world.runToIdle()
            val result = future.get(20, TimeUnit.SECONDS)
            val page = when (result) {
                is StateReadResult.Page -> result.page
                is StateReadResult.Unavailable -> fail("read of $ref refused: ${result.reason}")
                is StateReadResult.Unbounded -> fail("read of $ref answered unbounded")
            }
            pages++
            elements += page.entries.size
            cursor = page.next ?: return elements to pages
        }
    }
}
