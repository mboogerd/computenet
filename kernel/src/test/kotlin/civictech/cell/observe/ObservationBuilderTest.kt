package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.OrMapApi
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.TypedRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.inlet
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.streamTo
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exit criterion for the canonical app-edge [observation] builder: equal root
 * sets share one aligned sink, unequal root sets never gate each other, and the
 * combined frame states exactly which group frontiers remain comparable.
 */
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
        val wanted = IntersectSetCell<Int>()

        init {
            val management = host.managementInlet.call
            listOf(writerA, writerB, writerV, items, votes, produce, wanted).forEach(management::spawn)
            management.connect(writerA.ref, "outlet", items.ref, "inlet")
            management.connect(writerB.ref, "outlet", items.ref, "inlet")
            management.connect(writerV.ref, "outlet", votes.ref, "inlet")
            management.connect(items.ref, "outlet", produce.ref, "inlet")
            management.connect(items.ref, "outlet", wanted.ref, "left")
            management.connect(votes.ref, "outlet", wanted.ref, "right")
        }

        fun ops(cell: SetCell<Int>): SetOps<Int> = host.lookup<IntSetInlet>(cell.ref)!!.inlet.call
    }

    private fun rerouteThroughHostQueue(
        host: ManagedHost,
        outlet: Subscribe<Propagate<SetDelta<Int>>>,
        inletRef: PortRef,
        target: CellRef,
        portName: String,
    ) {
        val routed: Propagate<SetDelta<Int>> = host.inlet(target, portName)
        outlet.unsubscribe(inletRef)
        outlet.subscribe(Use.fixed(routed, inletRef))
    }

    private fun observedGroups(host: ManagedHost, union: CellRef, source: CellRef): Set<String> {
        val observation = host.observation {
            set("union", union)
            set("source", source)
        }
        return try {
            observation.groups
        } finally {
            observation.close()
        }
    }

    /**
     * Keeps the never-attached handle live while the first observation is
     * built, then returns only a weak reference so the test can prove the
     * post-collection partition is identical.
     */
    private fun groupsWithNeverLinkedHandleAlive(
        host: ManagedHost,
        union: CellRef,
        source: CellRef,
    ): Pair<Set<String>, WeakReference<Propagate<SetDelta<Int>>>> {
        val routed: Propagate<SetDelta<Int>> = host.inlet(union, "inlet")
        val weak = WeakReference(routed)
        return observedGroups(host, union, source) to weak
    }

    /**
     * Keeps the detached handle live while the first observation is built.
     * The bypass link does not retain the routed target after unlink.
     */
    private fun groupsWithUnlinkedHandleAlive(
        host: ManagedHost,
        writer: SetCell<Int>,
        union: CellRef,
        source: CellRef,
    ): Pair<Set<String>, WeakReference<Propagate<SetDelta<Int>>>> {
        val routed: Propagate<SetDelta<Int>> = host.inlet(union, "inlet")
        val weak = WeakReference(routed)
        writer.outlet.streamTo(routed).unlink()
        return observedGroups(host, union, source) to weak
    }

    /** Force enough collection to distinguish a weak index from live topology. */
    private fun collectRoutedHandle() {
        repeat(8) {
            System.gc()
            @Suppress("UNUSED_EXPRESSION")
            ByteArray(1 shl 20)
        }
        System.gc()
    }

    @Test
    fun `unmanaged union feed keeps its managed source in a separate group regardless of link order`() {
        listOf(true, false).forEach { linkBeforeObservation ->
            val controller = SimulationController()
            val host = ManagedHost(scheduler = controller.scheduler())
            val management = host.managementInlet.call
            val source = SetCell<Int>()
            val writer = SetCell<Int>()
            val union = UnionSetCell<Int>()
            listOf(source, writer, union).forEach(management::spawn)

            writer.outlet.streamTo(union.inlet.call, at = union.inlet.ref)
            management.upstreamConsumeAncestors(union.ref).opaque shouldBe setOf(writer.outlet.ref)
            if (linkBeforeObservation) {
                management.connect(source.ref, "outlet", union.ref, "inlet")
            }

            val observation = host.observation {
                set("union", union.ref)
                set("source", source.ref)
            }

            observation.groups shouldContainExactly setOf("union", "source")
            observation.current().groupOf shouldBe mapOf(
                "union" to "union",
                "source" to "source",
            )

            if (!linkBeforeObservation) {
                management.connect(source.ref, "outlet", union.ref, "inlet")
            }
            observation.close()
        }
    }

    @Test
    fun `anonymous routed union feed does not collapse onto its managed source`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val management = host.managementInlet.call
        val source = SetCell<Int>()
        val writer = SetCell<Int>()
        val union = UnionSetCell<Int>()
        listOf(source, writer, union).forEach(management::spawn)
        management.connect(source.ref, "outlet", union.ref, "inlet")

        val routed: Propagate<SetDelta<Int>> = host.inlet(union.ref, "inlet")
        writer.outlet.streamTo(routed)
        val routedRoots = management.upstreamConsumeAncestors(union.ref).opaque
        routedRoots.size shouldBe 1
        routedRoots.single().cell shouldBe union.ref
        (writer.outlet.ref in routedRoots) shouldBe false

        val observation = host.observation {
            set("union", union.ref)
            set("source", source.ref)
        }

        observation.groups shouldContainExactly setOf("union", "source")
        observation.current().groupOf shouldBe mapOf(
            "union" to "union",
            "source" to "source",
        )
        observation.close()
    }

    @Test
    fun `never-linked routed handle cannot change observation groups before collection`() {
        val host = ManagedHost()
        val management = host.managementInlet.call
        val source = SetCell<Int>()
        val union = UnionSetCell<Int>()
        listOf(source, union).forEach(management::spawn)
        management.connect(source.ref, "outlet", union.ref, "inlet")

        val expected = setOf("union+source")
        val (beforeCollection, handle) = groupsWithNeverLinkedHandleAlive(
            host,
            union.ref,
            source.ref,
        )
        beforeCollection shouldContainExactly expected

        collectRoutedHandle()
        handle.get() shouldBe null
        observedGroups(host, union.ref, source.ref) shouldContainExactly expected
    }

    @Test
    fun `unlinked routed feed cannot change observation groups before collection`() {
        val host = ManagedHost()
        val management = host.managementInlet.call
        val source = SetCell<Int>()
        val writer = SetCell<Int>()
        val union = UnionSetCell<Int>()
        listOf(source, writer, union).forEach(management::spawn)
        management.connect(source.ref, "outlet", union.ref, "inlet")

        val expected = setOf("union+source")
        val (beforeCollection, handle) = groupsWithUnlinkedHandleAlive(
            host,
            writer,
            union.ref,
            source.ref,
        )
        beforeCollection shouldContainExactly expected

        collectRoutedHandle()
        handle.get() shouldBe null
        observedGroups(host, union.ref, source.ref) shouldContainExactly expected
    }

    @Test
    fun `routed feed attached on another host cannot change equal-ref observation groups`() {
        val sharedUnionRef = CellRef(UUID.randomUUID())
        val observedHost = ManagedHost()
        val observedManagement = observedHost.managementInlet.call
        val source = SetCell<Int>()
        val union = UnionSetCell<Int>(sharedUnionRef)
        listOf(source, union).forEach(observedManagement::spawn)
        observedManagement.connect(source.ref, "outlet", union.ref, "inlet")

        val otherHost = ManagedHost()
        val otherManagement = otherHost.managementInlet.call
        val otherWriter = SetCell<Int>()
        val otherUnion = UnionSetCell<Int>(sharedUnionRef)
        listOf(otherWriter, otherUnion).forEach(otherManagement::spawn)
        val otherRouted: Propagate<SetDelta<Int>> = otherHost.inlet(otherUnion.ref, "inlet")
        otherWriter.outlet.streamTo(otherRouted)

        observedGroups(observedHost, union.ref, source.ref) shouldContainExactly setOf("union+source")
    }

    @Test
    fun `hosted bypass producer remains an opaque root instead of being traversed`() {
        val host = ManagedHost()
        val management = host.managementInlet.call
        val source = SetCell<Int>()
        val writer = SetCell<Int>()
        val union = UnionSetCell<Int>()
        listOf(source, writer, union).forEach(management::spawn)
        management.connect(source.ref, "outlet", writer.ref, "inlet")
        management.connect(source.ref, "outlet", union.ref, "inlet")
        writer.outlet.streamTo(union.inlet.call, at = union.inlet.ref)

        management.upstreamConsumeAncestors(union.ref).opaque shouldBe setOf(writer.outlet.ref)
        val observation = host.observation {
            set("union", union.ref)
            set("source", source.ref)
        }

        observation.groups shouldContainExactly setOf("union", "source")
        observation.close()
    }

    @Test
    fun `declared future unmanaged feed propagates to downstream view roots`() {
        val host = ManagedHost()
        val management = host.managementInlet.call
        val source = SetCell<Int>()
        val union = UnionSetCell<Int>()
        val filtered = FilterCell<Int> { true }
        listOf(source, union, filtered).forEach(management::spawn)
        management.connect(source.ref, "outlet", union.ref, "inlet")
        management.connect(union.ref, "outlet", filtered.ref, "inlet")
        val futureWriterFamily = PortRef.generate()

        val observation = host.observation {
            unmanagedFeed(union.ref, futureWriterFamily)
            set("union", union.ref)
            set("filtered", filtered.ref)
            set("source", source.ref)
        }

        observation.groups shouldContainExactly setOf("union+filtered", "source")
        observation.current().groupOf shouldBe mapOf(
            "union" to "union+filtered",
            "filtered" to "union+filtered",
            "source" to "source",
        )
        observation.close()
    }

    @Test
    fun `unmanaged feed declared on a cell outside every view's ancestry rejects before spawning`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val management = host.managementInlet.call
        val source = SetCell<Int>()
        val stray = UnionSetCell<Int>()
        listOf(source, stray).forEach(management::spawn)

        val refsBefore = registry.localRefs().size
        val error = assertThrows<IllegalArgumentException> {
            host.observation {
                unmanagedFeed(stray.ref, PortRef.generate())
                set("source", source.ref)
            }
        }
        error.message shouldBe
            "observation: unmanaged feed targets must be a registered view or its managed ancestor: [${stray.ref}]"
        registry.localRefs().size shouldBe refsBefore
    }

    @Test
    fun `equal root views share one aligned group across seeded schedules`() {
        val waves = 20
        for (seed in 0L until 50L) {
            val graph = ShoppingGraph(seed)
            val observation = graph.host.observation {
                set("items", graph.items.ref)
                set("produce", graph.produce.ref)
            }
            observation.groups shouldContainExactly setOf("items+produce")
            observation.current().groupOf shouldBe mapOf(
                "items" to "items+produce",
                "produce" to "items+produce",
            )

            val group = observation.group("items+produce")
            rerouteThroughHostQueue(
                graph.host,
                graph.items.outlet,
                group.inlets.getValue("items").ref,
                group.ref,
                "items",
            )
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

            awaitUntil("all canonical aligned frames delivered (seed $seed)") {
                frames.size >= waves + 1
            }
            frames.size shouldBe waves + 1
            frames.forEach { frame ->
                val items = frame.views.getValue("items") as Set<*>
                val produce = frame.views.getValue("produce") as Set<*>
                produce shouldBe items.filter { (it as Int) % 2 == 0 }.toSet()
            }
            observation.get<Set<Int>>("items") shouldBe (1..waves).toSet()
            observation.get<Set<Int>>("produce") shouldBe (1..waves).filter { it % 2 == 0 }.toSet()
            observation.bufferedWaves shouldBe 0

            observation.close()
        }
    }

    @Test
    fun `one view uses the same API dispatcher catch-up and stable group ref`() {
        val graph = ShoppingGraph(seed = 91)
        val expectedRef = CellRef(UUID.fromString("3ddde027-6305-4ef0-b694-c9e420a3d216"))
        val observation = graph.host.observation(groupRef = { expectedRef }) {
            set("votes", graph.votes.ref)
        }
        observation.groups shouldBe setOf("votes")
        observation.current().groups.size shouldBe 1
        observation.group("votes").ref shouldBe expectedRef

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

        awaitUntil("one-group catch-up and three settled waves") { frames.size >= 4 }
        frames.size shouldBe 4
        observation.get<Set<Int>>("votes") shouldBe setOf(1, 2, 3)
        frames.last().views.getValue("votes") shouldBe setOf(1, 2, 3)
        synchronized(callbackThreads) {
            callbackThreads.all { it == "aligned-observe-${expectedRef.id}" }
        } shouldBe true

        observation.close()
    }

    @Test
    fun `current immediately reflects a group publication after the host drains`() {
        val graph = ShoppingGraph(seed = 92)
        val observation = graph.host.observation {
            set("votes", graph.votes.ref)
        }
        val group = observation.group("votes")
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        group.onComposite {
            callbackStarted.countDown()
            releaseCallback.await(5, TimeUnit.SECONDS)
        }
        callbackStarted.await(1, TimeUnit.SECONDS) shouldBe true

        try {
            graph.ops(graph.writerV).add(7)
            graph.controller.runToIdle()

            val published = group.composite()
            observation.current() shouldBe ObservationFrame(
                views = published.views,
                groups = mapOf("votes" to published),
                groupOf = mapOf("votes" to "votes"),
                crossRoot = emptyMap(),
            )
        } finally {
            releaseCallback.countDown()
            observation.close()
        }
    }

    @Test
    fun `unequal roots partition without holding and disclose every group pair`() {
        for (seed in 0L until 20L) {
            val graph = ShoppingGraph(seed)
            val observation = graph.host.observation {
                set("items", graph.items.ref)
                set("produce", graph.produce.ref)
                set("votes", graph.votes.ref)
                // IntersectSetCell is intentionally ungated: this is the F-27
                // independent-root shape whose output must be its own group.
                unchecked("wanted")
                set("wanted", graph.wanted.ref)
            }

            observation.groups shouldContainExactly setOf("items+produce", "votes", "wanted")
            observation.current().groupOf shouldBe mapOf(
                "items" to "items+produce",
                "produce" to "items+produce",
                "votes" to "votes",
                "wanted" to "wanted",
            )
            val frames = Collections.synchronizedList(mutableListOf<ObservationFrame>())
            val callbackThreads = Collections.synchronizedList(mutableListOf<String>())
            observation.onChange {
                callbackThreads += Thread.currentThread().name
                frames += it
            }
            awaitUntil("multi-group catch-up dispatched (seed $seed)") { frames.isNotEmpty() }
            synchronized(callbackThreads) {
                callbackThreads.all { it.startsWith("observation-") }
            } shouldBe true

            val itemA = graph.ops(graph.writerA)
            val itemB = graph.ops(graph.writerB)
            val votes = graph.ops(graph.writerV)

            // Establish one wanted value, then pre-vote every value in the
            // item-only burst. Wanted's latest publication consequently carries
            // both the item and vote source frontiers.
            itemA.add(0)
            graph.controller.runToIdle()
            for (value in 0..12) votes.add(value)
            graph.controller.runToIdle()
            awaitUntil("pre-vote state assembled (seed $seed)") {
                observation.current().views["votes"] == (0..12).toSet() &&
                    observation.current().views["wanted"] == setOf(0)
            }
            val votesFrontier = observation.current().groups.getValue("votes").frontier

            val random = Random(seed)
            for (value in 1..12) {
                if ((value + seed) % 2L == 0L) itemA.add(value) else itemB.add(value)
                repeat(random.nextInt(3)) { graph.controller.step() }
            }
            graph.controller.runToIdle()
            awaitUntil("item-only burst assembled (seed $seed)") {
                observation.current().views["items"] == (0..12).toSet() &&
                    observation.current().views["wanted"] == (0..12).toSet() &&
                    observation.bufferedWaves == 0
            }
            awaitUntil("multi-group final frame dispatched (seed $seed)") {
                frames.lastOrNull()?.views?.get("wanted") == (0..12).toSet()
            }
            synchronized(callbackThreads) {
                callbackThreads.all { it.startsWith("observation-") }
            } shouldBe true

            val frame = observation.current()
            frame.groups.getValue("votes").frontier shouldBe votesFrontier
            observation.groups.forEach { observation.group(it).bufferedWaves shouldBe 0 }
            frame.crossRoot.size shouldBe 3

            val itemVotes = frame.crossRoot.getValue(GroupPair("items+produce", "votes"))
            val itemWanted = frame.crossRoot.getValue(GroupPair("items+produce", "wanted"))
            val voteWanted = frame.crossRoot.getValue(GroupPair("votes", "wanted"))
            itemVotes.independent shouldBe true
            itemWanted.independent shouldBe false
            voteWanted.independent shouldBe false

            frame.crossRoot.forEach { (pair, disclosure) ->
                val left = frame.groups.getValue(pair.a).frontier
                val right = frame.groups.getValue(pair.b).frontier
                disclosure.lagBySource shouldBe left.keys.intersect(right.keys).associateWith { source ->
                    left.getValue(source) - right.getValue(source)
                }
            }

            observation.close()
        }
    }

    @Test
    fun `admission rejects before spawning any group and unchecked exempts only its view`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry, scheduler = controller.scheduler())
        val management = host.managementInlet.call
        val left = SetCell<Int>()
        val right = SetCell<Int>()
        val wanted = IntersectSetCell<Int>()
        listOf(left, right, wanted).forEach(management::spawn)
        management.connect(left.ref, "outlet", wanted.ref, "left")
        management.connect(right.ref, "outlet", wanted.ref, "right")

        val refsBefore = registry.localRefs().size
        val linksBefore = wanted.outlet.linking.links.size
        val error = assertThrows<AlignedAdmissionException> {
            host.observation { set("wanted", wanted.ref) }
        }
        error.verdict.shouldBeInstanceOf<AdmissionVerdict.Rejected.UngatedAncestor>().cell shouldBe wanted.ref
        registry.localRefs().size shouldBe refsBefore
        wanted.outlet.linking.links.size shouldBe linksBefore

        val admitted = assertDoesNotThrow {
            host.observation {
                unchecked("wanted")
                set("wanted", wanted.ref)
            }
        }
        admitted.groups shouldBe setOf("wanted")
        admitted.close()
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
