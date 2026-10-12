package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell.WaveMode
import civictech.cell.consistency.GlitchViolation
import civictech.cell.control.Progress
import civictech.cell.control.StallNotice
import civictech.cell.control.StallReason
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.inlet
import civictech.cell.link.Link
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.testkit.awaitUntil
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The aligned sink under stalled edges (KE2 §5.3, spec 20/22 §Completeness over
 * silent or stuck edges, `[22-OBS-02]`): WAIT holds, DEGRADE shrinks and
 * discloses, a terminal stall re-scopes in every mode — and every published
 * [AlignedComposite] names the edges it was released without.
 *
 * **The phantom-edge shape.** Most tests observe two *independent* `SetCell`
 * roots, `a` and `b`. `b`'s edge is an expected sibling for every one of `a`'s
 * waves (the static-link-set frontier, G-13) and never settles them — which is,
 * from the fold's point of view, exactly a stalled edge. Notices are injected
 * on `b`'s link the way `GlitchFreeStallTest` injects them into a frontier.
 *
 * Non-vacuity is inherent: each test asserts a dropped set, a violation count
 * or a publication that the WAIT-only sink before this change could not
 * produce (it had no Suspension handler, so every stalled wave stayed held and
 * `droppedEdges` did not exist).
 */
class AlignedObserveStallTest {

    private interface IntSetInlet {
        val inlet: Use<SetOps<Int>>
    }

    // ---- the phantom-edge fixture ----------------------------------------------

    private class Phantom(mode: WaveMode, seedA: Set<Int> = emptySet()) {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val a = SetCell<Int>()
        val b = SetCell<Int>()
        val violations = Collections.synchronizedList(mutableListOf<GlitchViolation>())
        val sink: AlignedCompositeCell

        init {
            val mgmt = host.managementInlet.call
            mgmt.spawn(a)
            mgmt.spawn(b)
            if (seedA.isNotEmpty()) {
                seedA.forEach { opsA.add(it) }
                controller.runToIdle()
            }
            sink = host.observeAligned(mode = mode, onViolation = { violations += it }) {
                set("a", a.ref)
                set("b", b.ref)
            }
            controller.runToIdle()
        }

        val opsA: SetOps<Int> get() = host.lookup<IntSetInlet>(a.ref)!!.inlet.call
        val linkB: Link get() = sink.inlets.getValue("b").linking.links.single()
        val droppedB: DroppedEdge get() = DroppedEdge("b", linkB.id)

        fun notifyB(notice: StallNotice) =
            ProtocolSupport.of(sink.inlets.getValue("b")).deliver(Protocols.Suspension, linkB, notice)

        fun addA(n: Int) {
            opsA.add(n)
            controller.runToIdle()
        }
    }

    // ---- [KE2-11 as corrected] DEGRADE shrink + Resume restore ----------------

    @Test
    fun `DEGRADE - a recoverable stall shrinks the frontier, discloses the edge, and Resume restores it`() {
        val f = Phantom(WaveMode.DEGRADE)
        f.addA(1)
        f.sink.bufferedWaves shouldBe 1 // held on b, as in WAIT, until the stall

        val firstWave = f.sink.heldWaves().keys.single()
        f.notifyB(StallNotice.Stall(StallReason.SUSPENDED))

        f.sink.bufferedWaves shouldBe 0
        f.sink.current() shouldBe mapOf("a" to setOf(1), "b" to emptySet<Int>())
        f.sink.composite().droppedEdges shouldBe setOf(f.droppedB)
        f.sink.composite().alignedFrom shouldBe mapOf(firstWave.sourceId to firstWave.counter)
        f.sink.violations shouldBe 0L
        f.violations.shouldBeEmpty()

        // Resume: b rejoins the expected set, so a's next wave is held again.
        f.notifyB(StallNotice.Resume)
        f.addA(2)
        f.sink.bufferedWaves shouldBe 1
        val held = f.sink.heldWaves()
        held.values.single() shouldBe setOf(f.droppedB)
        f.sink.current() shouldBe mapOf("a" to setOf(1), "b" to emptySet<Int>())

        // b acks the wave (its own settlement signal): the wave releases, and
        // b — now caught up with the frontier — is no longer disclosed.
        val wave = held.keys.single()
        ProtocolSupport.of(f.sink.inlets.getValue("b")).deliver(Protocols.Progress, f.linkB, Progress(wave.sourceId, wave.counter))
        f.sink.bufferedWaves shouldBe 0
        f.sink.current() shouldBe mapOf("a" to setOf(1, 2), "b" to emptySet<Int>())
        f.sink.composite().droppedEdges shouldBe emptySet()
        val composite = f.sink.composite()
        composite.alignedFrom.keys shouldBe composite.frontier.keys
        composite.alignedFrom.forEach { (source, firstWave) ->
            (firstWave <= composite.frontier.getValue(source)) shouldBe true
        }
        f.sink.close()
    }

    @Test
    fun `DEGRADE - a wave released without an edge completes its handle VisibleDegraded`() {
        val f = Phantom(WaveMode.DEGRADE)
        f.addA(1)
        val wave = f.sink.heldWaves().keys.single()
        val handle = f.sink.visibilityOf(wave)
        handle.isDone shouldBe false

        f.notifyB(StallNotice.Stall(StallReason.RESTARTING))

        handle.get(5, TimeUnit.SECONDS) shouldBe VisibleDegraded(wave, setOf(f.droppedB))
        f.sink.close()
    }

    // ---- [KE2-13] WAIT holds and names the stalled edge ----------------------

    @Test
    fun `WAIT - a recoverable stall holds the wave and heldWaves names the stalled edge`() {
        val f = Phantom(WaveMode.WAIT)
        f.addA(1)
        val before = f.sink.composite()

        f.notifyB(StallNotice.Stall(StallReason.SUSPENDED))
        f.addA(2)

        f.sink.bufferedWaves shouldBe 2
        val held = f.sink.heldWaves()
        held.size shouldBe 2
        held.values.forEach { it shouldBe setOf(f.droppedB) }
        f.sink.current() shouldBe mapOf("a" to emptySet<Int>(), "b" to emptySet<Int>())
        f.sink.composite() shouldBeSameInstanceAs before // nothing published
        f.sink.composite().droppedEdges shouldBe emptySet()
        f.sink.violations shouldBe 0L
        f.sink.close()
    }

    // ---- RE-SCOPE: a terminal stall, every mode ----------------------------------

    @Test
    fun `a terminal stall with a timestamp re-scopes that wave only, in WAIT and in DEGRADE`() {
        for (mode in WaveMode.entries) {
            val f = Phantom(mode)
            f.addA(1)
            val wave = f.sink.heldWaves().keys.single()

            f.notifyB(StallNotice.Stall(StallReason.DEAD_LETTERED, wave))

            f.sink.bufferedWaves shouldBe 0
            f.sink.current() shouldBe mapOf("a" to setOf(1), "b" to emptySet<Int>())
            f.sink.composite().droppedEdges shouldBe setOf(f.droppedB)
            f.sink.composite().alignedFrom shouldBe mapOf(wave.sourceId to wave.counter)
            f.sink.violations shouldBe 1L
            f.violations.single().shouldBeInstanceOf<GlitchViolation>()

            // the edge is still open: the next wave from a waits on b again
            f.addA(2)
            f.sink.bufferedWaves shouldBe 1
            f.sink.heldWaves().values.single() shouldBe setOf(f.droppedB)
            f.sink.current() shouldBe mapOf("a" to setOf(1), "b" to emptySet<Int>())
            f.sink.close()
        }
    }

    @Test
    fun `a terminal stall without a timestamp closes the edge, releasing every held wave with it dropped`() {
        val f = Phantom(WaveMode.WAIT)
        val seen = Collections.synchronizedList(mutableListOf<AlignedComposite>())
        f.sink.onComposite { seen += it }
        f.addA(1)
        f.addA(2)
        f.sink.bufferedWaves shouldBe 2

        f.notifyB(StallNotice.Stall(StallReason.DEAD_LETTERED))

        f.sink.bufferedWaves shouldBe 0
        f.sink.violations shouldBe 1L
        f.violations.size shouldBe 1
        awaitUntil("catch-up plus the two released waves") { seen.size >= 3 }
        seen[1].views["a"] shouldBe setOf(1)
        seen[2].views["a"] shouldBe setOf(1, 2)
        seen.drop(1).forEach { it.droppedEdges shouldBe setOf(f.droppedB) }

        // a closed edge is no longer expected: a later wave neither waits on b
        // nor discloses it.
        f.addA(3)
        f.sink.bufferedWaves shouldBe 0
        f.sink.current()["a"] shouldBe setOf(1, 2, 3)
        f.sink.composite().droppedEdges shouldBe emptySet()
        f.sink.close()
    }

    // ---- [KE2-15] an edge closing while waves are held on it -------------------

    @Test
    fun `an EdgeClose while a wave is held on the edge releases it with the edge dropped`() {
        val f = Phantom(WaveMode.WAIT)
        f.addA(1)
        val dropped = f.droppedB
        f.sink.bufferedWaves shouldBe 1

        f.linkB.unlink()

        f.sink.bufferedWaves shouldBe 0
        f.sink.current() shouldBe mapOf("a" to setOf(1), "b" to emptySet<Int>())
        f.sink.composite().droppedEdges shouldBe setOf(dropped)
        f.sink.violations shouldBe 0L // a close is not a violation
        f.sink.close()
    }

    // ---- [KE2-12] immutability and the torn-pair rule --------------------------

    @Test
    fun `a composite published before a shrink never changes, and no listener sees a torn pair`() {
        // a's catch-up seeds {10} before the sink attaches: an unwaved install,
        // i.e. a composite genuinely PUBLISHED before the shrink.
        val f = Phantom(WaveMode.DEGRADE, seedA = setOf(10))
        val composites = Collections.synchronizedList(mutableListOf<AlignedComposite>())
        val views = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        f.sink.onComposite { composites += it }
        f.sink.onChange { views += it }

        val before = f.sink.composite()
        val beforeCopy = before.copy(views = LinkedHashMap(before.views), droppedEdges = before.droppedEdges.toSet())
        before.views["a"] shouldBe setOf(10)
        before.droppedEdges shouldBe emptySet()
        before.alignedFrom shouldBe emptyMap()

        f.addA(1)
        f.notifyB(StallNotice.Stall(StallReason.SUSPENDED))
        f.addA(2) // released at once: b is still suspended

        val after = f.sink.composite()
        after.droppedEdges shouldBe setOf(f.droppedB)
        before shouldBe beforeCopy // the captured instance was not retroactively marked
        before.droppedEdges shouldBe emptySet()

        awaitUntil("catch-up plus two degraded waves, on both listeners") { composites.size >= 3 && views.size >= 3 }
        // onChange and onComposite fire from one submission per publication
        views.toList() shouldBe composites.map { it.views }
        // a current()-independent recomputation of the pair: in this graph a
        // composite carries one of a's waves iff b was dropped from it (b never
        // settles a's waves) — the catch-up composite has a == {10} and no drop.
        composites.forEach { c ->
            val waved = (c.views["a"] as Set<*>) != setOf(10)
            c.droppedEdges shouldBe (if (waved) setOf(f.droppedB) else emptySet())
            if (waved) {
                c.alignedFrom.keys shouldBe c.frontier.keys
                c.alignedFrom.forEach { (source, firstWave) ->
                    (firstWave <= c.frontier.getValue(source)) shouldBe true
                }
            } else {
                c.alignedFrom shouldBe emptyMap()
            }
        }
        f.sink.close()
    }

    // ---- current / onChange / get<T> unchanged ----------------------------------

    @Test
    fun `current, onChange and get keep their types and read the same swap as composite`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetCell<Int>()
        host.managementInlet.call.spawn(source)
        val sink = host.observeAligned {
            set("left", source.ref)
            set("right", source.ref)
        }
        sink.mode shouldBe WaveMode.WAIT // the default
        val seen = Collections.synchronizedList(mutableListOf<Map<String, Any?>>())
        sink.onChange { seen += it }

        val ops = host.lookup<IntSetInlet>(source.ref)!!.inlet.call
        ops.add(1); ops.add(2)
        controller.runToIdle()

        val current: Map<String, Any?> = sink.current()
        current shouldBe mapOf("left" to setOf(1, 2), "right" to setOf(1, 2))
        sink.get<Set<Int>>("left") shouldBe setOf(1, 2)
        sink.composite().views shouldBeSameInstanceAs sink.current()
        sink.composite().droppedEdges shouldBe emptySet()
        val sourceId = sink.composite().frontier.keys.single()
        sink.composite().alignedFrom shouldBe mapOf(sourceId to 1L)
        sink.composite().frontier shouldBe mapOf(sourceId to 2L)
        awaitUntil("catch-up plus two waves") { seen.size >= 3 }
        seen.last() shouldBe current
        sink.bufferedWaves shouldBe 0
        sink.heldWaves() shouldBe emptyMap()
        sink.close()
    }

    // ---- [KE2-14] ordering across a DEGRADE stall and resume, seeded ----------

    private class Graph(seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = SetCell<Int>()
        val filter = FilterCell<Int> { it % 2 == 0 }

        init {
            val mgmt = host.managementInlet.call
            mgmt.spawn(source)
            mgmt.spawn(filter)
            mgmt.connect(source.ref, "outlet", filter.ref, "inlet")
        }

        val ops: SetOps<Int> get() = host.lookup<IntSetInlet>(source.ref)!!.inlet.call
    }

    /** `AlignedObserveTest`'s reroute: the handshaken link stays, delivery is queued. */
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

    private fun driveSome(graph: Graph, range: IntRange, rnd: Random) {
        for (n in range) {
            graph.ops.add(n)
            repeat(rnd.nextInt(4)) { graph.controller.step() }
        }
    }

    private fun evens(set: Set<*>) = set.filter { (it as Int) % 2 == 0 }.toSet()

    /**
     * `[KE2-14]`: `AlignedObserveTest`'s graph (`items` straight off a `SetCell`,
     * `filtered` through a `FilterCell`; `items` rerouted through the host queue
     * so it lags), in DEGRADE. Mid-drive a recoverable `Stall` lands on one
     * arm's link, more waves are driven, `Resume` lands, and the drive finishes.
     *
     * **Which arm stalls.** Even seeds stall `items` (the queued arm), odd seeds
     * `filtered` (the fused arm, as the breakdown prescribed), 50 of each, so
     * the degrading `items` half alone meets `[KE2-14]`'s 50-schedule bar.
     * Only the `items` stall can actually release a wave without its arm: the fused `filtered`
     * arm has always settled a wave (real delta or synchronous absorb-ack)
     * before the queued `items` delta arrives, so a `filtered` stall shrinks the
     * frontier without ever dropping anything — ordering is still exercised,
     * degradation is not. The aggregate `degraded > 0` assertion is what keeps
     * the `items` half non-vacuous.
     *
     * **The discriminator.** A composite carrying the stalled arm in
     * `droppedEdges` is exempt from `filtered == items.filter(even)` — that is
     * the disclosed degradation, including the straggler installs that bring a
     * lagging `items` view back up to the frontier. Every other composite is
     * held to it, and to `items` being a prefix `1..k` of the writes.
     */
    @Test
    fun `KE2-14 - stalled then resumed waves publish in per-source order, none lost, over 100 seeds`() {
        val waves = 30
        var degraded = 0
        for (seed in 0L until 100L) {
            val stalled = if (seed % 2 == 0L) "items" else "filtered"
            val graph = Graph(seed)
            val sink = graph.host.observeAligned(mode = WaveMode.DEGRADE) {
                set("items", graph.source.ref)
                set("filtered", graph.filter.ref)
            }
            rerouteThroughHostQueue(graph.host, graph.source.outlet, sink.inlets.getValue("items").ref, sink.ref, "items")
            val composites = Collections.synchronizedList(mutableListOf<AlignedComposite>())
            sink.onComposite { composites += it }

            val inlet = sink.inlets.getValue(stalled)
            val link = inlet.linking.links.single()
            val rnd = Random(seed)
            driveSome(graph, 1..10, rnd)
            ProtocolSupport.of(inlet).deliver(Protocols.Suspension, link, StallNotice.Stall(StallReason.SUSPENDED))
            driveSome(graph, 11..20, rnd)
            ProtocolSupport.of(inlet).deliver(Protocols.Suspension, link, StallNotice.Resume)
            driveSome(graph, 21..waves, rnd)
            graph.controller.runToIdle()

            val settled = mapOf<String, Any?>("items" to (1..waves).toSet(), "filtered" to evens((1..waves).toSet()))
            sink.current() shouldBe settled // nothing lost
            sink.bufferedWaves shouldBe 0
            sink.composite().droppedEdges shouldBe emptySet() // caught up: nothing left to disclose
            awaitUntil("the final composite reaches the listener (seed $seed)") { composites.lastOrNull()?.views == settled }

            val dropped = DroppedEdge(stalled, link.id)
            val recorded = composites.toList()
            recorded.zipWithNext().forEach { (prev, next) ->
                // per-source counter order over the published frontier
                next.frontier.forEach { (source, counter) ->
                    counter shouldBe maxOf(counter, prev.frontier[source] ?: Long.MIN_VALUE)
                }
                prev.alignedFrom.all { (source, firstWave) -> next.alignedFrom[source] == firstWave } shouldBe true
                // no wave un-applied or re-ordered: items only grows
                (next.views["items"] as Set<*>).containsAll(prev.views["items"] as Set<*>) shouldBe true
            }
            recorded.forEach { c ->
                if (c.frontier.isEmpty()) {
                    c.alignedFrom shouldBe emptyMap()
                } else {
                    c.alignedFrom.keys shouldBe c.frontier.keys
                    c.alignedFrom.forEach { (source, firstWave) ->
                        (firstWave <= c.frontier.getValue(source)) shouldBe true
                    }
                }
                c.droppedEdges.forEach { it shouldBe dropped }
                val items = c.views["items"] as Set<*>
                items shouldBe (1..items.size).toSet() // a prefix of the writes, never a gap
                if (c.droppedEdges.isEmpty()) {
                    c.views["filtered"] shouldBe evens(items)
                } else {
                    degraded++
                }
            }
            sink.close()
        }
        (degraded > 0) shouldBe true
    }
}
