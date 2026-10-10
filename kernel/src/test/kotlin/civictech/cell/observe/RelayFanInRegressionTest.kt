package civictech.cell.observe

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.Aggregators
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.Windows
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkOptions
import civictech.cell.link.LinkRole
import civictech.cell.onEach
import civictech.cell.port.Admit
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.output
import civictech.cell.port.propagateFeedbackInlet
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

private interface RelaySetInletProxy {
    val inlet: Use<Propagate<SetDelta<String>>>
}

private interface RelaySetOpsInletProxy {
    val inlet: Use<SetOps<String>>
}

class RelayFanInRegressionTest {

    @Suppress("UNCHECKED_CAST")
    private val setApi = Propagate::class.java as Class<Propagate<SetDelta<String>>>

    private class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        fun send(delta: SetDelta<String>) = outlet.call.propagate(delta)
    }

    private class IntProgressSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Int>>>())

        fun send(timestamp: Timestamp, delta: SetDelta<Int>) {
            CurrentContext.with(MessageContext(timestamp, outlet.ref)) {
                outlet.call.propagate(delta)
            }
        }
    }

    private class WaterlineProgressSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<WaterlineDelta>>())
    }

    private data class Seen(val timestamp: Timestamp, val delta: SetDelta<String>)

    private class Observer(
        clazz: Class<Propagate<SetDelta<String>>>,
        val seen: MutableList<Seen>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))

        init {
            inlet.onEach { seen += Seen(CurrentContext.get()!!.timestamp, it) }
        }
    }

    private class EventProbe(
        clazz: Class<Propagate<SetDelta<String>>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())

        init {
            inlet.onEach { delta ->
                events += "D${CurrentContext.get()!!.timestamp.counter}:${delta.adds.keys}"
            }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                events += "P${(message as Progress).thru}"
            }
        }
    }

    private class WaveEventProbe(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        val dataWaves = mutableListOf<Timestamp>()
        val progressedWaves = mutableListOf<Timestamp>()

        init {
            inlet.onEach { dataWaves += CurrentContext.get()!!.timestamp }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                val progress = message as Progress
                progressedWaves += Timestamp(progress.sourceId, progress.thru)
            }
        }

        fun duplicateSettlements(): Set<Timestamp> = progressedWaves.toSet() intersect dataWaves.toSet()

        fun clear() {
            dataWaves.clear()
            progressedWaves.clear()
        }
    }

    private class MintThenForward(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.onEach { outlet.call.propagate(it) }
        }

        fun send(delta: SetDelta<String>) = outlet.call.propagate(delta)
    }

    private class Unlinker(
        private val unlink: () -> Unit,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.onEach { unlink() }
        }
    }

    private class FeedbackForwarder(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet by output<Propagate<SetDelta<String>>>()
        val feedbackInput by propagateFeedbackInlet<SetDelta<String>> { outlet.call.propagate(it) }

        fun send(delta: SetDelta<String>) = outlet.call.propagate(delta)
    }

    private class SetProgressProbe(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())
        val seen = mutableListOf<Progress>()

        init {
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                seen += message as Progress
            }
        }
    }

    private class MapProgressProbe(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<Int, Long>>>())
        val seen = mutableListOf<Progress>()

        init {
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                seen += message as Progress
            }
        }
    }

    /**
     * Five independently scheduled hosts expose the ordering that a one-host
     * FIFO cannot: one branch absorbs odd waves while another branch still has
     * data for them. Every send is drained before the next, so a failure is the
     * relay's per-in-edge completeness bug, not the separate multi-wave hazard
     * tracked by computenet-xas2g.
     */
    private fun runFanIn(seed: Long, waves: Int): List<String> {
        val controller = SimulationController(seed)
        val sourceHost = ManagedHost(scheduler = controller.scheduler())
        val filteredHost = ManagedHost(scheduler = controller.scheduler())
        val passHost = ManagedHost(scheduler = controller.scheduler())
        val leftHost = ManagedHost(scheduler = controller.scheduler())
        val joinHost = ManagedHost(scheduler = controller.scheduler())
        val source = Source()
        val filtered = FilterCell<String> { it.startsWith("x") }
        val pass = FilterCell<String> { true }
        val left = FilterCell<String> { true }
        val quorum = QuorumSetCell<String>(threshold = { 1 })
        val join = SemiJoinCell<String, String, String>(
            leftKey = { it },
            rightKey = { it },
            negated = false,
            emitOnFrontier = true,
        )
        val seen = mutableListOf<Seen>()
        val observer = Observer(setApi, seen)
        val probe = EventProbe(setApi)

        sourceHost.managementInlet.call.spawn(source)
        filteredHost.managementInlet.call.spawn(filtered)
        passHost.managementInlet.call.spawn(pass)
        leftHost.managementInlet.call.spawn(left)
        listOf(quorum, join, observer, probe).forEach(joinHost.managementInlet.call::spawn)

        source.outlet.subscribe(
            Use.fixed(filteredHost.lookup<RelaySetInletProxy>(filtered.ref)!!.inlet.call, PortRef.generate()),
        )
        source.outlet.subscribe(
            Use.fixed(passHost.lookup<RelaySetInletProxy>(pass.ref)!!.inlet.call, PortRef.generate()),
        )
        source.outlet.subscribe(
            Use.fixed(leftHost.lookup<RelaySetInletProxy>(left.ref)!!.inlet.call, PortRef.generate()),
        )
        @Suppress("UNCHECKED_CAST")
        filtered.outlet.linkTo(quorum.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        pass.outlet.linkTo(quorum.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        quorum.outlet.linkTo(join.right as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        left.outlet.linkTo(join.left as LinkFrom<Propagate<SetDelta<String>>>)
        join.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        @Suppress("UNCHECKED_CAST")
        join.outlet.linkTo(probe.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        controller.runToIdle()

        val tagSource = UUID.randomUUID()
        val elements = mutableMapOf<Long, String>()
        for (counter in 1..waves) {
            val element = if (counter % 2 == 0) "x$counter" else "y$counter"
            elements[counter.toLong()] = element
            source.send(
                SetDelta(
                    adds = mapOf(element to setOf(Timestamp(tagSource, counter.toLong()))),
                ),
            )
            controller.runToIdle()
        }

        val problems = mutableListOf<String>()
        if (join.bufferedWaves != 0) problems += "seed $seed buffered=${join.bufferedWaves}"
        val emissions = seen.flatMap { result ->
            result.delta.adds.keys.map { element -> element to result.timestamp.counter }
        }
        elements.forEach { (counter, element) ->
            val emittedAt = emissions.filter { it.first == element }.map { it.second }
            if (emittedAt != listOf(counter)) {
                problems += "seed $seed element $element emitted at $emittedAt (expected [$counter])"
            }
        }
        val events = synchronized(probe.events) { probe.events.toList() }
        for (counter in 1..waves) {
            val hasProgress = "P$counter" in events
            val hasData = events.any { it.startsWith("D$counter:") }
            if (hasProgress && hasData) {
                problems += "seed $seed wave $counter emitted both Progress and data: $events"
            }
        }
        return problems
    }

    @Test
    fun `a fan-in hop does not relay one in-edge Progress as whole-hop settlement`() {
        val problems = (0L until 300L).flatMap { runFanIn(it, waves = 8) }
        println("RELAY_FAN_IN_PROBE failures=${problems.size} first=${problems.take(4)}")
        withClue(problems.take(4).joinToString("\n")) {
            problems.size shouldBe 0
        }
    }

    @Test
    fun `a quorum-minted wave reaching a fan-in by two paths is not settled before its second path`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val first = Source()
        val closing = Source()
        val publishing = QuorumSetCell<String>(threshold = { n -> n })
        val relay = QuorumSetCell<String>(threshold = { 1 })
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        listOf(first, closing, publishing, relay, fanIn, probe).forEach(management::spawn)
        management.connect(first.ref, "outlet", publishing.ref, "inlet")
        management.connect(closing.ref, "outlet", publishing.ref, "inlet")
        management.connect(publishing.ref, "outlet", fanIn.ref, "inlet")
        management.connect(publishing.ref, "outlet", relay.ref, "inlet")
        management.connect(relay.ref, "outlet", fanIn.ref, "inlet")
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")
        controller.runToIdle()

        first.send(SetDelta(adds = mapOf("e" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()
        // Closing the other source lowers publishing's threshold. It therefore
        // emits a fresh wave under its own source id, down both paths.
        closing.outlet.linking.links.single().unlink()
        controller.runToIdle()

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.dataWaves.shouldNotBeEmpty()
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `a mint-then-forward outlet is not treated as source-disjoint before its first forward`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = Source()
        val mixed = MintThenForward()
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        listOf(source, mixed, fanIn, probe).forEach(management::spawn)
        management.connect(source.ref, "outlet", fanIn.ref, "inlet")
        management.connect(source.ref, "outlet", mixed.ref, "inlet")
        management.connect(mixed.ref, "outlet", fanIn.ref, "inlet")
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")
        controller.runToIdle()

        // A spontaneous emission must not make a structurally reactive outlet
        // look like a root before its first forwarded wave.
        mixed.send(SetDelta(adds = mapOf("m" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()
        probe.clear()
        source.send(SetDelta(adds = mapOf("e" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.dataWaves.shouldNotBeEmpty()
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `externally-fed inlet-bearing sources settle independently through a quorum fan-in`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val sources = List(3) { SetCell<String>() }
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        (sources + listOf(fanIn, probe)).forEach(management::spawn)
        sources.forEach { source ->
            management.connect(source.ref, "outlet", fanIn.ref, "inlet")
        }
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")
        controller.runToIdle()

        sources.forEach { source ->
            // Slotfinder's participant sets are true roots even though callers
            // enter through their registered inlet: no upstream link carries
            // these waves, so each source settles independently at the fan-in.
            source.inlet.call.add("shared")
            controller.runToIdle()
        }

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.progressedWaves.size shouldBe 2
            probe.dataWaves.size shouldBe 1
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `externally host-fed policy inlets settle independently through a quorum fan-in`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val sources = List(3) { SetCell<String>() }
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        sources.forEach { source -> source.inlet.install(Admit(admits = { true })) }
        (sources + listOf(fanIn, probe)).forEach(management::spawn)
        sources.forEach { source ->
            management.connect(source.ref, "outlet", fanIn.ref, "inlet")
        }
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")
        controller.runToIdle()

        sources.forEach { source ->
            host.lookup<RelaySetOpsInletProxy>(source.ref)!!.inlet.call.add("shared")
            controller.runToIdle()
        }

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.progressedWaves.size shouldBe 2
            probe.dataWaves.size shouldBe 1
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `a Use-fixed-fed forwarder is not treated as a structural root`() {
        val host = ManagedHost()
        val source = Source()
        val forwarder = MintThenForward()
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        listOf(source, forwarder, fanIn, probe).forEach(management::spawn)
        management.connect(source.ref, "outlet", fanIn.ref, "inlet")
        source.outlet.subscribe(Use.fixed(forwarder.inlet.call, PortRef.generate()))
        management.connect(forwarder.ref, "outlet", fanIn.ref, "inlet")
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")

        forwarder.send(SetDelta(adds = mapOf("m" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        probe.clear()
        source.send(SetDelta(adds = mapOf("e" to setOf(Timestamp(UUID.randomUUID(), 1L)))))

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.dataWaves.size shouldBe 1
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `a delegating Use-fixed-fed forwarder is not treated as a structural root`() {
        val host = ManagedHost()
        val source = Source()
        val forwarder = MintThenForward()
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        listOf(source, forwarder, fanIn, probe).forEach(management::spawn)
        management.connect(source.ref, "outlet", fanIn.ref, "inlet")
        source.outlet.subscribe(
            Use.fixed(
                Propagate { delta -> forwarder.inlet.call.propagate(delta) },
                PortRef.generate(),
            ),
        )
        management.connect(forwarder.ref, "outlet", fanIn.ref, "inlet")
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")

        forwarder.send(SetDelta(adds = mapOf("m" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        probe.clear()
        source.send(SetDelta(adds = mapOf("e" to setOf(Timestamp(UUID.randomUUID(), 1L)))))

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.dataWaves.size shouldBe 1
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `unlink inside a wave handler does not settle and emit the caller wave`() {
        val host = ManagedHost()
        val first = Source()
        val closing = Source()
        val trigger = Source()
        val publishing = QuorumSetCell<String>(threshold = { n -> n })
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val unlinker = Unlinker(unlink = { closing.outlet.linking.links.single().unlink() })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        listOf(first, closing, trigger, publishing, fanIn, unlinker, probe).forEach(management::spawn)
        management.connect(first.ref, "outlet", publishing.ref, "inlet")
        management.connect(closing.ref, "outlet", publishing.ref, "inlet")
        // The direct arm must receive the trigger first: it absorb-acks the
        // wave before the sibling handler closes publishing's second source.
        management.connect(trigger.ref, "outlet", fanIn.ref, "inlet")
        management.connect(trigger.ref, "outlet", unlinker.ref, "inlet")
        management.connect(publishing.ref, "outlet", fanIn.ref, "inlet")
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")

        closing.send(SetDelta(adds = mapOf("z" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        first.send(SetDelta(adds = mapOf("e" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        probe.clear()

        // Closing the "z" lane lowers publishing's threshold, so its retained
        // "e" becomes data. EdgeClose is metadata-plane traffic: that emission
        // must mint publishing's wave, not inherit this trigger's wave.
        trigger.send(SetDelta(adds = mapOf("e" to setOf(Timestamp(UUID.randomUUID(), 1L)))))

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.dataWaves.size shouldBe 1
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `an outlet whose cell is fed only by an Observe link is not a structural root`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = Source()
        val pass = MintThenForward()
        val observer = MintThenForward()
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        listOf(source, pass, observer, fanIn, probe).forEach(management::spawn)
        management.connect(source.ref, "outlet", fanIn.ref, "inlet")
        management.connect(source.ref, "outlet", pass.ref, "inlet")
        management.connect(pass.ref, "outlet", observer.ref, "inlet", LinkOptions(role = LinkRole.Observe))
        management.connect(observer.ref, "outlet", fanIn.ref, "inlet")
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")
        controller.runToIdle()

        // The observer has minted once and has no Consume input, yet its tap
        // forwards the source's wave: an Observe input still makes it reactive.
        observer.send(SetDelta(adds = mapOf("m" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()
        probe.clear()
        source.send(SetDelta(adds = mapOf("e" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.dataWaves.size shouldBe 1
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `a publishing relay hop fed through an Observe link publishes that link's source`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = Source()
        val pass = MintThenForward()
        val publishing = QuorumSetCell<String>(threshold = { 1 })
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        listOf(source, pass, publishing, fanIn, probe).forEach(management::spawn)
        management.connect(source.ref, "outlet", fanIn.ref, "inlet")
        management.connect(source.ref, "outlet", pass.ref, "inlet")
        management.connect(pass.ref, "outlet", publishing.ref, "inlet", LinkOptions(role = LinkRole.Observe))
        management.connect(publishing.ref, "outlet", fanIn.ref, "inlet")
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")
        controller.runToIdle()

        // The publishing hop's only input is an Observe link, yet it re-emits
        // the source's wave: its published provenance must include that source.
        source.send(SetDelta(adds = mapOf("e" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.dataWaves.size shouldBe 1
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `an outlet whose cell is fed only by a feedback inlet is not a structural root`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val source = Source()
        val head = FeedbackForwarder()
        val relay = MintThenForward()
        val fanIn = QuorumSetCell<String>(threshold = { n -> n })
        val probe = WaveEventProbe()
        val management = host.managementInlet.call
        listOf(source, head, relay, fanIn, probe).forEach(management::spawn)
        management.connect(source.ref, "outlet", head.ref, "feedbackInput")
        management.connect(head.ref, "outlet", relay.ref, "inlet")
        management.connect(relay.ref, "outlet", fanIn.ref, "inlet")
        management.connect(head.ref, "outlet", fanIn.ref, "inlet")
        management.connect(fanIn.ref, "outlet", probe.ref, "inlet")

        // A feedback lap runs under the head's own epoch, which is never in its
        // outlet's minted set; its only input is a feedback (not FanInlet) port.
        head.send(SetDelta(adds = mapOf("m" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()
        probe.clear()
        source.send(SetDelta(adds = mapOf("e" to setOf(Timestamp(UUID.randomUUID(), 1L)))))
        controller.runToIdle()

        withClue("progress=${probe.progressedWaves}, data=${probe.dataWaves}") {
            probe.dataWaves.size shouldBe 1
            probe.duplicateSettlements() shouldBe emptySet()
        }
    }

    @Test
    fun `relay counts links added after construction and stops counting a closed link`() {
        val host = ManagedHost()
        val first = IntProgressSource()
        val second = IntProgressSource()
        val filter = FilterCell<Int> { true }
        val probe = SetProgressProbe()
        val management = host.managementInlet.call
        listOf(first, second, filter, probe).forEach(management::spawn)
        management.connect(first.ref, "outlet", filter.ref, "inlet")
        management.connect(filter.ref, "outlet", probe.ref, "inlet")

        val beforeFanIn = Progress(UUID.randomUUID(), 1L)
        Protocols.sendDownstream(first.outlet.linking.links.single(), Protocols.Progress, beforeFanIn)
        probe.seen shouldBe listOf(beforeFanIn)

        management.connect(second.ref, "outlet", filter.ref, "inlet")
        val duringFanIn = Progress(beforeFanIn.sourceId, 2L)
        Protocols.sendDownstream(first.outlet.linking.links.single(), Protocols.Progress, duringFanIn)
        probe.seen shouldBe listOf(beforeFanIn)

        second.outlet.linking.links.single().unlink()
        val afterClose = Progress(beforeFanIn.sourceId, 3L)
        Protocols.sendDownstream(first.outlet.linking.links.single(), Protocols.Progress, afterClose)
        probe.seen shouldBe listOf(beforeFanIn, afterClose)
    }

    @Test
    fun `a fan-in hop relays once after every open edge settles and close releases a waiting wave`() {
        val host = ManagedHost()
        val first = IntProgressSource()
        val second = IntProgressSource()
        val quorum = QuorumSetCell<Int> { 1 }
        val probe = SetProgressProbe()
        val management = host.managementInlet.call
        listOf(first, second, quorum, probe).forEach(management::spawn)
        management.connect(first.ref, "outlet", quorum.ref, "inlet")
        management.connect(second.ref, "outlet", quorum.ref, "inlet")
        management.connect(quorum.ref, "outlet", probe.ref, "inlet")

        val sourceId = UUID.randomUUID()
        val bothSettle = Progress(sourceId, 1L)
        Protocols.sendDownstream(first.outlet.linking.links.single(), Protocols.Progress, bothSettle)
        probe.seen shouldBe emptyList()
        Protocols.sendDownstream(second.outlet.linking.links.single(), Protocols.Progress, bothSettle)
        probe.seen shouldBe listOf(bothSettle)

        // Duplicate settlement cannot relay the same wave twice.
        Protocols.sendDownstream(first.outlet.linking.links.single(), Protocols.Progress, bothSettle)
        Protocols.sendDownstream(second.outlet.linking.links.single(), Protocols.Progress, bothSettle)
        probe.seen shouldBe listOf(bothSettle)

        // A close shrinks the live completeness condition immediately.
        val releasedByClose = Progress(sourceId, 2L)
        Protocols.sendDownstream(first.outlet.linking.links.single(), Protocols.Progress, releasedByClose)
        probe.seen shouldBe listOf(bothSettle)
        second.outlet.linking.links.single().unlink()
        probe.seen shouldBe listOf(bothSettle, releasedByClose)

        // A newly opened edge participates in the next arriving wave.
        management.connect(second.ref, "outlet", quorum.ref, "inlet")
        val afterReopen = Progress(sourceId, 3L)
        Protocols.sendDownstream(first.outlet.linking.links.single(), Protocols.Progress, afterReopen)
        probe.seen shouldBe listOf(bothSettle, releasedByClose)
        Protocols.sendDownstream(second.outlet.linking.links.single(), Protocols.Progress, afterReopen)
        probe.seen shouldBe listOf(bothSettle, releasedByClose, afterReopen)
    }

    @Test
    fun `a fan-in data arrival settles its edge but an absorbed wave waits for every sibling`() {
        val host = ManagedHost()
        val first = IntProgressSource()
        val second = IntProgressSource()
        val quorum = QuorumSetCell<Int> { 2 }
        val probe = SetProgressProbe()
        val management = host.managementInlet.call
        listOf(first, second, quorum, probe).forEach(management::spawn)
        management.connect(first.ref, "outlet", quorum.ref, "inlet")
        management.connect(second.ref, "outlet", quorum.ref, "inlet")
        management.connect(quorum.ref, "outlet", probe.ref, "inlet")

        val timestamp = Timestamp(UUID.randomUUID(), 1L)
        first.send(
            timestamp,
            SetDelta(adds = mapOf(7 to setOf(Timestamp(UUID.randomUUID(), 1L)))),
        )
        probe.seen shouldBe emptyList()

        val siblingSettled = Progress(timestamp.sourceId, timestamp.counter)
        Protocols.sendDownstream(
            second.outlet.linking.links.single(),
            Protocols.Progress,
            siblingSettled,
        )
        probe.seen shouldBe listOf(siblingSettled)
    }

    @Test
    fun `GroupBy waterline waits for every sibling and relays when a sibling closes`() {
        val host = ManagedHost()
        val data = IntProgressSource()
        val waterline = WaterlineProgressSource()
        val grouped = GroupByCell(
            keyFn = { value: Int -> value },
            aggregator = Aggregators.count<Int>(),
            lateness = Windows.Lateness({ value: Int -> value.toLong() }, 0),
            keyTime = { key: Int -> key.toLong() + 1 },
        )
        val probe = MapProgressProbe()
        val management = host.managementInlet.call
        listOf(data, waterline, grouped, probe).forEach(management::spawn)
        management.connect(waterline.ref, "outlet", grouped.ref, "waterline")
        management.connect(grouped.ref, "outlet", probe.ref, "inlet")

        val waterlineOnly = Progress(UUID.randomUUID(), 1L)
        Protocols.sendDownstream(
            waterline.outlet.linking.links.single(),
            Protocols.Progress,
            waterlineOnly,
        )
        probe.seen shouldBe listOf(waterlineOnly)

        management.connect(data.ref, "outlet", grouped.ref, "inlet")
        val withDataSibling = Progress(waterlineOnly.sourceId, 2L)
        Protocols.sendDownstream(
            waterline.outlet.linking.links.single(),
            Protocols.Progress,
            withDataSibling,
        )
        probe.seen shouldBe listOf(waterlineOnly)

        data.outlet.linking.links.single().unlink()
        probe.seen shouldBe listOf(waterlineOnly, withDataSibling)
        val afterDataClose = Progress(waterlineOnly.sourceId, 3L)
        Protocols.sendDownstream(
            waterline.outlet.linking.links.single(),
            Protocols.Progress,
            afterDataClose,
        )
        probe.seen shouldBe listOf(waterlineOnly, withDataSibling, afterDataClose)
    }

    @Test
    fun `GroupBy data inlet waits for every sibling and relays when a sibling closes`() {
        val host = ManagedHost()
        val data = IntProgressSource()
        val waterline = WaterlineProgressSource()
        val grouped = GroupByCell(
            keyFn = { value: Int -> value },
            aggregator = Aggregators.count<Int>(),
            lateness = Windows.Lateness({ value: Int -> value.toLong() }, 0),
            keyTime = { key: Int -> key.toLong() + 1 },
        )
        val probe = MapProgressProbe()
        val management = host.managementInlet.call
        listOf(data, waterline, grouped, probe).forEach(management::spawn)
        management.connect(data.ref, "outlet", grouped.ref, "inlet")
        management.connect(grouped.ref, "outlet", probe.ref, "inlet")

        val dataOnly = Progress(UUID.randomUUID(), 1L)
        Protocols.sendDownstream(data.outlet.linking.links.single(), Protocols.Progress, dataOnly)
        probe.seen shouldBe listOf(dataOnly)

        management.connect(waterline.ref, "outlet", grouped.ref, "waterline")
        val withWaterlineSibling = Progress(dataOnly.sourceId, 2L)
        Protocols.sendDownstream(data.outlet.linking.links.single(), Protocols.Progress, withWaterlineSibling)
        probe.seen shouldBe listOf(dataOnly)

        waterline.outlet.linking.links.single().unlink()
        probe.seen shouldBe listOf(dataOnly, withWaterlineSibling)
        val afterWaterlineClose = Progress(dataOnly.sourceId, 3L)
        Protocols.sendDownstream(data.outlet.linking.links.single(), Protocols.Progress, afterWaterlineClose)
        probe.seen shouldBe listOf(dataOnly, withWaterlineSibling, afterWaterlineClose)
    }
}
