package civictech.demo.skillmatch

import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillMatchObservationFrameTest {

    @Test
    fun `one candidate write converges all derived views in an SSE frame`() {
        val app = SkillMatchApp(port = 0).start()
        try {
            assertEquals(
                setOf("candSkills", "jobSkills", "matches", "gap", "qualification", "market"),
                app.observationViews,
            )
            assertEquals(
                listOf("candSkills", "jobSkills", "matches", "gap", "qualification", "market"),
                app.observationViews.toList(),
            )
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)

            probe.post("action=jskill&job=backend&skill=kotlin")
            probe.await { "\"gap\":[{\"job\":\"backend\",\"skill\":\"kotlin\"}]" in it }

            val frames = collectFrames(
                "$base/events",
                onSubscribed = { probe.post("action=cskill&candidate=ada&skill=kotlin") },
            ) { frame ->
                "\"qualified\":true" in frame && "\"gap\":[]" in frame && "\"supply\":1" in frame
            }

            val terminal = frames.last()
            assertTrue("\"qualified\":true" in terminal, "qualification did not converge: $terminal")
            assertTrue("\"gap\":[]" in terminal, "gap did not converge: $terminal")
            assertTrue("\"supply\":1" in terminal, "market supply did not converge: $terminal")
        } finally {
            app.stop()
        }
    }
}

private fun collectFrames(
    url: String,
    onSubscribed: () -> Unit,
    until: (String) -> Boolean,
): List<String> = SseTap(url) { it }.use { tap ->
    awaitUntil("initial SSE frame from $url", timeoutMs = 5_000) { tap.frames().isNotEmpty() }
    onSubscribed()
    awaitUntil("terminal observation SSE frame from $url", timeoutMs = 5_000) { tap.frames().any(until) }
    val frames = tap.frames()
    frames.subList(0, frames.indexOfFirst(until) + 1)
}
