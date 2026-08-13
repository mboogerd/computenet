package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID

/**
 * computenet-dqy.40's targeted probe on the surface that bead names as the
 * suspect: `Peering.announceTo`'s `localRefs()` catch-up sweep.
 *
 * ## What it attacks, and why this shape rather than more of the old one
 *
 * [WsAnnouncementStressTest] drives the catch-up shape **one ref at a time**:
 * a single collector is spawned before the listener, so the sweep it exercises
 * is a sweep of a set whose interesting member is already installed by the time
 * the peer's hello arrives, milliseconds later. It found the Linux loss once in
 * ~39,000 awaits (computenet-dqy.38) and cannot be pointed any harder at the
 * sweep, because there is nothing in it to race.
 *
 * This one makes the sweep race by construction, in the two ways the sweep can
 * be raced at all:
 *
 * - **volume** — [REFS_PER_ITERATION] refs are published before the peering
 *   exists, so a *single* catch-up burst announces a whole batch. A truncation
 *   (a sweep that stops early, a burst that loses its tail on either side of the
 *   wire) is then visible as "n of N arrived" rather than as a coin flip on one
 *   ref. The Linux failure looked exactly like a truncation — the client held
 *   one `Remote` when the server had three local refs to announce — and one ref
 *   per iteration cannot tell a truncation from a loss.
 * - **concurrency** — half the refs are published from a *separate* thread that
 *   is released from **inside `announceTo`'s register-then-sweep window**: the
 *   kernel seam `Peering.Side.onCatchUpWindowOpen` fires after the
 *   `onLocalPublish` hook is installed and before the `localRefs()` sweep runs,
 *   and that is what unblocks the spawner. Their installation into the registry
 *   therefore runs concurrently with the sweep, which is exactly the handover
 *   `announceTo`'s ordering argument rests on: a publish is either seen by the
 *   sweep or delivered by the hook, never neither.
 *
 *   **Why the seam, and why timing it against connect does not work**
 *   (computenet-dqy.45, measured). The previous shape released the spawner at
 *   the moment the dialer connected. A local publish is an in-memory enqueue on
 *   the server host, while the peer's `announceTo` is gated on a socket round
 *   trip (the client's hello) — so the enqueues always won and every racing ref
 *   was already `Local` when the sweep read. Deleting the sweep then lost
 *   **40/40 refs in 10/10 iterations**: both halves travelled the sweep and the
 *   hook was entirely unobserved. No widened await or retry can move that
 *   ordering; only releasing the publishes from something ordered against the
 *   *server's* `announceTo` can.
 *
 * ## Which path each measured bound covers
 *
 * The two paths are bounded separately, and the figures must NOT be pooled:
 *
 * - **The `localRefs()` sweep**: 120,000 refs announced with 0 losses
 *   (computenet-dqy.40's long run, made on the *pre-seam* shape where — as
 *   above — every ref of both halves travelled the sweep). Rule of three: under
 *   ~2.5e-5 loss per ref. That headline figure bounds the sweep and nothing
 *   else; it never touched the `onLocalPublish` path.
 * - **The register-then-sweep handover** (the `onLocalPublish` leg): bounded
 *   only by runs of *this* shape, and now bounded at the sweep's own size —
 *   **120,000 refs released inside the window with 0 losses** (computenet-dqy.46,
 *   2026-08-13, macOS arm64 / JBR 25.0.2, 6,000 iterations x 20 racing refs;
 *   240,000 refs announced in total, 25.2s wall), and again at 25.4s in the
 *   review's independent re-run on the same host. Rule of three: under ~2.5e-5
 *   loss per racing ref, the same order as the sweep's. Note the JDK: the sweep's
 *   own 120,000 was taken on JDK 21.0.11, so the two figures are the same size on
 *   different runtimes, which is a caveat on comparing them, not on either one.
 *   The command is the whole cost:
 *
 *       java -cp <:wire test runtime classpath> \
 *         civictech.wire.WsAnnouncementCatchUpBurstTestKt 6000 40
 *
 *   The bound above is **macOS only**, and that is the open gap: the loss this
 *   family chases (computenet-dqy.38) was seen on Linux. No *long-run* Linux
 *   figure exists for this path — the container route
 *   (`scripts/flake-loop/run-linux-loop.sh`) needs a running Docker daemon, and
 *   on the machine this was measured the daemon could not be brought up
 *   unattended. What Linux evidence there is, is the fast lane's own: this test
 *   runs in `build-test-fast` on `ubuntu-latest` and contributes 200 racing refs
 *   per CI run (green there on PR #84's run, in the same job where
 *   [WsAnnouncementStressTest] lost an announcement). That is four orders of
 *   magnitude short of the bound above. Anyone with a Linux box or a live daemon
 *   should run the same command there and add the number here.
 *
 *   The **committed fast lane stays at 10 iterations x 20 racing refs = 200
 *   refs** (rule of three, under ~1.5e-2 per ref) — deliberately, on measured
 *   cost. This test's own JUnit-reported duration is 0.242s at 10 iterations,
 *   0.741s at 100, 1.209s at 200 (`./gradlew :wire:test --tests
 *   '*WsAnnouncementCatchUpBurstTest*' --rerun -Dwire.burst.iterations=N`,
 *   ~4.9ms marginal per iteration). Buying even 4,000 racing refs costs ~1s,
 *   the fast lane's stated ceiling, and still lands ~30x short of the sweep's
 *   bound: parity is what the long run above is for, not what the fast lane can
 *   be stretched into. What the fast lane is sized for is regression detection,
 *   and at 10 x 40 it already fails 9/10 and 10/10 against every mutation below.
 *   `-Dwire.burst.iterations` and [main] are how it is made strong on demand;
 *   the report prints the racing-ref count so any run says what it bounds.
 *
 * ## What is live, established by mutation of `Peering.announceTo`
 *
 * Not by argument (computenet-dqy.45, all at the fast-lane size, 10 x 40):
 *
 * - drop 1 in 5 `onLocalPublish` announcements -> **fails 9/10 iterations**,
 *   30-38 of 40 arriving, and *every* lost ref is a racing one. Before the seam
 *   this mutation was invisible (BUILD SUCCESSFUL).
 * - drop 1 in 7 sweep announcements -> **fails 10/10**, 38-39 of 40 arriving,
 *   and every lost ref is a quiet one. Sweep coverage is unchanged.
 * - delete the sweep entirely -> **fails 10/10 at exactly 20/40**, the quiet
 *   half lost and the racing half arriving. That is the split, measured: the
 *   quiet half rides the sweep, the racing half rides the hook. Before the seam
 *   the same mutation gave 0/40.
 *
 * All three were re-measured after computenet-dqy.40's per-ref `catchUp`
 * wrapper landed on the sweep (#83), because they had been taken against a
 * sweep where a failed send propagated. They still hold, at 10/10, 36/40 (every
 * lost ref racing), 10/10, 37/40 (every lost ref quiet), and 10/10 at exactly
 * 20/40 — the few-ref differences are the mutation's own arithmetic, not a
 * change of verdict. A fourth mutation checks the wrapper itself: making every
 * 7th sweep *send throw* is swallowed by `catchUp` and logged (30 `[Peering]
 * catch-up announcement failed` lines), and the probe still **fails 10/10** at
 * 37-39/40, naming the lost refs. So the isolation shrinks a failure's blast
 * radius — pre-#83 that throw also abandoned every ref behind it — without
 * making a lost ref quieter here: this probe asserts arrival at the client, and
 * a send that failed did not arrive however its exception was handled.
 *
 * The honest limit of the racing arm: its publishes are *issued* inside the
 * window and installed concurrently with the sweep, but they almost always miss
 * the sweep's `localRefs()` snapshot, so what they overwhelmingly exercise is
 * the hook leg of the handover rather than an install landing astride the
 * snapshot read. At fast-lane sizes the report's "already Local when it opened"
 * count is 0 in every iteration observed; the 6,000-iteration runs above put an
 * order of magnitude on it — **14 of 120,000 racing refs**, and **1 of 120,000**
 * in the review's re-run of the same command on the same host, were already
 * `Local` when the window opened, i.e. certainly swept. It is a race, so read
 * that as "of order 1e-5 to 1e-4", not as a rate either run pinned down. So the
 * racing arm does occasionally land on the other side of the snapshot, but at a
 * rate that makes the 120,000 above a bound on the *hook* leg and not on the
 * snapshot seam itself. A publish that fell between the two legs would still be caught — that
 * is the property under test — but the probe still cannot claim to have aimed
 * one at that seam.
 *
 * The probe also refuses to be vacuous: it fails if the seam never fires (the
 * peer never announced, so nothing was raced) and fails if any ref never
 * becomes `Local` on the server.
 *
 * A publish is asynchronous in both arms (`managementInlet.call.spawn` enqueues
 * on the host's scheduler), so the racing arm is genuinely racing the sweep and
 * the quiet arm is only *probably* installed before it — which is why the
 * assertion is over the refs the server registry actually holds as `Local` at
 * the end, not over the refs the test asked for.
 *
 * ## What a failure here says
 *
 * The awaited condition is the same one `WsAnnouncementStressTest` awaits: the
 * client registry resolves the ref to [LocationRegistry.Remote]. The diagnostics
 * are what this adds — on a miss it reports, from both sides:
 *
 * - which refs arrived and which did not, against what the *server* still holds
 *   as `Local` (so "the sweep never had it" is distinguishable from "the sweep
 *   had it and it did not arrive");
 * - the park depth of every ref the client publishes locally — its bridge
 *   ingress and its registry mirror. That is the blind spot in
 *   [WsAnnouncementStressTest]'s report: an announcement stuck at either of the
 *   two scheduler hops behind the socket is parked under the *mirror's* ref or
 *   the *ingress's* ref, never under the announced ref, so its
 *   "parked for awaited ref: 0" rules out nothing about a park.
 *
 * `-Dwire.burst.iterations=N` and `-Dwire.burst.refs=N` turn it into a
 * measurement instrument; [main] is the long-run entry point, kept out of the
 * Gradle test task for the reason [WsAnnouncementStressTest]'s is.
 */
class WsAnnouncementCatchUpBurstTest {

    @Test
    fun `every ref published before the peering arrives through the catch-up sweep`() {
        val report = burst(
            iterations = System.getProperty("wire.burst.iterations")?.toInt() ?: DEFAULT_ITERATIONS,
            refsPerIteration = System.getProperty("wire.burst.refs")?.toInt() ?: REFS_PER_ITERATION,
        )
        if (report.failures.isNotEmpty()) throw AssertionFailedError(report.render())
    }

    companion object {
        const val DEFAULT_ITERATIONS = 10

        /** Refs published before the peering exists — half quiet, half racing the sweep. */
        const val REFS_PER_ITERATION = 40

        /** The budget a real test's announcement await gives the catch-up burst. */
        const val EXPECT_WITHIN_MS = 5_000L

        /** Past this, refs that have not arrived are lost, not slow. */
        const val LOST_AFTER_MS = 15_000L

        class Failure(val iteration: Int, val arrived: Int, val expected: Int, val diagnostics: String)

        class Report(
            val iterations: Int,
            val refsAnnounced: Long,
            /** Refs released from inside the register-then-sweep window. */
            val racingRefs: Long,
            /** Of those, the ones already `Local` when the window opened — certainly swept. */
            val racingAlreadyLocal: Long,
            val failures: List<Failure>,
            val latencies: LongArray,
        ) {
            fun render(): String = buildString {
                appendLine(
                    "catch-up burst: ${failures.size} failed iteration(s) in $iterations iterations " +
                        "($refsAnnounced refs announced)"
                )
                appendLine(
                    "released inside the register-then-sweep window: $racingRefs refs, " +
                        "of which $racingAlreadyLocal were already Local when it opened (certainly swept)"
                )
                val sorted = latencies.sorted()
                if (sorted.isNotEmpty()) {
                    fun q(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
                    appendLine("full-burst arrival latency ms: p50=${q(0.5)} p99=${q(0.99)} max=${sorted.last()}")
                }
                failures.forEach { f ->
                    appendLine("--- iteration ${f.iteration}: ${f.arrived}/${f.expected} announced refs arrived")
                    append(f.diagnostics)
                }
            }
        }

        /** A cell whose only job is to have a ref that must be announced. */
        class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
            val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

            init {
                inlet.serve(object : Consumer<String> {
                    override fun provide(input: String) = Unit
                })
            }
        }

        private class Stack(onCatchUpWindowOpen: (() -> Unit)? = null) {
            val registry = LocationRegistry()
            val host = ManagedHost(registry = registry)
            val bridgeHost = ManagedHost(registry = registry)
            val side = Peering.Side(registry, bridgeHost, onCatchUpWindowOpen = onCatchUpWindowOpen)
        }

        fun burst(iterations: Int, refsPerIteration: Int, progressEvery: Int = 0): Report {
            val failures = mutableListOf<Failure>()
            val latencies = mutableListOf<Long>()
            var announced = 0L
            var racingRefsTotal = 0L
            var racingAlreadyLocal = 0L
            repeat(iterations) { i ->
                val quiet = List(refsPerIteration / 2) { CollectingCell() }
                val racing = List(refsPerIteration - quiet.size) { CollectingCell() }
                val racingRefs = racing.mapTo(mutableSetOf()) { it.ref }

                // The racing half is released from INSIDE announceTo's window —
                // between the onLocalPublish registration and the localRefs()
                // sweep — not from the dialer's connect. Timing it against
                // connect is what computenet-dqy.45 measured as missing the
                // window entirely: a local spawn is an in-memory enqueue and the
                // peer's announceTo is gated on a socket round trip, so those
                // publishes were always installed before the sweep read.
                val release = CountDownLatch(1)
                val windowOpened = CountDownLatch(1)
                val windowFired = AtomicBoolean(false)
                val localAtWindowOpen = AtomicInteger(0)
                lateinit var server: Stack
                server = Stack(onCatchUpWindowOpen = {
                    if (windowFired.compareAndSet(false, true)) {
                        release.countDown()
                        // Proxy split measurement, taken as close to the sweep's
                        // own read as a test can stand: a racing ref already
                        // Local here is certainly in the sweep; the rest are
                        // racing it. The authoritative account of which path
                        // carries them is the mutation evidence in the KDoc.
                        localAtWindowOpen.set(server.registry.localRefs().count { it in racingRefs })
                        windowOpened.countDown()
                    }
                })
                val client = Stack()
                quiet.forEach { server.host.managementInlet.call.spawn(it) }
                racingRefsTotal += racing.size

                val spawner = Thread {
                    release.await()
                    racing.forEach { server.host.managementInlet.call.spawn(it) }
                }.apply { isDaemon = true; name = "burst-spawner-$i"; start() }

                val listener = WsTransport.listen(0, server.side)
                val connection = try {
                    WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side)
                } catch (t: Throwable) {
                    release.countDown()
                    runCatching { listener.stop(1000) }
                    throw t
                }
                try {
                    // Vacuity guard 1: the seam must have fired. Without it the
                    // racing half is never published at all and the probe would
                    // assert over the quiet half alone, silently.
                    if (!windowOpened.await(LOST_AFTER_MS, TimeUnit.MILLISECONDS)) {
                        failures += Failure(
                            iteration = i,
                            arrived = 0,
                            expected = refsPerIteration,
                            diagnostics = "  announceTo's register-then-sweep window never opened: " +
                                "the peer never announced, so nothing was raced.\n",
                        )
                        return@repeat
                    }
                    racingAlreadyLocal += localAtWindowOpen.get()
                    spawner.join(LOST_AFTER_MS)
                    val expected = (quiet + racing).mapTo(mutableSetOf()) { it.ref }
                    // Vacuity guard 2: a spawn is asynchronous, so the set to be
                    // announced is the registry's, not the test's wish list — but
                    // it must eventually BE the wish list, or the assertion below
                    // is over a set the test never populated.
                    val installStart = System.currentTimeMillis()
                    while (!server.registry.localRefs().containsAll(expected) &&
                        System.currentTimeMillis() - installStart < LOST_AFTER_MS
                    ) Thread.sleep(1)
                    val neverLocal = expected - server.registry.localRefs()
                    if (neverLocal.isNotEmpty()) {
                        failures += Failure(
                            iteration = i,
                            arrived = 0,
                            expected = expected.size,
                            diagnostics = "  ${neverLocal.size} ref(s) never became Local on the SERVER, " +
                                "so they were never announced by either path: ${neverLocal.take(5)}\n",
                        )
                        return@repeat
                    }

                    val start = System.currentTimeMillis()
                    fun missing(): Set<CellRef> =
                        expected.filterTo(mutableSetOf()) { client.registry.location(it) !is LocationRegistry.Remote }
                    while (missing().isNotEmpty() && System.currentTimeMillis() - start < EXPECT_WITHIN_MS) {
                        Thread.sleep(1)
                    }
                    announced += expected.size
                    if (missing().isEmpty()) {
                        latencies += System.currentTimeMillis() - start
                        return@repeat
                    }
                    while (missing().isNotEmpty() && System.currentTimeMillis() - start < LOST_AFTER_MS) {
                        Thread.sleep(10)
                    }
                    val lost = missing()
                    if (lost.isNotEmpty()) {
                        failures += Failure(
                            iteration = i,
                            arrived = expected.size - lost.size,
                            expected = expected.size,
                            diagnostics = diagnose(lost, racingRefs, localAtWindowOpen.get(), server.registry, client.registry),
                        )
                    } else {
                        latencies += System.currentTimeMillis() - start
                    }
                } finally {
                    release.countDown()
                    connection.shutdown()
                    runCatching { listener.stop(1000) }
                }
                if (progressEvery > 0 && (i + 1) % progressEvery == 0) {
                    System.err.println("[burst] ${i + 1}/$iterations refs=$announced failures=${failures.size}")
                }
            }
            return Report(iterations, announced, racingRefsTotal, racingAlreadyLocal, failures, latencies.toLongArray())
        }

        /**
         * The two-sided account of a miss. The park depths are the point: an
         * announcement stalled at either scheduler hop behind the socket parks
         * under the client's *own* local refs (its bridge ingress, its registry
         * mirror), which is where a stalled burst is visible and where
         * [WsAnnouncementStressTest]'s per-announced-ref park count cannot look.
         */
        private fun diagnose(
            lost: Set<CellRef>,
            racingRefs: Set<CellRef>,
            racingAlreadyLocal: Int,
            server: LocationRegistry,
            client: LocationRegistry,
        ): String = buildString {
            appendLine("  lost refs (${lost.size}): ${lost.take(5)}${if (lost.size > 5) " …" else ""}")
            appendLine(
                "  of the lost, ${lost.count { it in racingRefs }} were released inside the " +
                    "register-then-sweep window (${racingAlreadyLocal} of ${racingRefs.size} racing refs " +
                    "were already Local when it opened)"
            )
            appendLine("  server localRefs: ${server.localRefs().size}, client remoteRefs: ${client.remoteRefs().size}")
            appendLine("  all lost refs still Local on the server: ${lost.all { server.location(it) is LocationRegistry.Local }}")
            client.localRefs().forEach { ref ->
                appendLine("  client-local $ref: location=${client.location(ref)?.javaClass?.simpleName} parked=${client.parkedFor(ref).size}")
            }
        }
    }
}

/**
 * Long-run entry point: `java -cp <test runtime classpath>
 * civictech.wire.WsAnnouncementCatchUpBurstTestKt <iterations> [refs]`.
 */
fun main(args: Array<String>) {
    val iterations = args.getOrNull(0)?.toInt() ?: 200
    val refs = args.getOrNull(1)?.toInt() ?: WsAnnouncementCatchUpBurstTest.REFS_PER_ITERATION
    val report = WsAnnouncementCatchUpBurstTest.burst(iterations, refs, progressEvery = 25)
    println(report.render())
}
