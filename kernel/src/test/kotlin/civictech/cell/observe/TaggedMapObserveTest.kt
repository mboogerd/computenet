package civictech.cell.observe

import civictech.cell.Timestamp
import civictech.cell.data.OrMapApi
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.graph.TypedRef
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `View.taggedMap()` / `ObserveAllBuilder.taggedMap` — the observation seam
 * over `OrMapCell` (KE1 E1.5, view half; feature computenet-j2x.2). Task 2
 * consumes `TaggedMapView` (computenet-j2x.2.1); these tests exercise the
 * registration and the hosted composition, not the fold algebra itself
 * (that is [civictech.cell.data.TaggedMapViewTest]'s job).
 *
 * BS-5 (sink half, [KE1-15]): a sink built over [View.taggedMap] fires
 * [ObservationSink.onChange] exactly when `apply` returned true.
 * BS-7 ([KE1-16], [KE1-32] view half): a late-linked sink is seeded by
 * producer `onLinked` catch-up so its `current()` equals a batch fold of the
 * cell's `state()`, tombstones included.
 */
class TaggedMapObserveTest {

    private var tagCounter = 0L
    private fun freshTag() = Timestamp(UUID(0, ++tagCounter), tagCounter)

    private fun putDelta(key: String, value: String, tag: Timestamp = freshTag()) =
        TaggedMapDelta<String, String>(puts = mapOf(key to mapOf(tag to value)))

    // ---- View.taggedMap() sits beside set()/map()/count() (compile-time fact) ----

    @Test
    fun `View taggedMap is registered beside the other three factories`() {
        // compiles ⇒ passes: View.taggedMap<K, V>() exists in the same companion
        // as set()/map()/count(), with the same View<D, S> shape.
        val view: View<TaggedMapDelta<String, Int>, Map<String, Int>> = View.taggedMap()
        view.current() shouldBe emptyMap()
    }

    // ---- BS-5 sink half: onChange fires exactly when apply returned true ----

    @Test
    fun `onChange fires exactly when apply returned true`() {
        val sink = ObserveCell(View.taggedMap<String, String>())

        val fires = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        sink.onChange { fires += it }
        // registration catch-up: one call with the (empty) snapshot
        civictech.testkit.awaitUntil("registration catch-up") { fires.size >= 1 }
        fires.toList() shouldBe listOf(emptyMap())

        val dotA = freshTag()
        sink.inlet.call.propagate(putDelta("a", "1", dotA)) // effective: a appears

        // re-delivered/duplicate dot: the exact same put again — no new dot,
        // no presence/value change ⇒ apply false ⇒ no fire
        sink.inlet.call.propagate(putDelta("a", "1", dotA))

        // a remove of an absent key: a tombstone that covers nothing live ⇒
        // apply false ⇒ no fire
        sink.inlet.call.propagate(TaggedMapDelta(dels = mapOf("missing" to setOf(freshTag()))))

        sink.current() shouldBe mapOf("a" to "1")
        awaitExactlyOneEffectiveFire(fires)
        sink.close()
    }

    /**
     * registration catch-up + one effective change = two calls total, never
     * three (the duplicate put and the no-op remove must not fire).
     */
    private fun awaitExactlyOneEffectiveFire(fires: MutableList<Map<String, String>>) {
        civictech.testkit.awaitUntil("exactly one effective fire after catch-up") { fires.size >= 2 }
        // give any (incorrect) extra fire a chance to land before asserting
        Thread.sleep(50)
        fires.toList() shouldBe listOf(emptyMap(), mapOf("a" to "1"))
    }

    // ---- BS-7: late-linked sink is seeded by catch-up, tombstones included ----

    @Test
    fun `a late-linked taggedMap sink is seeded by catch-up with removed keys absent`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val writer = OrMapCell<String, String>()
        mgmt.spawn(writer)

        // several puts and one remove BEFORE anyone observes
        writer.inlet.call.put("a", "1")
        writer.inlet.call.put("b", "2")
        writer.inlet.call.put("c", "3")
        writer.inlet.call.remove("b")
        controller.runToIdle()

        val sink = host.observe(writer.ref, View.taggedMap<String, String>())
        controller.runToIdle()

        // current() equals a batch fold of the cell's state() — the removed
        // key is absent, no per-op replay
        sink.current() shouldBe mapOf("a" to "1", "c" to "3")
        ("b" in sink.current()) shouldBe false

        // and it keeps folding live changes afterwards
        writer.inlet.call.put("d", "4")
        writer.inlet.call.remove("a")
        controller.runToIdle()
        sink.current() shouldBe mapOf("c" to "3", "d" to "4")
    }

    // ---- observeAll: both taggedMap builder overloads round-trip ----

    @Test
    fun `observeAll taggedMap round-trips both the untyped and typed builder overloads`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val mgmt = host.managementInlet.call

        val board = OrMapCell<String, String>()
        val typedBoard = OrMapCell<String, String>()
        mgmt.spawn(board)
        mgmt.spawn(typedBoard)

        board.inlet.call.put("x", "1")
        typedBoard.inlet.call.put("y", "2")
        controller.runToIdle()

        val typedRef = TypedRef<OrMapApi<String, String>>(typedBoard.ref)

        val view = host.observeAll {
            taggedMap("board", board.ref)          // untyped CellRef overload
            taggedMap("typedBoard", typedRef)      // typed TypedRef<OrMapApi<K, V>> overload
        }
        controller.runToIdle()

        civictech.testkit.awaitUntil("observeAll taggedMap catch-up") {
            view.current()["board"] == mapOf("x" to "1") &&
                view.current()["typedBoard"] == mapOf("y" to "2")
        }

        view.get<Map<String, String>>("board") shouldBe mapOf("x" to "1")
        view.get<Map<String, String>>("typedBoard") shouldBe mapOf("y" to "2")

        view.close()
    }
}
