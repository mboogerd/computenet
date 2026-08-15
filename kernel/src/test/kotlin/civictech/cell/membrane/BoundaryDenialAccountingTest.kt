package civictech.cell.membrane

import civictech.cell.BoundaryDenialSink
import civictech.cell.BoundarySeam
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.DenialReason
import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.Redacted
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.PeerId
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Minimal organelle: an inlet that swallows whatever it is given. */
private class SinkOrganelle(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val received = mutableListOf<String>()
    val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

    init {
        inlet.serve(object : Consumer<String> {
            override fun provide(input: String) {
                received += input
            }
        })
    }
}

/** A membrane with one mediated inlet exposure, i.e. one [BoundaryDenialSink]. */
private class AccountedMembrane(
    val organelle: SinkOrganelle = SinkOrganelle(),
) : CompositeCell() {
    val exposedInlet = mediate("exposedInlet", "inlet", organelle.inlet)
}

/**
 * The denial-accounting **seam** (spec 40/43, `[SEC1-25]`/`[SEC1-26]`,
 * `[SEC1-29]`/BS-14), realization (B): a narrow sink, not a thrown
 * `BoundaryDenied` — rationale in `civictech.cell.BoundaryDenials`' KDoc.
 *
 * These tests drive the sink **directly**, which is the point: at this task
 * the three flow-time denial sites (`MediateProxy.verifyOrDrop`,
 * `DisclosurePolicy.asDeltaFilter`, `protocolAuthority.asProtocolFilter`) and
 * seam 2 still return null / reject exactly as before, and sibling tasks
 * `computenet-usd.1.2`..`.1.5` adopt them one by one. What is asserted here is
 * the seam's contract those adoptions will inherit: sanitization is
 * `DeadLetters`' spec-23-R8 rule *reused*, the per-boundary counter is
 * monotonic and test-readable, and a denial is not a cell fault.
 */
class BoundaryDenialAccountingTest {

    private fun collectDeadLetters(host: ManagedHost): MutableList<DeadLetter> {
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        letters += value
                    }
                },
                PortRef.generate(),
            ),
        )
        return letters
    }

    @Test
    fun `a denial carrying exclusives lands exactly one sanitized dead letter and moves only the boundary counter`() {
        val controller = SimulationController(seed = 7)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val membrane = AccountedMembrane()
        host.managementInlet.call.spawn(membrane)
        // RESTART, so "a denial is not a fault" is a real check rather than a
        // vacuous one: were a denial classified as a cell failure anywhere on
        // this path, this is the policy that would fire (BS-14, [SEC1-29]).
        host.managementInlet.call.supervise(membrane.ref, SupervisionPolicy.RESTART)
        controller.runToIdle()

        val sink = membrane.boundaryDenials["exposedInlet"]!!
        sink.denialCount shouldBe 0L

        val owned = Owned("owned-secret")
        val leased = Leased("leased-secret")

        sink.deny(
            seam = BoundarySeam.INTEGRITY,
            reason = DenialReason.REPLAY,
            principal = PeerId("mallory"),
            subject = "Consumer#provide",
            detail = "counter=7 not > last accepted 7",
            deniedArgs = listOf(owned, leased, "plain"),
        )
        controller.runToIdle()

        // 1. the counter moved, on the boundary, by exactly one
        sink.denialCount shouldBe 1L
        membrane.boundaryDenials.denialCount shouldBe 1L

        // 2. exactly one dead letter, and it is a report — no cause, so no
        //    failure was fabricated to carry it
        letters.size shouldBe 1
        val letter = letters.single()
        letter.cause shouldBe null
        letter.description shouldContain "exposedInlet"
        letter.description shouldContain "mallory"
        letter.description shouldContain "REPLAY"

        // 3. sanitized by DeadLetters' spec-23-R8 rule, inherited not
        //    reimplemented: Owned -> Frozen, Leased -> Redacted, and no live
        //    exclusive handle enters the fan-out outlet
        val captured = letter.invocation!!.invocation.args
        captured.size shouldBe 3
        captured[0].shouldBeInstanceOf<Frozen<*>>().value shouldBe "owned-secret"
        captured[1].shouldBeInstanceOf<Redacted>()
        captured[2] shouldBe "plain"
        captured.none { it is Owned<*> || it is Leased<*> } shouldBe true

        // and the discharge really happened on the originals, exactly once
        assertThrows<IllegalStateException> { owned.take() }
        assertThrows<IllegalStateException> { leased.release() }

        // 4. not a fault: no RESTART, no supervision escalation
        host.supervisionAccounting().restarts shouldBe 0L

        // 5. no wave minted or advanced — the report rides a null MessageContext
        letter.invocation!!.invocation.context shouldBe null

        // 6. the organelle never saw anything: accounting a denial delivers nothing
        membrane.organelle.received shouldBe emptyList()
    }

    @Test
    fun `the per-boundary counter is monotonic and per-exposure`() {
        val controller = SimulationController(seed = 8)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = collectDeadLetters(host)

        val membrane = AccountedMembrane()
        host.managementInlet.call.spawn(membrane)
        controller.runToIdle()

        val sink = membrane.boundaryDenials["exposedInlet"]!!
        val other = membrane.boundaryDenials.sinkFor("anotherExposure")

        repeat(3) {
            sink.deny(BoundarySeam.PROTOCOL_AUTHORITY, DenialReason.RATE, principal = PeerId("alice"))
        }
        other.deny(BoundarySeam.LINK_AUTHORITY, DenialReason.LINK_REFUSED, principal = PeerId("mallory"))
        controller.runToIdle()

        sink.denialCount shouldBe 3L
        other.denialCount shouldBe 1L
        membrane.boundaryDenials.denialCount shouldBe 4L
        // alice's rate refusals never touch the other boundary's counter, and
        // vice versa — the counter is per boundary, which is what a sibling
        // task's per-Principal rate assertions (BS-11) will read.
        letters.size shouldBe 4

        // the sink instance is stable: the same exposure resolves to the same
        // counter, so a later adopter cannot silently start a fresh one
        membrane.boundaryDenials.sinkFor("exposedInlet") shouldBe sink
        membrane.boundaryDenials.exposures shouldBe setOf("exposedInlet", "anotherExposure")
    }

    @Test
    fun `an unhosted membrane still counts and never throws`() {
        // A membrane never spawned onto a ManagedHost has no reporter. Accounting
        // a denial must not itself become a failure path — a refusal that crashes
        // the refusing cell is strictly worse than the silent drop it replaces.
        val membrane = AccountedMembrane()
        val sink = membrane.boundaryDenials["exposedInlet"]!!

        val owned = Owned("still-live")
        sink.deny(
            seam = BoundarySeam.DISCLOSURE,
            reason = DenialReason.DISCLOSURE_DENIED,
            deniedArgs = listOf(owned),
        )

        sink.denialCount shouldBe 1L
        // No reporter means no sanitizer ran, so the payload is untouched: the
        // sink deliberately does NOT discharge exclusives itself — there is
        // exactly one discharge site, inside DeadLetters' R8 sanitization.
        owned.take() shouldBe "still-live"
    }

    @Test
    fun `an exposure with no declared boundary seam allocates no sink`() {
        // [SEC1-02]/[SEC1-03], BS-15: default open stays default open. A
        // flatten() exposure with no linkAuthority is not a boundary and gets
        // no accounting object at all.
        val plain = object : CompositeCell() {
            val organelle = SinkOrganelle()
            val flatInlet = flatten("flatInlet", "inlet", organelle.inlet)
        }

        plain.boundaryDenials["flatInlet"] shouldBe null
        plain.boundaryDenials.exposures shouldBe emptySet()
        plain.boundaryDenials.denialCount shouldBe 0L
    }
}
