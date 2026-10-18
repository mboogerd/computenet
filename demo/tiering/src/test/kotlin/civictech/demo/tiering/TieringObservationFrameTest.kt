package civictech.demo.tiering

import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TieringObservationFrameTest {

    @Test
    fun `one valuation converges the valuation and average views`() {
        val app = TieringApp(port = 0).start()
        try {
            assertEquals(
                setOf("items", "valuations", "tierAvg", "prefs", "prefAvg", "fused", "manual", "board"),
                app.observationViews,
            )
            assertEquals(
                listOf("items", "valuations", "tierAvg", "prefs", "prefAvg", "fused", "manual", "board"),
                app.observationViews.toList(),
            )
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)
            probe.post("action=item&name=pizza")
            probe.await { "\"unrated\":[\"pizza\"]" in it }

            val publications = CopyOnWriteArrayList<Map<String, Any?>>()
            app.onValuationSnapshot { publications += it }
            awaitUntil("initial valuation snapshot", timeoutMs = 5_000) { publications.isNotEmpty() }

            val frames = collectTieringFrames(
                "$base/events",
                onSubscribed = { probe.post("action=tier&agent=ada&item=pizza&tier=S") },
            ) { "\"tierAvg\":6.0000" in it && "\"agent\":\"ada\"" in it }

            val terminal = frames.last()
            assertTrue("\"tierAvg\":6.0000" in terminal, "tier average did not converge: $terminal")
            assertTrue("\"agent\":\"ada\"" in terminal, "valuation did not converge: $terminal")
            val expected = mapOf(
                "valuations" to setOf(Valuation("ada", "pizza", 6L)),
                "tierAvg" to mapOf("pizza" to 6.0),
            )
            assertOnePublication(publications, expected)
        } finally {
            app.stop()
        }
    }

    @Test
    fun `one preference converges the preference and average views`() {
        val app = TieringApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)
            probe.post("action=item&name=pizza")
            probe.post("action=item&name=sushi")
            probe.await { "\"unrated\":[\"pizza\",\"sushi\"]" in it }

            val publications = CopyOnWriteArrayList<Map<String, Any?>>()
            app.onPreferenceSnapshot { publications += it }
            awaitUntil("initial preference snapshot", timeoutMs = 5_000) { publications.isNotEmpty() }

            val frames = collectTieringFrames(
                "$base/events",
                onSubscribed = { probe.post("action=pref&agent=ada&winner=sushi&loser=pizza") },
            ) { "\"prefAvg\":1.0000" in it && "\"winner\":\"sushi\"" in it }

            val terminal = frames.last()
            assertTrue("\"prefAvg\":1.0000" in terminal, "preference average did not converge: $terminal")
            assertTrue("\"winner\":\"sushi\"" in terminal, "preference did not converge: $terminal")
            val expected = mapOf(
                "prefs" to setOf(Pref("ada", "sushi", "pizza")),
                "prefAvg" to mapOf("pizza" to -1.0, "sushi" to 1.0),
            )
            assertOnePublication(publications, expected)
        } finally {
            app.stop()
        }
    }

    private fun assertOnePublication(
        publications: List<Map<String, Any?>>,
        expected: Map<String, Any?>,
    ) {
        awaitUntil("complete observation publication", timeoutMs = 5_000) {
            publications.lastOrNull()?.let { latest ->
                expected.all { (name, value) -> latest[name] == value }
            } == true
        }
        assertEquals(expected, publications.last().filterKeys { it in expected.keys })
    }
}

private fun collectTieringFrames(
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
