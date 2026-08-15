package civictech.cell.membrane

import civictech.cell.BoundaryDenial
import civictech.cell.BoundaryDenials
import civictech.cell.BoundaryDenialSink
import civictech.cell.BoundarySeam
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
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
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Seam 3 `PORT_API` inbound (spec 40/43, decided 93 I-28): `RequireSigned`
 * verifies a signature at ingress before delivery; failure dead-letters
 * (never delivered) rather than throwing or forwarding unverified data. Only
 * `AuthLevel.TransportVouched` strength is available (phase-2 keys/DIDs are
 * research, 95 §R7): [SignatureVerifier.TransportVouched] checks the
 * signature names the peer the transport already vouches for; the
 * increasing per-source counter is what actually defeats replay at this
 * phase — every test below is phrased around that counter, never around
 * forgery resistance ([SEC1-24]).
 *
 * Each of [MediateProxy.verifyOrDrop]'s three failure branches now reports
 * through a [BoundaryDenialSink] (`computenet-usd.1.2`, adopting the seam
 * `computenet-usd.1.1` built): `UNSIGNED`, `BAD_SIGNATURE`, `REPLAY`. The
 * class KDoc's "dead-lettered, never delivered" claim is exercised end to
 * end by the hosted test at the bottom of this file.
 */
class MediateProxyIntegrityTest {

    private val propagateMethod = Propagate::class.java.methods.single { it.name == "propagate" }
    private val peer = PeerId("replica-a")

    private fun signed(payload: String, counter: Long, wrongSignature: Boolean = false) = SignedDelta(
        payload = payload,
        mintingPeer = peer,
        counter = counter,
        signature = if (wrongSignature) "not-${peer.name}".toByteArray() else peer.name.toByteArray(),
    )

    /** A fresh per-exposure sink plus every [BoundaryDenial] reported through it, for assertions. */
    private fun trackingSink(): Pair<BoundaryDenialSink, MutableList<BoundaryDenial>> {
        val denials = BoundaryDenials()
        val records = mutableListOf<BoundaryDenial>()
        denials.attachReporter { denial, _ -> records += denial }
        return denials.sinkFor("exposedInlet") to records
    }

    @Test
    fun `BS-3 RequireSigned delivers a validly signed delta with an increasing counter, no denial record`() {
        val received = mutableListOf<String>()
        val target = object : Propagate<String> {
            override fun propagate(value: String) {
                received += value
            }
        }
        val (sink, records) = trackingSink()
        val proxy = MediateProxy(target, IntegrityPolicy.RequireSigned, denials = sink)

        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-1", counter = 1)))
        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-2", counter = 2)))

        received shouldBe listOf("delta-1", "delta-2")
        sink.denialCount shouldBe 0L
        records shouldBe emptyList()
    }

    @Test
    fun `RequireSigned dead-letters an unsigned argument, accounted as UNSIGNED`() {
        val received = mutableListOf<String>()
        val target = object : Propagate<String> {
            override fun propagate(value: String) {
                received += value
            }
        }
        val (sink, records) = trackingSink()
        val proxy = MediateProxy(target, IntegrityPolicy.RequireSigned, denials = sink)

        // A bare, un-enveloped payload — not a SignedDelta at all.
        proxy.invoke(null, propagateMethod, arrayOf("raw-delta"))

        received shouldBe emptyList()
        sink.denialCount shouldBe 1L
        val denial = records.single()
        denial.seam shouldBe BoundarySeam.INTEGRITY
        denial.reason shouldBe DenialReason.UNSIGNED
        // No SignedDelta means no mintingPeer to name.
        denial.principal shouldBe null
    }

    @Test
    fun `RequireSigned dead-letters an invalid signature, accounted as BAD_SIGNATURE`() {
        val received = mutableListOf<String>()
        val target = object : Propagate<String> {
            override fun propagate(value: String) {
                received += value
            }
        }
        val (sink, records) = trackingSink()
        val proxy = MediateProxy(target, IntegrityPolicy.RequireSigned, denials = sink)

        proxy.invoke(null, propagateMethod, arrayOf(signed("forged", counter = 1, wrongSignature = true)))

        received shouldBe emptyList()
        sink.denialCount shouldBe 1L
        val denial = records.single()
        // [SEC1-24]: TransportVouched is a byte-compare against the peer name,
        // not a forgery-resistant scheme — this only asserts the refusal is
        // accounted with the right reason and principal, not that the
        // signature scheme resists forgery.
        denial.reason shouldBe DenialReason.BAD_SIGNATURE
        denial.principal shouldBe peer
    }

    @Test
    fun `BS-4 a replayed counter is refused with one REPLAY record naming the peer and counter, then counter n+1 converges`() {
        val received = mutableListOf<String>()
        val target = object : Propagate<String> {
            override fun propagate(value: String) {
                received += value
            }
        }
        val (sink, records) = trackingSink()
        val proxy = MediateProxy(target, IntegrityPolicy.RequireSigned, denials = sink)

        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-1", counter = 5)))
        sink.denialCount shouldBe 0L

        // Replay of the same counter (or an earlier one) — refused before
        // delivery, never forwarded to the target, no ack/retry frame produced
        // (this proxy has none to produce — the only observable effects are
        // the target's `received` list and the denial accounting below).
        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-1-replayed", counter = 5)))
        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-0-replayed", counter = 3)))

        received shouldBe listOf("delta-1")
        sink.denialCount shouldBe 2L
        records.size shouldBe 2
        records.forEach { denial ->
            denial.seam shouldBe BoundarySeam.INTEGRITY
            denial.reason shouldBe DenialReason.REPLAY
            denial.principal shouldBe peer
        }
        records[0].detail shouldContain "counter=5"
        records[1].detail shouldContain "counter=3"

        // A later, correctly-signed copy with a strictly increasing counter
        // (n+1 relative to the last accepted 5) is accepted and converges
        // normally — the replay defense does not wedge the peer permanently.
        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-2", counter = 6)))

        received shouldBe listOf("delta-1", "delta-2")
        sink.denialCount shouldBe 2L
    }

    /** Minimal organelle behind a hosted, mediated, signature-required exposure. */
    private class SignedInletOrganelle(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<String>>())

        init {
            inlet.serve(object : Propagate<String> {
                override fun propagate(value: String) {
                    received += value
                }
            })
        }
    }

    private class SignedMembrane(
        val organelle: SignedInletOrganelle = SignedInletOrganelle(),
    ) : CompositeCell() {
        val exposedInlet = mediate(
            "exposedInlet",
            "inlet",
            organelle.inlet,
            BoundaryPolicy(integrity = IntegrityPolicy.RequireSigned),
        )
    }

    @Test
    fun `BS-14 a replay refusal through a hosted mediate exposure is not a fault`() {
        val controller = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = controller.scheduler())
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

        val membrane = SignedMembrane()
        host.managementInlet.call.spawn(membrane)
        // RESTART, so BS-14 is a real check rather than a vacuous one: were the
        // replay refusal classified as a cell fault anywhere on this path, this
        // is the policy that would fire ([SEC1-29]).
        host.managementInlet.call.supervise(membrane.ref, SupervisionPolicy.RESTART)
        controller.runToIdle()

        // An accepted, validly-signed delivery — the BS-3 twin, through the
        // real hosted exposure this time, not a direct MediateProxy call.
        propagateMethod.invoke(membrane.exposedInlet.call, signed("delta-1", counter = 1))
        controller.runToIdle()

        membrane.organelle.received shouldBe listOf("delta-1")
        val sink = membrane.boundaryDenials["exposedInlet"]!!
        sink.denialCount shouldBe 0L
        letters.size shouldBe 0

        // The replay: refused before deltaInlet, never reaching the organelle.
        propagateMethod.invoke(membrane.exposedInlet.call, signed("delta-1-replayed", counter = 1))
        controller.runToIdle()

        membrane.organelle.received shouldBe listOf("delta-1")
        sink.denialCount shouldBe 1L

        letters.size shouldBe 1
        val letter = letters.single()
        letter.cause shouldBe null
        letter.description shouldContain "exposedInlet"
        letter.description shouldContain "REPLAY"
        letter.description shouldContain peer.name

        // Not a fault: no supervision RESTART fired.
        host.supervisionAccounting().restarts shouldBe 0L
        // No wave minted or advanced — the report rides a null MessageContext.
        letter.invocation!!.invocation.context shouldBe null
    }
}
