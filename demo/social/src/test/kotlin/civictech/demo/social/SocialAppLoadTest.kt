package civictech.demo.social

import civictech.testkit.HttpProbe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SocialApp`'s `--scale`/`--seed` load path and `/op action=step` (feature
 * `computenet-99qcg`, task `computenet-99qcg.4`, design 99qcg-D2/D3/D10):
 * one test per [SOC1-GEN-05] plus the step action and the `applied`/
 * `remaining` `/state` counters. Orchestrator correction on this bead
 * (2026-09-19): the loaded person counts are each source's `staticSlice()`
 * person count (SnbGenerator cuts ~80% of persons into the static slice),
 * not the generator's total person count.
 */
class SocialAppLoadTest {

    // --- [SOC1-GEN-05] ------------------------------------------------------

    @Test
    fun `LaunchOptions parse defaults to scale 0_05 and seed 42`() {
        val opts = LaunchOptions.parse(emptyArray())
        assertEquals(0.05, opts.scale)
        assertEquals(42L, opts.seed)
    }

    @Test
    fun `LaunchOptions parse reads --scale and --seed`() {
        val opts = LaunchOptions.parse(arrayOf("--scale", "0.2", "--seed", "7"))
        assertEquals(0.2, opts.scale)
        assertEquals(7L, opts.seed)
    }

    @Test
    fun `LaunchOptions parse rejects a non-numeric --scale`() {
        val e = assertThrows<IllegalArgumentException> { LaunchOptions.parse(arrayOf("--scale", "nope")) }
        assertTrue("--scale" in (e.message ?: ""), "message should name --scale: ${e.message}")
    }

    @Test
    fun `LaunchOptions parse rejects a non-numeric --seed`() {
        val e = assertThrows<IllegalArgumentException> { LaunchOptions.parse(arrayOf("--seed", "nope")) }
        assertTrue("--seed" in (e.message ?: ""), "message should name --seed: ${e.message}")
    }

    @Test
    fun `SOC1-GEN-05 loaded person count equals the source's staticSlice person count, and differs by scale`() {
        val small = SnbGenerator(42, 0.05)
        val large = SnbGenerator(42, 0.2)

        var smallApp: SocialApp? = null
        val elapsedMs = measureTimeMillis {
            smallApp = SocialApp(port = 0, source = small).start()
        }
        val largeApp = SocialApp(port = 0, source = large).start()
        try {
            assertTrue(elapsedMs < 10_000, "0.05-scale construction should finish under 10s, took ${elapsedMs}ms")

            val smallExpected = small.staticSlice().persons.size
            val largeExpected = large.staticSlice().persons.size
            assertEquals(smallExpected, smallApp!!.graph.personIds().size)
            assertEquals(largeExpected, largeApp.graph.personIds().size)
            assertTrue(
                smallApp!!.graph.personIds().size != largeApp.graph.personIds().size,
                "person counts should differ by scale: ${smallApp!!.graph.personIds().size} vs ${largeApp.graph.personIds().size}",
            )
        } finally {
            smallApp?.stop()
            largeApp.stop()
        }
    }

    // --- /op action=step, /state applied/remaining --------------------------

    @Test
    fun `action=step applies n events and advances state's applied and remaining`() {
        val source = SnbGenerator(42, 0.05)
        val app = SocialApp(port = 0, source = source).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val before = probe.state()
            assertTrue(""""applied":0""" in before, "fresh app should have applied 0: $before")
            val remainingBefore = source.updates().count()
            assertTrue(""""remaining":$remainingBefore""" in before, "fresh app remaining should be the whole stream: $before")

            val status = probe.post("action=step&n=3")
            assertEquals(200, status)

            val after = probe.await { """"applied":3""" in it }
            assertTrue(""""remaining":${remainingBefore - 3}""" in after, "remaining should decrease by 3: $after")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `action=step without a source is a 400`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val response = probe.postForm("action=step")
            assertEquals(400, response.statusCode())
            assertEquals("no stream", response.body())
        } finally {
            app.stop()
        }
    }

    @Test
    fun `state of a source-less app carries applied 0 and remaining 0`() {
        val app = SocialApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            val json = probe.state()
            assertTrue(""""applied":0,"remaining":0""" in json, "source-less app should carry applied=0,remaining=0: $json")
        } finally {
            app.stop()
        }
    }
}
