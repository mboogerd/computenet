package civictech.demo.tiering

import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TieringAlignedFrameTest {

    @Test
    fun `one valuation changes the valuation and average views in one SSE frame`() {
        val app = TieringApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)
            probe.post("action=item&name=pizza")
            probe.await { "\"unrated\":[\"pizza\"]" in it }

            val frames = collectTieringFrames(
                "$base/events",
                onSubscribed = { probe.post("action=tier&agent=ada&item=pizza&tier=S") },
            ) { "\"tierAvg\":6.0000" in it && "\"agent\":\"ada\"" in it }

            assertOneJointTransition(frames, ::valuations, ::tierAverage)
            awaitUntil("tiering aligned sinks idle", timeoutMs = 5_000) { app.alignedBufferedWaves == 0 }
        } finally {
            app.stop()
        }
    }

    @Test
    fun `one preference changes the preference and average views in one SSE frame`() {
        val app = TieringApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)
            probe.post("action=item&name=pizza")
            probe.post("action=item&name=sushi")
            probe.await { "\"unrated\":[\"pizza\",\"sushi\"]" in it }

            val frames = collectTieringFrames(
                "$base/events",
                onSubscribed = { probe.post("action=pref&agent=ada&winner=sushi&loser=pizza") },
            ) { "\"prefAvg\":1.0000" in it && "\"winner\":\"sushi\"" in it }

            assertOneJointTransition(frames, ::preferences, ::preferenceAverages)
            awaitUntil("tiering aligned sinks idle", timeoutMs = 5_000) { app.alignedBufferedWaves == 0 }
        } finally {
            app.stop()
        }
    }

    private fun assertOneJointTransition(
        frames: List<String>,
        first: (String) -> String,
        second: (String) -> String,
    ) {
        val transitions = (1 until frames.size).filter { i ->
            first(frames[i]) != first(frames[i - 1]) || second(frames[i]) != second(frames[i - 1])
        }
        assertEquals(1, transitions.size, "expected one same-root transition: $frames")
        val changedAt = transitions.single()
        assertTrue(first(frames[changedAt]) != first(frames[changedAt - 1]), "first view did not change: $frames")
        assertTrue(second(frames[changedAt]) != second(frames[changedAt - 1]), "second view did not change: $frames")
    }

    private fun valuations(frame: String) = arrayField(frame, "valuations")
    private fun preferences(frame: String) = arrayField(frame, "prefs")
    private fun tierAverage(frame: String) = Regex("\\\"tierAvg\\\":([^,}]+)").find(frame)?.groupValues?.get(1).orEmpty()
    private fun preferenceAverages(frame: String) =
        Regex("\\\"prefAvg\\\":([^,}]+)").findAll(frame).joinToString("|") { it.groupValues[1] }

    private fun arrayField(frame: String, name: String): String =
        Regex("\\\"$name\\\":(\\[[^]]*])").find(frame)?.groupValues?.get(1)
            ?: error("missing $name in $frame")
}

private fun collectTieringFrames(
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
