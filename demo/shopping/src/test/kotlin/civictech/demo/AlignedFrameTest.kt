package civictech.demo

import civictech.cell.observe.ObservationFrame
import civictech.testkit.HttpProbe
import civictech.testkit.SseTap
import civictech.testkit.awaitSseData
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Single-JVM proof of the aligned `{items, produce}` sink wired in `Main.kt`
 * (computenet-sozzn.1, `[22-OBS-01]`/`[22-OBS-02]`, G-13's no-phantom-hold
 * rule): an `add`/`remove` in `produce`'s filter range settles as exactly one
 * SSE frame in which both fields change, `produce` is always a subset of
 * `items`, and the aligned sink drains to zero buffered waves at idle.
 *
 * **Frame-attribution method**: the multi-group observation listener receives
 * the frame for the group publication that triggered it, and `broadcast()`
 * serializes that delivered frame. Each test subscribes (the first frame is
 * the initial state), performs the op(s) under test, waits until the tap shows
 * the op's own aligned state, then posts a sentinel `add` of an item outside
 * the `a..m` range (`zebra`, which changes `items` only). The frames strictly
 * between the initial frame and the sentinel frame are consequently the op's
 * own frames, including every intermediate frame that would expose a
 * non-atomic aligned publication.
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
    fun `replication keeps shared observation in its own root group`() {
        val app = DemoApp(port = 0, replicate = true).start()
        try {
            assertEquals(
                setOf("items+produce", "votes", "wanted", "shared"),
                app.observationGroups,
            )
        } finally {
            app.stop()
        }
    }

    @Test
    fun `item-only ops leave the votes group frontier unchanged`() {
        val app = DemoApp(port = 0).start()
        try {
            val base = "http://localhost:${app.boundPort}"
            val probe = HttpProbe(base)

            // Establish a non-empty votes frontier, then advance only the item
            // writer. The independent votes group must not move with those ops.
            probe.post("user=tester&action=vote&item=banana")
            awaitSseData(base + "/events", timeoutMs = 5_000) { "\"banana\"" in it }
            val afterVote = app.observationFrontier("votes")

            probe.post("user=tester&action=add&item=apples")
            awaitSseData(base + "/events", timeoutMs = 5_000) { "\"apples\"" in it }
            val afterFirstItem = app.observationFrontier("votes")

            probe.post("user=tester&action=add&item=pears")
            awaitSseData(base + "/events", timeoutMs = 5_000) { "\"pears\"" in it }
            val afterSecondItem = app.observationFrontier("votes")

            assertEquals(afterVote, afterFirstItem, "an item-only op advanced the votes group frontier")
            assertEquals(afterFirstItem, afterSecondItem, "a second item-only op advanced the votes group frontier")
        } finally {
            app.stop()
        }
    }

    @Test
    fun `an in-range add response completes only after its aligned frame is published`() {
        val app = DemoApp(port = 0).start()
        try {
            val probe = HttpProbe("http://localhost:${app.boundPort}")
            // Materialize and cache this user's keyed writers before occupying
            // the host queue; writerFor performs awaited management lookups.
            assertEquals(200, probe.post("user=tester&action=add&item=zebra"))

            val releaseHost = CountDownLatch(1)
            val hostHeld = CountDownLatch(1)
            val holder = CompletableFuture.runAsync {
                app.holdHostForTest {
                    hostHeld.countDown()
                    check(releaseHost.await(5, TimeUnit.SECONDS)) { "test did not release the host queue" }
                }
            }
            try {
                assertTrue(hostHeld.await(5, TimeUnit.SECONDS), "host queue was not held for the completion-order proof")
                val frameAtCompletion = CompletableFuture<ObservationFrame>()
                app.itemHandleProbe = { handle ->
                    // A correct handle is still pending while the host is held.
                    // A premature completion executes this dependent now and
                    // captures the pre-apples frame, which the assertions below reject.
                    handle.thenAccept { frameAtCompletion.complete(app.observationFrame()) }
                    releaseHost.countDown()
                }

                assertEquals(200, probe.post("user=tester&action=add&item=apples"))

                val completedFrame = frameAtCompletion.get(5, TimeUnit.SECONDS)
                assertEquals(
                    setOf("apples", "zebra"),
                    completedFrame.views.getValue("items"),
                    "the handle completed before its item was published by the aligned sink",
                )
                assertEquals(
                    setOf("apples"),
                    completedFrame.views.getValue("produce"),
                    "the handle completed before its derived produce change was published by the aligned sink",
                )
            } finally {
                releaseHost.countDown()
                holder.get(5, TimeUnit.SECONDS)
            }
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
                onSubscribed = { tap ->
                    probe.post("user=tester&action=add&item=apples")
                    awaitUntil("aligned SSE frame for `add apples`", timeoutMs = 5_000) {
                        tap.frames().any { frame ->
                            "apples" in itemsOf(frame) && "apples" in produceOf(frame)
                        }
                    }
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
            awaitUntil("aligned sink idle", timeoutMs = 5_000) { app.alignedBufferedWaves == 0 }
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
                onSubscribed = { tap ->
                    probe.post("user=tester&action=remove&item=apples")
                    awaitUntil("aligned SSE frame for `remove apples`", timeoutMs = 5_000) {
                        tap.frames().any { frame ->
                            "apples" !in itemsOf(frame) &&
                                "apples" !in produceOf(frame) &&
                                "zebra" !in itemsOf(frame)
                        }
                    }
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
            awaitUntil("aligned sink idle", timeoutMs = 5_000) { app.alignedBufferedWaves == 0 }
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
                onSubscribed = { tap ->
                    probe.post("user=tester&action=add&item=banana")
                    probe.post("user=tester&action=vote&item=banana")
                    probe.post("user=tester&action=add&item=zucchini")
                    probe.post("user=tester&action=vote&item=apricot") // unlisted item's vote
                    probe.post("user=tester&action=remove&item=banana")
                    awaitUntil("aligned frame before mixed-op sentinel", timeoutMs = 5_000) {
                        tap.frames().any { frame ->
                            "zucchini" in itemsOf(frame)
                        }
                    }
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
