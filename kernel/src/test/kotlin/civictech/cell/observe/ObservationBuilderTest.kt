package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.data.OrMapApi
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.TypedRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.testkit.awaitUntil
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exit criterion for the canonical point-consistent app-edge [observation] builder. */
class ObservationBuilderTest {

    private interface IntSetInlet {
        val inlet: Use<SetOps<Int>>
    }

    private class ShoppingGraph(seed: Long = 0) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry, scheduler = controller.scheduler())
        val writerA = SetCell<Int>()
        val writerB = SetCell<Int>()
        val writerV = SetCell<Int>()
        val items = UnionSetCell<Int>()
        val votes = UnionSetCell<Int>()
        val produce = FilterCell<Int> { it % 2 == 0 }

        init {
            val management = host.managementInlet.call
            listOf(writerA, writerB, writerV, items, votes, produce).forEach(management::spawn)
            management.connect(writerA.ref, "outlet", items.ref, "inlet")
            management.connect(writerB.ref, "outlet", items.ref, "inlet")
            management.connect(writerV.ref, "outlet", votes.ref, "inlet")
            management.connect(items.ref, "outlet", produce.ref, "inlet")
        }

        fun ops(cell: SetCell<Int>): SetOps<Int> = host.lookup<IntSetInlet>(cell.ref)!!.inlet.call
    }

    @Test
    fun `registered views use one sink each and converge across seeded schedules`() {
        val waves = 20
        for (seed in 0L until 50L) {
            val graph = ShoppingGraph(seed)
            val sinkRefs = mapOf(
                "items" to CellRef(UUID.nameUUIDFromBytes("items-$seed".toByteArray())),
                "produce" to CellRef(UUID.nameUUIDFromBytes("produce-$seed".toByteArray())),
            )
            val requestedRefs = mutableListOf<String>()
            val refsBefore = graph.registry.localRefs().size
            val observation = graph.host.observation(
                sinkRef = { name -> requestedRefs += name; sinkRefs.getValue(name) },
            ) {
                set("items", graph.items.ref)
                set("produce", graph.produce.ref)
            }

            observation.views.toList() shouldBe listOf("items", "produce")
            requestedRefs shouldBe listOf("items", "produce")
            graph.registry.localRefs().size shouldBe refsBefore + 2
            sinkRefs.forEach { (name, ref) -> observation.sink(name).ref shouldBe ref }

            val frames = Collections.synchronizedList(mutableListOf<ObservationFrame>())
            observation.onChange { frames += it }
            val a = graph.ops(graph.writerA)
            val b = graph.ops(graph.writerB)
            val random = Random(seed)
            for (value in 1..waves) {
                if ((value + seed) % 2L == 0L) a.add(value) else b.add(value)
                repeat(random.nextInt(4)) { graph.controller.step() }
            }
            graph.controller.runToIdle()

            val expectedItems = (1..waves).toSet()
            val expectedProduce = (1..waves).filter { it % 2 == 0 }.toSet()
            awaitUntil("canonical views converge (seed $seed)") {
                observation.current().views == mapOf(
                    "items" to expectedItems,
                    "produce" to expectedProduce,
                )
            }
            awaitUntil("converged frame dispatched (seed $seed)") {
                frames.lastOrNull()?.views == mapOf(
                    "items" to expectedItems,
                    "produce" to expectedProduce,
                )
            }
            synchronized(frames) {
                frames.all { it.views.keys.toList() == listOf("items", "produce") }
            } shouldBe true
            observation.get<Set<Int>>("items") shouldBe expectedItems
            observation.get<Set<Int>>("produce") shouldBe expectedProduce

            observation.close()
        }
    }

    @Test
    fun `one view uses the sink dispatcher and stable sink ref`() {
        val graph = ShoppingGraph(seed = 91)
        val expectedRef = CellRef(UUID.fromString("3ddde027-6305-4ef0-b694-c9e420a3d216"))
        val observation = graph.host.observation(sinkRef = { expectedRef }) {
            set("votes", graph.votes.ref)
        }
        observation.views shouldBe setOf("votes")
        observation.sink("votes").ref shouldBe expectedRef

        val frames = Collections.synchronizedList(mutableListOf<ObservationFrame>())
        val callbackThreads = Collections.synchronizedList(mutableListOf<String>())
        observation.onChange {
            callbackThreads += Thread.currentThread().name
            frames += it
        }
        val votes = graph.ops(graph.writerV)
        votes.add(1)
        votes.add(2)
        votes.add(3)
        graph.controller.runToIdle()

        awaitUntil("one-view catch-up and three changes") { frames.size >= 4 }
        frames.size shouldBe 4
        observation.get<Set<Int>>("votes") shouldBe setOf(1, 2, 3)
        frames.last().views.getValue("votes") shouldBe setOf(1, 2, 3)
        synchronized(callbackThreads) {
            callbackThreads.all { it == "observe-cell-${expectedRef.id}" }
        } shouldBe true

        observation.close()
    }

    @Test
    fun `current immediately reflects a sink publication after the host drains`() {
        val graph = ShoppingGraph(seed = 92)
        val observation = graph.host.observation {
            set("votes", graph.votes.ref)
        }
        val sink = observation.sink("votes")
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        sink.onChange {
            callbackStarted.countDown()
            releaseCallback.await(5, TimeUnit.SECONDS)
        }
        callbackStarted.await(1, TimeUnit.SECONDS) shouldBe true

        try {
            graph.ops(graph.writerV).add(7)
            graph.controller.runToIdle()

            observation.current() shouldBe ObservationFrame(
                views = mapOf("votes" to sink.current()),
            )
        } finally {
            releaseCallback.countDown()
            observation.close()
        }
    }

    @Test
    fun `multi-view callbacks share one dispatcher and terminate`() {
        val graph = ShoppingGraph(seed = 93)
        val observation = graph.host.observation {
            set("items", graph.items.ref)
            set("produce", graph.produce.ref)
        }
        val catchUp = observation.current()
        val frames = Collections.synchronizedList(mutableListOf<ObservationFrame>())
        val callbackThreads = Collections.synchronizedList(mutableListOf<String>())
        observation.onChange {
            callbackThreads += Thread.currentThread().name
            frames += it
        }

        awaitUntil("multi-view catch-up") { frames.firstOrNull() == catchUp }
        graph.ops(graph.writerA).add(2)
        graph.controller.runToIdle()
        awaitUntil("multi-view change dispatched") {
            frames.lastOrNull()?.views == mapOf("items" to setOf(2), "produce" to setOf(2))
        }

        observation.close()
        observation.awaitTermination(1_000) shouldBe true
        synchronized(callbackThreads) {
            callbackThreads.all { it.startsWith("observation-") }
        } shouldBe true
    }

    @Test
    fun `duplicate names reject before spawning`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val management = host.managementInlet.call
        val a = SetCell<Int>()
        val b = SetCell<Int>()
        management.spawn(a)
        management.spawn(b)
        val refsBefore = registry.localRefs().size
        val sinkRefCalls = AtomicInteger()

        val error = assertThrows<IllegalArgumentException> {
            host.observation(
                sinkRef = { sinkRefCalls.incrementAndGet(); CellRef(UUID.randomUUID()) },
            ) {
                set("x", a.ref)
                set("x", b.ref)
            }
        }

        error.message shouldBe "duplicate observe name 'x'"
        sinkRefCalls.get() shouldBe 0
        registry.localRefs().size shouldBe refsBefore
    }

    @Test
    fun `tagged map supports both untyped and typed registrars`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val management = host.managementInlet.call
        val board = OrMapCell<String, String>()
        val typedBoard = OrMapCell<String, String>()
        management.spawn(board)
        management.spawn(typedBoard)
        board.inlet.call.put("x", "1")
        typedBoard.inlet.call.put("y", "2")
        controller.runToIdle()

        val observation = host.observation {
            taggedMap("board", board.ref)
            taggedMap("typedBoard", TypedRef<OrMapApi<String, String>>(typedBoard.ref))
        }
        controller.runToIdle()
        awaitUntil("canonical tagged-map catch-up") {
            observation.current().views["board"] == mapOf("x" to "1") &&
                observation.current().views["typedBoard"] == mapOf("y" to "2")
        }
        observation.get<Map<String, String>>("board") shouldBe mapOf("x" to "1")
        observation.get<Map<String, String>>("typedBoard") shouldBe mapOf("y" to "2")

        observation.close()
    }
}
