package civictech.demo.skillmatch

import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillMatchAlignedFrameTest {

    private val groupedFields = listOf("matches", "progress", "gap", "market")

    @Test
    fun `one candidate write changes all same-root views in one SSE frame`() {
        val app = SkillMatchApp(port = 0).start()
        try {
            assertEquals(
                mapOf(
                    "candSkills" to "candSkills",
                    "jobSkills" to "jobSkills",
                    "matches" to "matches+gap+qualification+market",
                    "gap" to "matches+gap+qualification+market",
                    "qualification" to "matches+gap+qualification+market",
                    "market" to "matches+gap+qualification+market",
                ),
                app.observationGroups,
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

            val transitions = (1 until frames.size).filter { i ->
                groupedFields.any { name -> field(frames[i], name) != field(frames[i - 1], name) }
            }
            assertEquals(1, transitions.size, "expected one derived-group transition: $frames")
            val changedAt = transitions.single()
            groupedFields.forEach { name ->
                assertTrue(
                    field(frames[changedAt], name) != field(frames[changedAt - 1], name),
                    "$name did not change in the group's sole SSE frame: $frames",
                )
            }

            awaitUntil("skillmatch aligned sink idle", timeoutMs = 5_000) { app.alignedBufferedWaves == 0 }
        } finally {
            app.stop()
        }
    }

    private fun field(frame: String, name: String): String =
        Regex("\\\"$name\\\":(\\[[^]]*])").find(frame)?.groupValues?.get(1)
            ?: error("missing $name in $frame")
}

private fun collectFrames(
    url: String,
    onSubscribed: () -> Unit,
    until: (String) -> Boolean,
): List<String> = SseTap(url) { it }.use { tap ->
    awaitUntil("initial SSE frame from $url", timeoutMs = 5_000) { tap.frames().isNotEmpty() }
    onSubscribed()
    awaitUntil("terminal aligned SSE frame from $url", timeoutMs = 5_000) { tap.frames().any(until) }
    val frames = tap.frames()
    frames.subList(0, frames.indexOfFirst(until) + 1)
}
