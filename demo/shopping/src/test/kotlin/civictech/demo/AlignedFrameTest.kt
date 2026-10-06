package civictech.demo

import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import civictech.testkit.awaitSseData
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Single-JVM proof of the aligned `{items, produce}` sink wired in `Main.kt`
 * (computenet-sozzn.1, `[22-OBS-01]`/`[22-OBS-02]`, G-13's no-phantom-hold
 * rule): an `add`/`remove` in `produce`'s filter range settles as exactly one
 * SSE frame in which both fields change, `produce` is always a subset of
 * `items`, and the aligned sink drains to zero buffered waves at idle.
 *
 * **Frame-attribution method**: because `broadcast` frames from different
 * hubs arrive from different dispatcher threads, a frame from another hub is
 * not ordered against an aligned frame — but two waves through the *aligned*
 * sink are FIFO on its own single-consumer dispatcher. So each test
 * subscribes (the first frame is the initial state), performs the op(s)
 * under test, then a sentinel `add` of an item outside the `a..m` range
 * (`zebra`, which changes `items` only) and collects frames up to and
 * including the one where `zebra` first appears in `items`. The frames
 * strictly between the initial frame and the sentinel frame are the op's own
 * frames.
 */
class AlignedFrameTest {

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

    private fun assertProduceSubsetOfItems(frames: List<String>) {
        frames.forEach { frame ->
            val produce = produceOf(frame)
            val items = itemsOf(frame)
            assertTrue(
                produce.all { it in items },
                "produce not a subset of items in frame: $frame (produce=$produce, items=$items)",
            )
        }
    }

    /** Frame indices `i` (1 until frames.size) at which both `items` and `produce` changed vs `frames[i - 1]`. */
    private fun bothChangedIndices(frames: List<String>): List<Int> =
        (1 until frames.size).filter { i ->
            itemsOf(frames[i]) != itemsOf(frames[i - 1]) && produceOf(frames[i]) != produceOf(frames[i - 1])
        }

    @Test
    fun `canonical observation partitions shopping views by equal roots`() {
        val app = DemoApp(port = 0).start()
        try {
            assertEquals(
                setOf("items+produce", "votes", "wanted"),
                app.observationGroups,
            )
        } finally {
            app.stop()
        }
    }

    @Test
    fun `an in-range add settles as one aligned frame with both fields changed`() {
        val app = DemoApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)

            val frames = collectSseFrames(
                "$base/events",
                onSubscribed = {
                    probe.post("user=tester&action=add&item=apples")
                    probe.post("user=tester&action=add&item=zebra")
                },
            ) { "zebra" in itemsOf(it) }

            assertProduceSubsetOfItems(frames)

            // op's own frames: strictly between the initial frame (index 0) and
            // the sentinel frame (last index, where zebra enters items).
            val opFrames = frames.subList(0, frames.size - 1)
            val changed = bothChangedIndices(opFrames)
            assertEquals(
                1, changed.size,
                "expected exactly one frame with both items and produce changed by the `add apples` op; " +
                    "frames=$opFrames",
            )
            assertTrue("apples" in itemsOf(opFrames[changed.single()]), "the changed frame never shows apples")
            assertTrue("apples" in produceOf(opFrames[changed.single()]), "the changed frame never shows apples in produce")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `an in-range remove settles as one aligned frame with both fields changed`() {
        val app = DemoApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)

            // seed apples and wait until it is visible before subscribing, so the
            // aligned sink's own subscription starts from a settled baseline.
            probe.post("user=tester&action=add&item=apples")
            awaitSseData("$base/events", timeoutMs = 5_000) { "apples" in it }

            val frames = collectSseFrames(
                "$base/events",
                onSubscribed = {
                    probe.post("user=tester&action=remove&item=apples")
                    probe.post("user=tester&action=add&item=zebra")
                },
            ) { "zebra" in itemsOf(it) }

            assertProduceSubsetOfItems(frames)

            val opFrames = frames.subList(0, frames.size - 1)
            val changed = bothChangedIndices(opFrames)
            assertEquals(
                1, changed.size,
                "expected exactly one frame with both items and produce changed by the `remove apples` op; " +
                    "frames=$opFrames",
            )
            assertTrue("apples" !in itemsOf(opFrames[changed.single()]), "the changed frame still shows apples in items")
            assertTrue(
                "apples" !in produceOf(opFrames[changed.single()]),
                "the changed frame still shows apples in produce",
            )
        } finally {
            app.stop()
        }
    }

    @Test
    fun `mixed item and vote ops never expose produce ahead of items, and the aligned sink drains to idle`() {
        val app = DemoApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)

            val frames = collectSseFrames(
                "$base/events",
                onSubscribed = {
                    probe.post("user=tester&action=add&item=banana")
                    probe.post("user=tester&action=vote&item=banana")
                    probe.post("user=tester&action=add&item=zucchini")
                    probe.post("user=tester&action=vote&item=apricot") // unlisted item's vote
                    probe.post("user=tester&action=remove&item=banana")
                    probe.post("user=tester&action=add&item=zebra")
                },
            ) { "zebra" in itemsOf(it) }

            assertProduceSubsetOfItems(frames)

            awaitUntil("aligned sink idle", timeoutMs = 5_000) { app.alignedBufferedWaves == 0 }
        } finally {
            app.stop()
        }
    }
}

/**
 * Subscribes to [url], returns every `data:` payload up to and including the
 * first one satisfying [until]. Bounded by [timeoutMs] — [awaitSseData]'s
 * discipline: fails naming [url] rather than parking until JUnit's suite
 * timeout. Built on [SseTap] (already in `:testkit`, unmodified here), whose
 * async subscription starts on construction, so [onSubscribed] — invoked once
 * the tap's own initial-state frame has arrived — is where a caller safely
 * issues the ops it wants captured, on the same thread, with no extra
 * concurrency of its own. `internal` and top-level so the two-JVM sibling
 * task (computenet-sozzn.2) can reuse it against `TwoJvmConvergenceTest.kt`.
 */
internal fun collectSseFrames(
    url: String,
    timeoutMs: Long = 5_000,
    onSubscribed: (() -> Unit)? = null,
    until: (String) -> Boolean,
): List<String> {
    SseTap(url) { it }.use { tap ->
        awaitUntil("initial SSE frame from $url", timeoutMs = timeoutMs) { tap.frames().isNotEmpty() }
        onSubscribed?.invoke()
        awaitUntil("SSE frame from $url satisfying `until`", timeoutMs = timeoutMs) { tap.frames().any(until) }
        val frames = tap.frames()
        val cutoff = frames.indexOfFirst(until)
        return frames.subList(0, cutoff + 1)
    }
}
