package civictech.demo

import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Single-JVM convergence proof for the shopping observation sinks. */
class ObservationFrameTest {

    private val itemsRe = Regex(""""items":\[([^]]*)]""")
    private val produceRe = Regex(""""produce":\[([^]]*)]""")

    private fun fieldOf(regex: Regex, frame: String): Set<String> =
        regex.find(frame)?.groupValues?.get(1).orEmpty()
            .split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun itemsOf(frame: String) = fieldOf(itemsRe, frame)
    private fun produceOf(frame: String) = fieldOf(produceRe, frame)

    @Test
    fun `canonical observation exposes every shopping view`() {
        val app = DemoApp(port = 0).start()
        try {
            assertEquals(
                setOf("items", "produce", "votes", "wanted"),
                app.observationViews,
            )
        } finally {
            app.stop()
        }
    }

    @Test
    fun `replication exposes the shared shopping view`() {
        val app = DemoApp(port = 0, replicate = true).start()
        try {
            assertEquals(
                setOf("items", "produce", "votes", "wanted", "shared"),
                app.observationViews,
            )
        } finally {
            app.stop()
        }
    }

    @Test
    fun `an add converges across the items and produce views`() {
        val app = DemoApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)
            val frames = collectSseFrames(
                "$base/events",
                onSubscribed = { tap ->
                    probe.post("user=tester&action=add&item=apples")
                    awaitUntil("add apples reached the items view", timeoutMs = 5_000) {
                        tap.frames().any { frame -> "apples" in itemsOf(frame) }
                    }
                    probe.post("user=tester&action=add&item=zebra")
                },
            ) { "zebra" in itemsOf(it) }

            val terminal = frames.last()
            val items = itemsOf(terminal)
            val produce = produceOf(terminal)
            assertTrue("apples" in items, "terminal observation omitted apples from items: $terminal")
            assertTrue("apples" in produce, "terminal observation omitted apples from produce: $terminal")
            assertEquals(
                items.filter { it.firstOrNull()?.lowercaseChar() in 'a'..'m' }.toSet(),
                produce,
                "terminal produce view did not equal the app filter: $terminal",
            )
        } finally {
            app.stop()
        }
    }
}

/**
 * Subscribes to [url], returns every `data:` payload up to and including the
 * first one satisfying [until]. Bounded by [timeoutMs], so a silent stream
 * fails naming [url] rather than parking until JUnit's suite timeout. Built on
 * [SseTap] (already in `:testkit`, unmodified here), whose
 * async subscription starts on construction, so [onSubscribed] — invoked with
 * the tap once its own initial-state frame has arrived — is where a caller
 * safely issues the ops it wants captured and can await an operation frame on
 * that same tap, on the same thread, with no extra concurrency of its own.
 * `internal` and top-level so the two-JVM sibling task (computenet-sozzn.2) can
 * reuse it against `TwoJvmConvergenceTest.kt`.
 */
internal fun collectSseFrames(
    url: String,
    timeoutMs: Long = 5_000,
    onSubscribed: ((SseTap<String>) -> Unit)? = null,
    until: (String) -> Boolean,
): List<String> {
    SseTap(url) { it }.use { tap ->
        awaitUntil("initial SSE frame from $url", timeoutMs = timeoutMs) { tap.frames().isNotEmpty() }
        onSubscribed?.invoke(tap)
        awaitUntil("SSE frame from $url satisfying `until`", timeoutMs = timeoutMs) { tap.frames().any(until) }
        val frames = tap.frames()
        val cutoff = frames.indexOfFirst(until)
        return frames.subList(0, cutoff + 1)
    }
}
