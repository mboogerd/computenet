package civictech.demo.beadsmirror.e2e

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.wire.Peering
import civictech.demo.beadsmirror.MirrorLink
import civictech.demo.beadsmirror.MirrorTransport
import civictech.demo.beadsmirror.WsMirrorTransport
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

/**
 * Feature computenet-7em.2 rule 4 / design example 4 (task
 * computenet-7em.2.3): the **divergence control** for [ConvergenceSuite] —
 * proof that its equality assertion can see the failure class it claims to
 * gate, by seeding a one-direction gossip defect and watching the identical
 * recorded-seed run go red, then green again with the defect off.
 *
 * A suite that only ever runs the correct pipeline cannot distinguish a
 * working guard from one that is never reached. This class is what makes
 * [WsConvergenceSuiteTest]'s green non-vacuous, and it is the direct
 * counterpart of BDS1's [DivergenceControlTest] for the projector guards.
 *
 * **Deliberately NOT part of [ConvergenceSuite].** The suite is the artifact
 * DSC0 re-runs unmodified over an iroh transport; a seeded defect proves the
 * assertions' teeth ONCE, against the WebSocket binding, and a future
 * transport has no reason to re-pay it. That is why this class names
 * [WsMirrorTransport] where the suite refuses to.
 *
 * **Cost.** The defective run pays the full [TwoNodeRig.AWAIT_CONVERGENCE_MS]
 * budget by construction — it is waiting for something that will never
 * happen — so this class is two rig lifetimes plus one 30s bounded wait, and
 * runs exactly one seed. Do not multiply seeds here; the seed sweep is
 * [ConvergenceSuite]'s.
 */
class ConvergenceDivergenceControlTest {

    @BeforeEach
    fun checkPrerequisites() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
    }

    /**
     * The defective run: with L→D gossip link establishment suppressed
     * ([DeafListenerTransport]), the seeded schedule that
     * [ConvergenceSuite] converges must make the equality assertion **fail**
     * within the bounded wait.
     *
     * Three things are asserted about that failure, because "an
     * `AssertionFailedError` was thrown" alone would also be satisfied by a
     * rig that died:
     *
     * 1. both nodes' poll loops are still alive (`pollerFailure == null`) —
     *    the failure is divergence, not a dead poller;
     * 2. the **live** direction really is live: the dialer's own field on the
     *    shared issue reached the listener's fold, so the defect is
     *    one-directional and not a severed peering wearing a divergence
     *    costume (a total partition would prove far less — it is exactly the
     *    case [ConvergenceSuite]'s partition test already repairs);
     * 3. the **suppressed** direction really is suppressed: the listener's own
     *    field on that same issue never reached the dialer.
     */
    @Test
    fun `with L to D link establishment suppressed, the convergence assertion fails`() {
        val seed = SeededSchedule.SEED_1
        val schedule = SeededSchedule.derive(seed)
        val rig = TwoNodeRig.create("bds2-divergence-control", transport = DeafListenerTransport())
        try {
            rig.startListener()
            rig.startDialer()
            runSchedule(rig, schedule)

            rig.listener.quiesce()
            rig.dialer.quiesce()

            val failure = runCatching {
                rig.await("seed $seed: both nodes converge to equal folds") {
                    rig.listener.view() == rig.dialer.view() && rig.listener.edgeView() == rig.dialer.edgeView()
                }
            }.exceptionOrNull()

            check(failure is AssertionFailedError) {
                "the seeded one-direction defect did not make the equality assertion fail; got $failure"
            }

            // (1) the pollers are alive: this is divergence, not a dead rig.
            rig.listener.app.pollerFailure shouldBe null
            rig.dialer.app.pollerFailure shouldBe null

            // (2) D -> L, the direction the defect leaves alone, carried.
            rig.listener.view()[schedule.sharedIssueId]
                ?.get("notes") shouldBe json("set by D, seed $seed")

            // (3) L -> D, the suppressed direction, carried nothing: the
            //     dialer's own row for the shared issue still holds ITS OWN
            //     `design` (`bd create` writes the field empty, so the key is
            //     present either way — which is why this is a value check and
            //     not a null check), and every issue the listener minted is
            //     missing from the dialer's fold entirely.
            rig.dialer.view()[schedule.sharedIssueId]
                ?.get("design") shouldNotBe json("set by L, seed $seed")
            (rig.listener.view().keys - rig.dialer.view().keys).shouldNotBeEmpty()
            (rig.dialer.view().keys - rig.listener.view().keys).shouldBeEmpty()
        } finally {
            rig.close()
        }
    }

    /**
     * The clean half of the control pair: the identical seed, the identical
     * schedule, the identical assertion — with the defect off — converges.
     * Without this, a red defective run would be evidence of nothing but a
     * fragile test.
     */
    @Test
    fun `the identical run with the defect off converges`() {
        val seed = SeededSchedule.SEED_1
        val schedule = SeededSchedule.derive(seed)
        val rig = TwoNodeRig.create("bds2-divergence-control-clean")
        try {
            rig.startListener()
            rig.startDialer()
            runSchedule(rig, schedule)

            rig.listener.quiesce()
            rig.dialer.quiesce()
            rig.await("seed $seed: both nodes converge to equal folds") {
                rig.listener.view() == rig.dialer.view() && rig.listener.edgeView() == rig.dialer.edgeView()
            }

            rig.listener.view() shouldBe rig.dialer.view()
            rig.listener.edgeView() shouldBe rig.dialer.edgeView()

            // The two fields the defective run splits: here both survive on both sides.
            val onListener = rig.listener.view()[schedule.sharedIssueId]
                ?: error("seed $seed: the shared issue never reached the listener's fold")
            val onDialer = rig.dialer.view()[schedule.sharedIssueId]
                ?: error("seed $seed: the shared issue never reached the dialer's fold")
            onListener["design"] shouldBe json("set by L, seed $seed")
            onDialer["design"] shouldBe json("set by L, seed $seed")
            onListener["notes"] shouldBe json("set by D, seed $seed")
            onDialer["notes"] shouldBe json("set by D, seed $seed")
        } finally {
            rig.close()
        }
    }

    /** Both sides of [schedule], concurrently, failing loudly on either side's `bd` failure. */
    private fun runSchedule(rig: TwoNodeRig, schedule: SeededSchedule) {
        var listenerFailure: Throwable? = null
        var dialerFailure: Throwable? = null
        val listenerThread = Thread({
            try {
                schedule.listenerSteps.forEach { it.apply(rig.listenerWorkspace) }
            } catch (t: Throwable) {
                listenerFailure = t
            }
        }, "divergence-control-listener")
        val dialerThread = Thread({
            try {
                schedule.dialerSteps.forEach { it.apply(rig.dialerWorkspace) }
            } catch (t: Throwable) {
                dialerFailure = t
            }
        }, "divergence-control-dialer")

        listenerThread.start()
        dialerThread.start()
        listenerThread.join()
        dialerThread.join()

        listOfNotNull(listenerFailure, dialerFailure).takeIf { it.isNotEmpty() }?.let { failures ->
            val combined = AssertionError("the seeded schedule failed on ${failures.size} side(s)")
            failures.forEach { combined.addSuppressed(it) }
            throw combined
        }
    }

    private fun json(value: String): String = JsonPrimitive(value).toString()

    private fun commandAvailable(vararg command: String): Boolean = try {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }
}

/**
 * **The seeded defect** (task computenet-7em.2.3): a [MirrorTransport]
 * decorator that leaves the dialer's end alone and makes the LISTENER *deaf*
 * to the peer's announcements — so the listener never learns the dialer's
 * replica refs, never links out to them, and its deltas never reach the
 * dialer, while the dialer's deltas keep reaching the listener.
 *
 * ## Why the defect lives here and not in the kernel
 *
 * The feature's design says "suppressing gossip link establishment in one
 * direction", and one WebSocket socket has no one-direction switch: severing
 * it is [WsMirrorTransport.partition], which is the *other* control entirely.
 * What IS directional is the announcement that causes a link to form.
 * `Replication` links a local replica outward when a peer's ref appears in the
 * local [LocationRegistry] (`registry.onPublish { linkOut(it) }`), and that ref
 * appears only because the peer announced it. So:
 *
 * - the listener not *hearing* the dialer's announcements ⇒ the listener never
 *   links out ⇒ **no L→D gossip**;
 * - the dialer still hearing the listener's ⇒ the dialer links out ⇒ **D→L
 *   gossip intact**.
 *
 * That is expressible entirely at the injection seam, with public kernel API
 * and no kernel or `:wire` main-source change — which is the point of the seam
 * ([MirrorTransport]), and mirrors how [civictech.demo.beadsmirror.projector.SeededDefects]
 * deliberately does not exist on `BeadsMirrorConfig`: a defective wiring must
 * not be reachable from a running app.
 *
 * ## The mechanism, exactly
 *
 * A [Peering.Side] carries ONE registry, used for three things: announcing its
 * `localRefs` outward, delivering the peer's inbound frames, and installing the
 * peer's announced refs as [LocationRegistry.Remote]. This decorator hands the
 * transport a side built on a **decoy** registry (and a decoy bridge host, so
 * the connection's mirror and ingress cells resolve there), then wires exactly
 * one of the three back to the node's real registry:
 *
 * - **real → decoy, local publishes**: every ref the node publishes locally is
 *   re-published into the decoy against the same [ManagedHost]. So the
 *   listener still announces its replicas to the dialer (the dialer links out,
 *   D→L lives), and the dialer's inbound deltas, delivered through the decoy,
 *   still resolve to the real host and reach the real cells.
 * - **decoy → real, mirrored remotes**: *not* wired. The dialer's announced
 *   refs land in the decoy and stop there, so the node's real registry — the
 *   one `Replication` watches — never sees them, and `replicasOf` never grows
 *   past the local replica. No link is ever attempted, so nothing is dropped
 *   mid-flight and no `Owned`/`Leased` payload is at stake: the defect is an
 *   absence of a link, not a silent discard on one.
 *
 * The hook registrations are deliberately never closed: this object lives
 * exactly as long as the rig that failed on purpose.
 */
private class DeafListenerTransport(
    private val delegate: MirrorTransport = WsMirrorTransport(reconnectBackoff = { 10L }),
) : MirrorTransport {

    /** The listening end — the deaf one. */
    override fun listen(requestedWsPort: Int, side: Peering.Side): MirrorLink =
        delegate.listen(requestedWsPort, deafened(side))

    /** The dialing end, untouched: it hears the listener and links out normally. */
    override fun dial(uri: String, side: Peering.Side): MirrorLink = delegate.dial(uri, side)

    override fun partition() = delegate.partition()

    override fun heal() = delegate.heal()

    private fun deafened(real: Peering.Side): Peering.Side {
        val decoy = LocationRegistry()
        val forward = { ref: CellRef -> real.registry.locate(ref)?.let { decoy.publish(ref, it) } }
        real.registry.onLocalPublish { forward(it) }
        real.registry.localRefs().forEach { forward(it) }
        return Peering.Side(
            registry = decoy,
            bridgeHost = ManagedHost(registry = decoy),
            peer = real.peer,
            allow = real.allow,
        )
    }
}
