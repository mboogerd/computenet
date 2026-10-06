package civictech.demo.skillmatch

import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillMatchAlignedFrameTest {

    private val alignedFields = listOf("matches", "progress", "gap", "market")

    @Test
    fun `checked aligned views publish one consistent candidate-wave frame`() {
        val app = SkillMatchApp(port = 0).start()
        try {
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
                alignedFields.any { name -> field(frames[i], name) != field(frames[i - 1], name) }
            }
            assertEquals(1, transitions.size, "expected one derived-group transition: $frames")
            val changedAt = transitions.single()
            alignedFields.forEach { name ->
                assertTrue(
                    field(frames[changedAt], name) != field(frames[changedAt - 1], name),
                    "$name did not change in the group's sole SSE frame: $frames",
                )
            }
            assertEquals(
                "[]",
                field(frames[changedAt], "gap"),
                "the frame that publishes the match must also retract its gap: $frames",
            )
            assertTrue(
                "\"qualified\":true" in field(frames[changedAt], "progress"),
                "the frame that publishes the match must also publish qualification: $frames",
            )
            assertTrue(
                "\"supply\":1" in field(frames[changedAt], "market"),
                "the frame that publishes the match must also publish market supply: $frames",
            )

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
