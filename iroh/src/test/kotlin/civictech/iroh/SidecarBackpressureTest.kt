package civictech.iroh

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import civictech.testkit.awaitUntil
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * The host's recovery contract for a refused `DATA` (`computenet-ey4v`,
 * residual of `computenet-3gij`): **an `ERROR` on an established link is
 * terminal for that link, and the host closes it.**
 *
 * `PROTOCOL.md` §2's Backpressure section is the normative statement and
 * carries the reasoning; the short of it is that the wire names a *link* and
 * never a *frame*. An `ERROR` carries a kind byte, a link id and a UTF-8
 * reason and no sequence number, while an accepted `DATA` is answered with
 * nothing at all — so a host with several `DATA` outstanding on a link cannot
 * tell which one was refused, and with a concurrent consumer the refusals are
 * not even a contiguous suffix. Nothing addressed to a frame is implementable
 * over that; discarding the link is, and it discards the hole with it.
 *
 * ## Why these run against a fake sidecar rather than a real one
 *
 * The rule under test is entirely the **host's**, and the sidecar's part in it
 * is one `ERROR` frame. Driving the client over a plain loopback socket that
 * speaks `PROTOCOL.md` by hand makes the refusal deterministic — no flood, no
 * 256-frame race, no timing — and, because it needs no `-Piroh.enabled=true`
 * build, it executes on the default lanes rather than reporting SKIPPED there
 * ([SidecarBinary]). The sidecar's own half of the contract — that a `DATA`
 * past the bound is answered with `ERROR` on its link and not sent, while the
 * host connection keeps answering — is pinned in Rust, by
 * `sidecar/tests/protocol.rs`'s
 * `a_flood_on_an_unadopted_inbound_link_leaves_the_host_connection_answering`.
 */
class SidecarBackpressureTest {

    private val peerId = ByteArray(NODE_ID_LEN) { (it + 1).toByte() }

    @Test
    fun `an ERROR on an established link makes the host close that link`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val listener = RecordingLinkListener("refused")
                val link = fake.dialAndAnswer(client, listener)

                link.send(byteArrayOf(1, 2, 3))
                val sent = assertIs<HostMessage.Data>(fake.nextHostMessage())
                assertEquals(link.id, sent.link)

                // The sidecar refuses that frame: PROTOCOL.md §2, the queue-full
                // arm of server.rs's kind::DATA handler, verbatim.
                val reason = "link ${link.id}'s send queue is full (256 frames outstanding); the frame was not sent"
                fake.send(SidecarMessage.Failure(link.id, reason))

                // THE PIN. The host answers a refusal by taking the link down —
                // not by resending, not by carrying on, and without having had to
                // work out which frame was refused.
                val answer = fake.nextHostMessage()
                assertEquals(
                    HostMessage.CloseLink(link.id),
                    answer,
                    "PROTOCOL.md §2: an ERROR on an established link is terminal for it and the host must " +
                        "CLOSE_LINK; the host answered with $answer instead",
                )

                // The client sets refused/sends CLOSE_LINK/calls onError in that program
                // order on its single reader thread (SidecarClient.onError), but onError
                // runs on that reader thread while this assertion runs on the test thread
                // — observing CLOSE_LINK above says nothing about whether onError has run
                // yet. Bound-wait for the listener's record rather than reading it cold.
                awaitUntil("listener.errors to record the refusal") { listener.errors.isNotEmpty() }
                assertEquals(listOf(reason), listener.errors.toList(), "the refusal is reported verbatim, not absorbed")
                // Safe without a wait: refused is set (via CAS) strictly before CLOSE_LINK
                // is sent on the same reader thread, so this fake having already observed
                // CLOSE_LINK above guarantees refused is already true.
                assertTrue(link.refusedAndClosed, "the link records that it ended in a refusal")

                // And nothing more goes onto it: recovery is a NEW link.
                val refused = assertFailsWith<SidecarException> { link.send(byteArrayOf(4)) }
                assertTrue(
                    "resending" in (refused.message ?: ""),
                    "the refusal to send should say why resending is wrong, got: ${refused.message}",
                )

                // The LINK_DOWN that close produces arrives as an ordinary down —
                // which is what an IrohConnection recovers from, with a new link.
                fake.send(SidecarMessage.LinkDown(link.id, "link closed"))
                assertEquals("link closed", listener.nextDown())
                assertEquals(1, listener.downCount.get())
            }
        }
    }

    /**
     * The rule is scoped to *established* links. An `ERROR` answering a `DIAL`
     * settles that dial and establishes nothing, so there is no link to close —
     * a client that closed on it would name a link the sidecar never had.
     */
    @Test
    fun `an ERROR answering a DIAL closes nothing`() {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val dialing = Thread {
                    assertFailsWith<SidecarException> { client.dial(peerId, RecordingLinkListener("dial")) }
                }
                dialing.start()

                val dial = assertIs<HostMessage.Dial>(fake.nextHostMessage())
                fake.send(SidecarMessage.Failure(dial.link, "dial failed: no route to peer"))
                dialing.join(TimeUnit.SECONDS.toMillis(30))

                assertEquals(
                    null,
                    fake.pollHostMessage(500),
                    "an ERROR that answers a DIAL establishes no link, so the host must send no CLOSE_LINK for it",
                )
            }
        }
    }

    // ------------------------------------- what an IrohConnection does with one

    /**
     * The connection-level half of the same contract, and the pin
     * `computenet-ey4v` shipped without (`computenet-pozr`).
     *
     * A refusal ends its link, and the `LINK_DOWN` that ending produces is
     * byte-identical to any other — including to the one a *refused hello*
     * produces at the listening side's allowlist, which is the signal
     * [IrohTransport.REFUSED_DIAL_LIMIT] counts. So a link torn down because
     * this side's own send queue overflowed would otherwise read as one more
     * peer refusal, and enough of them would abandon a perfectly willing peer.
     * `IrohConnection.linkRefused` is what separates the two, and these three
     * tests are what say so.
     *
     * Driven over [FakeSidecar] like the tests above, and for the same payoff:
     * no cargo, no flood, no timing. `IrohTransport.Sidecar` is the seam that
     * makes an [IrohTransport.IrohConnection] constructible without a spawned
     * binary.
     */
    @Test
    fun `an unadmitted link that simply goes down counts toward the refused-dial bound`() {
        assertEquals(
            1,
            unadmittedOpensAfterOneUnadmittedLink(refusedFirst = false),
            "a link that came up and went down without ever being admitted is the local shadow of a refused " +
                "hello (computenet-4gzr) and must count toward REFUSED_DIAL_LIMIT",
        )
    }

    /** THE PIN. @see `an unadmitted link that simply goes down counts toward the refused-dial bound` */
    @Test
    fun `an unadmitted link the sidecar refused something on does not count toward the refused-dial bound`() {
        assertEquals(
            0,
            unadmittedOpensAfterOneUnadmittedLink(refusedFirst = true),
            "a link this side tore down because the sidecar refused a frame on it (PROTOCOL.md section 2) is no " +
                "evidence about the PEER's willingness, and must not accumulate toward REFUSED_DIAL_LIMIT — " +
                "letting it would abandon a willing peer after a queue-overflow flood (computenet-4gzr)",
        )
    }

    /**
     * The one-shot's lifetime, which the guard's own KDoc records as
     * intentional: `retire` consumes the flag only *past* its `shuttingDown`
     * and `closeRequested` early returns, so an `ERROR` followed by a
     * caller-requested close leaves it set and it exempts the **next**
     * unplanned down instead.
     *
     * This is here to make that a decision rather than an accident. It fails
     * safe — abandonment is delayed by at most one link, never triggered early
     * — and a future change of mind should have to come here and say so.
     */
    @Test
    fun `the refusal flag outlives a caller-requested close and exempts the next unplanned down`() {
        val side = dialerSide()
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                connectionOver(client, side).use { connection ->
                    val refused = fake.answerOpenLink(connection)
                    fake.refuse(refused)

                    // A close this side asks for, INSTEAD of the LINK_DOWN the
                    // client's own CLOSE_LINK would have produced. retire()
                    // returns at `closeRequested` without reading the flag.
                    connection.sever()
                    assertEquals(HostMessage.CloseLink(refused), fake.nextHostMessage())
                    fake.send(SidecarMessage.LinkDown(refused, "link closed"))

                    // The next link is an ORDINARY unadmitted down. Its retire()
                    // reaches scheduleReconnect, which nothing before it did —
                    // so one backoff consultation is an exact witness that this
                    // second retire ran to the end.
                    val next = fake.answerOpenLink(connection)
                    fake.send(SidecarMessage.LinkDown(next, "transport drop"))
                    awaitUntil("retire() to have decided about the second link") {
                        connection.backoffConsultations >= 1L
                    }

                    assertEquals(
                        0,
                        connection.unadmittedOpens,
                        "the refusal flag is documented as surviving retire()'s closeRequested early return and " +
                            "exempting the next unplanned down; it exempted nothing",
                    )
                    assertTrue(
                        !connection.abandonedAfterRefusals,
                        "nothing here reaches REFUSED_DIAL_LIMIT, so the connection must still be re-dialling",
                    )
                }
            }
        }
    }

    /**
     * One [IrohTransport.IrohConnection] over the fake, one link on it that comes
     * **up** and goes **down without ever being admitted** (the peer never
     * answers this side's hello), and the connection's unadmitted count once
     * `retire` has decided.
     *
     * [refusedFirst] is the whole distinction: with it, the sidecar refuses a
     * frame on the link before the down, exactly as `PROTOCOL.md` section 2's
     * queue-full arm does.
     */
    private fun unadmittedOpensAfterOneUnadmittedLink(refusedFirst: Boolean): Int {
        val side = dialerSide()
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                connectionOver(client, side).use { connection ->
                    val link = fake.answerOpenLink(connection)
                    if (refusedFirst) fake.refuse(link)
                    fake.send(SidecarMessage.LinkDown(link, if (refusedFirst) "link closed" else "transport drop"))
                    // retire() consults the schedule as its last act on both
                    // paths under test (neither reaches the limit), so this is a
                    // witness that the decision has been made — not a sleep.
                    awaitUntil("retire() to have decided") { connection.backoffConsultations >= 1L }
                    return connection.unadmittedOpens
                }
            }
        }
    }

    /** A dialling side with no allowlist; nothing here ever gets as far as admission. */
    private fun dialerSide(): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(registry, ManagedHost(registry = registry), peer = null, allow = null)
    }

    /**
     * A connection over [client] with a schedule long enough that its re-dial
     * loop never fires inside a test — every link here is opened deliberately,
     * so the fake never has to race one it did not ask for. The consultation is
     * still recorded ([IrohTransport.IrohConnection.backoffConsultations]),
     * which is what the tests wait on.
     */
    private fun connectionOver(client: SidecarClient, side: Peering.Side) =
        IrohTransport.IrohConnection(
            sidecar = NoSidecar,
            client = client,
            side = side,
            peerNodeId = peerId,
            backoff = { 60_000L },
            redialTimeout = 30.seconds,
        )

    /** There is no child process behind [FakeSidecar]; the node id is never read here. */
    private object NoSidecar : IrohTransport.Sidecar {
        override val nodeId: ByteArray get() = ByteArray(NODE_ID_LEN)
        override fun close() = Unit
    }

}
