package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.observe.AdmissionVerdict
import civictech.cell.observe.AlignedAdmissionException
import civictech.cell.observe.observeAligned
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Random
import java.util.UUID

/** Regression for one source reaching a frontier through two lanes of one fan-in operator. */
class QuorumFanInSettlementTest {
    interface SetArmProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    @Suppress("UNCHECKED_CAST")
    private val setApi = Propagate::class.java as Class<Propagate<SetDelta<String>>>

    private class SetSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        fun send(delta: SetDelta<String>) = outlet.call.propagate(delta)
    }

    private sealed interface Event {
        val wave: Timestamp

        data class Lane(val name: String, override val wave: Timestamp) : Event
        data class Ack(override val wave: Timestamp) : Event
    }

    private class SetArm(
        clazz: Class<Propagate<SetDelta<String>>>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
        private val name: String,
        private val events: MutableList<Event>,
        private val keep: (String) -> Boolean,
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.onEach { delta ->
                events += Event.Lane(name, CurrentContext.get()!!.timestamp)
                val filtered = SetDelta(
                    adds = delta.adds.filterKeys(keep),
                    dels = delta.dels.filterKeys(keep),
                )
                if (filtered.adds.isEmpty() && filtered.dels.isEmpty()) {
                    outlet.absorbAck()
                } else {
                    outlet.call.propagate(filtered)
                }
            }
        }
    }

    private class AckProbe(
        clazz: Class<Propagate<SetDelta<String>>>,
        private val events: MutableList<Event>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))

        init {
            inlet.onEach { }
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message ->
                val progress = message as Progress
                events += Event.Ack(Timestamp(progress.sourceId, progress.thru))
            }
        }
    }

    private enum class FanInKind { QUORUM, UNION }

    private data class FanInPorts(
        val cell: Cell,
        val inlet: FanInlet<Propagate<SetDelta<String>>>,
        val outlet: FanOutlet<Propagate<SetDelta<String>>>,
    )

    private fun fanIn(kind: FanInKind, gated: Boolean = true): FanInPorts = when (kind) {
        FanInKind.QUORUM -> QuorumSetCell.union<String>(emitOnFrontier = gated)
            .let { FanInPorts(it, it.inlet, it.outlet) }
        FanInKind.UNION -> UnionSetCell<String>(emitOnFrontier = gated)
            .let { FanInPorts(it, it.inlet, it.outlet) }
    }

    /**
     * The first (odd) wave is absent on both x-only arms but real on the mirror.
     * A later even wave can therefore advance an eager fan-in's one output edge
     * past that unsettled mirror delivery. The gated join then mistakes wave 1
     * for complete and emits Progress before the late real contribution arrives.
     */
    private fun firstPrematureAck(seed: Long, waves: Int, kind: FanInKind): Timestamp? {
        val controller = SimulationController(seed)
        val sourceHost = ManagedHost(scheduler = controller.scheduler())
        val baseHost = ManagedHost(scheduler = controller.scheduler())
        val xHost = ManagedHost(scheduler = controller.scheduler())
        val mirrorHost = ManagedHost(scheduler = controller.scheduler())
        val sinkHost = ManagedHost(scheduler = controller.scheduler())
        val events = mutableListOf<Event>()

        val source = SetSource()
        val base = SetArm(setApi, name = "base", events = events) { it.startsWith("x") }
        val xOnly = SetArm(setApi, name = "fan-x", events = events) { it.startsWith("x") }
        val mirror = SetArm(setApi, name = "mirror", events = events) { true }
        val fanIn = fanIn(kind)
        val join = SemiJoinCell<String, String, String>(
            leftKey = { it },
            rightKey = { it },
            emitOnFrontier = true,
            requireSettledFanIn = true,
        )
        val probe = AckProbe(setApi, events)

        sourceHost.managementInlet.call.spawn(source)
        baseHost.managementInlet.call.spawn(base)
        xHost.managementInlet.call.spawn(xOnly)
        mirrorHost.managementInlet.call.spawn(mirror)
        listOf(fanIn.cell, join, probe).forEach(sinkHost.managementInlet.call::spawn)

        listOf(baseHost to base, xHost to xOnly, mirrorHost to mirror).forEach { (host, arm) ->
            source.outlet.subscribe(
                Use.fixed(host.lookup<SetArmProxy>(arm.ref)!!.inlet.call, PortRef.generate()),
            )
        }
        @Suppress("UNCHECKED_CAST")
        xOnly.outlet.linkTo(fanIn.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        mirror.outlet.linkTo(fanIn.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        base.outlet.linkTo(join.left as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        fanIn.outlet.linkTo(join.right as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        join.outlet.linkTo(probe.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        controller.runToIdle()

        val random = Random(seed)
        val tagSource = UUID.randomUUID()
        for (counter in 1..waves) {
            val prefix = if (counter % 2 == 0) "x" else "y"
            val element = "$prefix$counter"
            source.send(SetDelta(adds = mapOf(element to setOf(Timestamp(tagSource, counter.toLong())))))
            repeat(random.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()

        return events.withIndex()
            .filter { it.value is Event.Ack }
            .firstNotNullOfOrNull { (ackIndex, event) ->
                val wave = event.wave
                val settled = events.take(ackIndex)
                    .filterIsInstance<Event.Lane>()
                    .filter { it.wave == wave }
                    .mapTo(mutableSetOf()) { it.name }
                wave.takeIf { "fan-x" !in settled || "mirror" !in settled }
            }
    }

    private fun assertNoPrematureAck(kind: FanInKind) {
        val premature = (0L until 200L).mapNotNull { seed ->
            firstPrematureAck(seed, waves = 8, kind = kind)?.let { seed to it.counter }
        }
        withClue("${kind.name}: premature Progress in ${premature.size}/200 seeds: $premature") {
            premature.shouldBeEmpty()
        }
    }

    @Test
    fun `frontier-settled QuorumSetCell never advances a gated join before both lanes over 200 seeds`() {
        assertNoPrematureAck(FanInKind.QUORUM)
    }

    @Test
    fun `frontier-settled UnionSetCell never advances a gated join before both lanes over 200 seeds`() {
        assertNoPrematureAck(FanInKind.UNION)
    }

    @Test
    fun `gated join rejects ungated fan-in outlets naming the operator`() {
        listOf(
            QuorumSetCell.union<String>().outlet to "QuorumSetCell",
            UnionSetCell<String>().outlet to "UnionSetCell",
        ).forEach { (outlet, name) ->
            val join = SemiJoinCell<String, String, String>(
                leftKey = { it },
                rightKey = { it },
                emitOnFrontier = true,
                requireSettledFanIn = true,
            )
            @Suppress("UNCHECKED_CAST")
            val result = outlet.linkTo(join.right as LinkFrom<Propagate<SetDelta<String>>>)
            val rejection = result.shouldBeInstanceOf<LinkResult.Rejected>()
            rejection.reason shouldContain name
        }
    }

    @Test
    fun `single-input QuorumSetCell with opaque ancestry remains admissible to aligned observation`() {
        val controller = SimulationController(0)
        val sourceHost = ManagedHost(scheduler = controller.scheduler())
        val armHost = ManagedHost(scheduler = controller.scheduler())
        val sinkHost = ManagedHost(scheduler = controller.scheduler())
        val events = mutableListOf<Event>()
        val source = SetSource()
        val arm = SetArm(setApi, name = "only", events = events) { true }
        val quorum = QuorumSetCell.union<String>()

        sourceHost.managementInlet.call.spawn(source)
        armHost.managementInlet.call.spawn(arm)
        sinkHost.managementInlet.call.spawn(quorum)
        source.outlet.subscribe(
            Use.fixed(armHost.lookup<SetArmProxy>(arm.ref)!!.inlet.call, PortRef.generate()),
        )
        @Suppress("UNCHECKED_CAST")
        arm.outlet.linkTo(quorum.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        controller.runToIdle()

        sinkHost.observeAligned { set("quorum", quorum.ref) }
        quorum.inlet.linking.links.count { it.to == quorum.inlet.ref } shouldBe 1
    }

    /**
     * Aligned admission over the two-lane cross-host fan-in: an ungated
     * instance is rejected naming its operator (computenet-xas2g's rule for
     * Quorum, extended to Union here), a gated instance is admitted.
     */
    private fun admitTwoLaneFanIn(kind: FanInKind, gated: Boolean) {
        val controller = SimulationController(0)
        val sourceHost = ManagedHost(scheduler = controller.scheduler())
        val xHost = ManagedHost(scheduler = controller.scheduler())
        val mirrorHost = ManagedHost(scheduler = controller.scheduler())
        val sinkRegistry = LocationRegistry()
        val sinkHost = ManagedHost(registry = sinkRegistry, scheduler = controller.scheduler())
        val events = mutableListOf<Event>()

        val source = SetSource()
        val xOnly = SetArm(setApi, name = "fan-x", events = events) { it.startsWith("x") }
        val mirror = SetArm(setApi, name = "mirror", events = events) { true }
        val fanIn = fanIn(kind, gated)

        sourceHost.managementInlet.call.spawn(source)
        xHost.managementInlet.call.spawn(xOnly)
        mirrorHost.managementInlet.call.spawn(mirror)
        sinkHost.managementInlet.call.spawn(fanIn.cell)
        listOf(xHost to xOnly, mirrorHost to mirror).forEach { (host, arm) ->
            source.outlet.subscribe(
                Use.fixed(host.lookup<SetArmProxy>(arm.ref)!!.inlet.call, PortRef.generate()),
            )
        }
        @Suppress("UNCHECKED_CAST")
        xOnly.outlet.linkTo(fanIn.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        mirror.outlet.linkTo(fanIn.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        controller.runToIdle()

        if (gated) {
            sinkHost.observeAligned { set("fanIn", fanIn.cell.ref) }
            return
        }
        val refsBefore = sinkRegistry.localRefs().size
        val linksBefore = fanIn.outlet.linking.links.size
        val error = assertThrows<AlignedAdmissionException> {
            sinkHost.observeAligned { set("fanIn", fanIn.cell.ref) }
        }
        val verdict = error.verdict.shouldBeInstanceOf<AdmissionVerdict.Rejected.UngatedAncestor>()
        verdict.cell shouldBe fanIn.cell.ref
        verdict.cellClass shouldBe fanIn.cell::class.java
        error.message!! shouldContain fanIn.cell::class.java.simpleName
        sinkRegistry.localRefs().size shouldBe refsBefore
        fanIn.outlet.linking.links.size shouldBe linksBefore
    }

    @Test
    fun `aligned observation rejects an ungated two-lane cross-host fan-in naming the operator`() {
        FanInKind.entries.forEach { kind -> withClue(kind.name) { admitTwoLaneFanIn(kind, gated = false) } }
    }

    @Test
    fun `aligned observation admits a frontier-settled two-lane cross-host fan-in`() {
        FanInKind.entries.forEach { kind -> withClue(kind.name) { admitTwoLaneFanIn(kind, gated = true) } }
    }

    @Test
    fun `eager QuorumSetCell absorb-acks a live delivery whose lane fold is empty`() {
        val controller = SimulationController(0)
        val host = ManagedHost(scheduler = controller.scheduler())
        val events = mutableListOf<Event>()
        val source = SetSource()
        val quorum = QuorumSetCell.union<String>()
        val probe = AckProbe(setApi, events)
        listOf(source, quorum, probe).forEach(host.managementInlet.call::spawn)
        @Suppress("UNCHECKED_CAST")
        source.outlet.linkTo(quorum.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        @Suppress("UNCHECKED_CAST")
        quorum.outlet.linkTo(probe.inlet as LinkFrom<Propagate<SetDelta<String>>>)
        controller.runToIdle()

        val tag = Timestamp(UUID.randomUUID(), 1)
        source.send(SetDelta(adds = mapOf("a" to setOf(tag))))
        controller.runToIdle()
        events.shouldBeEmpty()
        // The same tag again: the lane fold is empty, so the default (eager)
        // path must absorb-ack the wave rather than swallow it silently.
        source.send(SetDelta(adds = mapOf("a" to setOf(tag))))
        controller.runToIdle()
        events.filterIsInstance<Event.Ack>().size shouldBe 1
    }
}
