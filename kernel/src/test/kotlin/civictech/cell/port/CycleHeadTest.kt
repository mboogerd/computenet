package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.Leased
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.data.Magnitude
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.math.abs

/**
 * W3.1 — CycleHead & two-tier quiescence (spec 21 §Cycles, 10/13
 * `CycleWithoutHead`, 20/22 rule 2, 20/23 §Cycle edges, 93 I-5/I-6).
 */
class CycleHeadTest {

    private data class Delta(val value: Double) : Magnitude {
        override fun size() = abs(value)
    }

    /** A cell owning a declared cycle head (spec 21 §Cycles): absorbs the returning lap, re-emits a contracted delta. */
    private class HeadCell(
        quiescence: Double,
        private val factor: Double,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell, CycleHead<Delta> {
        val outlet by output<Consumer<Delta>>()
        val laps = mutableListOf<Double>()

        override val feedbackInput by feedbackInlet<Delta>(quiescence) { delta ->
            laps += delta.value
            outlet.call.provide(Delta(delta.value * factor))
        }
    }

    /** A plain transparent-flow hop — no cycle-head machinery at all. */
    private class RelayCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<Consumer<Delta>>()
        val outlet by output<Consumer<Delta>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<Delta> {
                override fun provide(input: Delta) = outlet.call.provide(input)
            })
        }
    }

    private interface RelayInterface {
        val inlet: Use<Consumer<Delta>>
    }

    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : civictech.cell.data.Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) {
                letters += value
            }
        }, PortRef.generate()))
        return letters
    }

    // ---- Convergent (headed) vs divergent (headless) control comparison ----

    @Test
    fun `a headed loop absorbs sub-threshold laps and quiesces without dead letters`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val letters = collectDeadLetters(host)

        val head = HeadCell(quiescence = 0.01, factor = 0.4)
        val relay = RelayCell()
        host.managementInlet.call.spawn(head)
        host.managementInlet.call.spawn(relay)
        host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")
        // Closing edge lands on a declared CycleHead: admitted (spec 10/13).
        val closing = host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
        closing.shouldBeInstanceOf<LinkResult.Connected>()

        head.outlet.originate { provide(Delta(1.0)) }
        controller.runToIdle()

        // Strictly contracting (factor 0.4 < 1): the weak-tier damper absorbs
        // the lap once size() falls to/under quiescence, so laps stay few and
        // finite — no dead letters, no hop-guard involvement whatsoever.
        head.laps.isNotEmpty() shouldBe true
        head.laps.size shouldBe head.laps.count { it > 0.01 }
        (head.laps.size < 10) shouldBe true
        letters.shouldBeEmptyList()
    }

    private fun MutableList<DeadLetter>.shouldBeEmptyList() {
        size shouldBe 0
    }

    @Test
    fun `a headless cross-host loop free-runs until the hop guard dead-letters it`() {
        val controller = SimulationController()
        // No shared registry: cross-host proxies use the fixed-host form, so
        // this cycle is never locally visible to either host's topology index
        // — exactly the case 21 §Cycles calls out as invisible to link-time
        // admission, backstopped only by the hop guard.
        val hostA = ManagedHost(scheduler = controller.scheduler(), hopBound = 8)
        val hostB = ManagedHost(scheduler = controller.scheduler(), hopBound = 8)
        val lettersA = collectDeadLetters(hostA)
        val lettersB = collectDeadLetters(hostB)

        val relayA = RelayCell()
        val relayB = RelayCell()
        hostA.managementInlet.call.spawn(relayA)
        hostB.managementInlet.call.spawn(relayB)

        val proxyToB = hostB.lookup<RelayInterface>(relayB.ref)!!
        val proxyToA = hostA.lookup<RelayInterface>(relayA.ref)!!
        relayA.outlet.linkTo(proxyToB.inlet)
        relayB.outlet.linkTo(proxyToA.inlet)

        relayA.outlet.originate { provide(Delta(1.0)) }
        controller.runToIdle()

        val letters = lettersA + lettersB
        letters.size shouldBe 1
        letters.single().description shouldContain "cycle hop guard"
        letters.single().cause.shouldBeInstanceOf<CycleError>()
    }

    // ---- Admission (spec 10/13 `CycleWithoutHead`) ----

    @Test
    fun `connect rejects a locally-visible cycle closing on a plain inlet`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

        val a = RelayCell()
        val b = RelayCell()
        host.managementInlet.call.spawn(a)
        host.managementInlet.call.spawn(b)
        host.managementInlet.call.connect(a.ref, "outlet", b.ref, "inlet")

        val closing = host.managementInlet.call.connect(b.ref, "outlet", a.ref, "inlet")
        val rejected = closing.shouldBeInstanceOf<LinkResult.Rejected>()
        rejected.reason shouldContain "CycleWithoutHead"
    }

    @Test
    fun `connect admits a locally-visible cycle closing on a declared CycleHead`() {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

        val head = HeadCell(quiescence = 0.0, factor = 1.0)
        val relay = RelayCell()
        host.managementInlet.call.spawn(head)
        host.managementInlet.call.spawn(relay)
        host.managementInlet.call.connect(head.ref, "outlet", relay.ref, "inlet")

        val closing = host.managementInlet.call.connect(relay.ref, "outlet", head.ref, "feedbackInput")
        closing.shouldBeInstanceOf<LinkResult.Connected>()
    }

    // ---- Absorb, not join: fresh wave + hop reset (bare, no host) ----

    @Test
    fun `absorption mints a fresh wave and resets hop, never joining the incoming context`() {
        var seenContext: MessageContext? = null
        val head = FeedbackInlet<Delta>(ref = PortRef.generate(), quiescence = 0.0) { _ ->
            seenContext = CurrentContext.get()
        }

        val incoming = MessageContext(Timestamp(UUID.randomUUID(), 5L), PortRef.generate(), hop = 7)
        CurrentContext.with(incoming) {
            head.call.provide(Delta(1.0))
        }

        val seen = seenContext!!
        seen.hop shouldBe 0
        seen.sourcePort shouldBe head.ref
        (seen.timestamp.sourceId != incoming.timestamp.sourceId) shouldBe true
    }

    @Test
    fun `weak-tier damper absorbs a sub-threshold delta without invoking onLap`() {
        var laps = 0
        val head = FeedbackInlet<Delta>(quiescence = 0.5) { _ -> laps++ }

        head.call.provide(Delta(0.1)) // below threshold: absorbed silently
        laps shouldBe 0

        head.call.provide(Delta(1.0)) // above threshold: re-originates
        laps shouldBe 1
    }

    // ---- Leased forbidden on cycle edges (spec 20/23, 93 I-6) ----

    @Test
    fun `a Leased payload on a feedback inlet is rejected, not absorbed`() {
        val head = FeedbackInlet<Any>(quiescence = 0.0) { }
        val leased = Leased(value = "x")

        val error = assertThrows<CycleError> { head.call.provide(leased) }
        error.message shouldContain "CycleRejectsLeased"
    }
}
