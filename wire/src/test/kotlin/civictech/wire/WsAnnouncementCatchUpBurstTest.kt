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
 * - **concurrency** — half the refs are spawned from a *separate* thread that is
 *   released at the moment the dialer connects, so their `publish` is at least
 *   *intended* to land in the window `announceTo` closes by registering
 *   `onLocalPublish` before it sweeps. Every ref must arrive through exactly one
 *   of the two paths; a ref that falls between them is the defect.
 *
 *   **Measured, review of computenet-dqy.40: today it does not reach that
 *   window.** Deleting the `localRefs()` sweep from `Peering.announceTo`
 *   entirely loses **0/40 refs per iteration, in all 10 iterations** — so every
 *   ref of *both* halves, racing included, is delivered by the sweep and none by
 *   the hook. (Correspondingly, dropping 1 in 5 `onLocalPublish` announcements
 *   is invisible here, while dropping 1 in 7 sweep announcements fails all 10
 *   iterations.) The racing publishes are in-memory enqueues and beat the socket
 *   round trip that gates the peer's `announceTo`, so they land *before* the
 *   sweep rather than beside it. The honest reading: this arm adds volume to the
 *   sweep, and the loss bound this probe buys is a bound on the **sweep** only,
 *   not on the register-then-sweep handover. Making the arm genuinely race the
 *   handover is computenet-dqy.45.
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
            val failures: List<Failure>,
            val latencies: LongArray,
        ) {
            fun render(): String = buildString {
                appendLine(
                    "catch-up burst: ${failures.size} failed iteration(s) in $iterations iterations " +
                        "($refsAnnounced refs announced)"
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

        private class Stack {
            val registry = LocationRegistry()
            val host = ManagedHost(registry = registry)
            val bridgeHost = ManagedHost(registry = registry)
            val side = Peering.Side(registry, bridgeHost)
        }

        fun burst(iterations: Int, refsPerIteration: Int, progressEvery: Int = 0): Report {
            val failures = mutableListOf<Failure>()
            val latencies = mutableListOf<Long>()
            var announced = 0L
            repeat(iterations) { i ->
                val server = Stack()
                val client = Stack()
                val quiet = List(refsPerIteration / 2) { CollectingCell() }
                val racing = List(refsPerIteration - quiet.size) { CollectingCell() }
                quiet.forEach { server.host.managementInlet.call.spawn(it) }

                val release = CountDownLatch(1)
                val spawner = Thread {
                    release.await()
                    racing.forEach { server.host.managementInlet.call.spawn(it) }
                }.apply { isDaemon = true; name = "burst-spawner-$i"; start() }

                val listener = WsTransport.listen(0, server.side)
                val connection = try {
                    release.countDown() // the racing publishes land while the hello crosses
                    WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side)
                } catch (t: Throwable) {
                    runCatching { listener.stop(1000) }
                    throw t
                }
                try {
                    spawner.join(LOST_AFTER_MS)
                    // What the server actually holds is the contract: a spawn is
                    // asynchronous, so the set to be announced is the registry's,
                    // not the test's wish list.
                    val expected = (quiet + racing).map { it.ref }.toSet()
                    val start = System.currentTimeMillis()
                    fun missing(): Set<CellRef> {
                        val local = server.registry.localRefs()
                        return expected.filter { it in local && client.registry.location(it) !is LocationRegistry.Remote }
                            .toSet()
                    }
                    while (missing().isNotEmpty() && System.currentTimeMillis() - start < EXPECT_WITHIN_MS) {
                        Thread.sleep(1)
                    }
                    if (missing().isEmpty()) {
                        latencies += System.currentTimeMillis() - start
                        announced += expected.size
                        return@repeat
                    }
                    while (missing().isNotEmpty() && System.currentTimeMillis() - start < LOST_AFTER_MS) {
                        Thread.sleep(10)
                    }
                    val lost = missing()
                    announced += expected.size
                    if (lost.isNotEmpty()) {
                        failures += Failure(
                            iteration = i,
                            arrived = expected.size - lost.size,
                            expected = expected.size,
                            diagnostics = diagnose(lost, server.registry, client.registry),
                        )
                    } else {
                        latencies += System.currentTimeMillis() - start
                    }
                } finally {
                    connection.shutdown()
                    runCatching { listener.stop(1000) }
                }
                if (progressEvery > 0 && (i + 1) % progressEvery == 0) {
                    System.err.println("[burst] ${i + 1}/$iterations refs=$announced failures=${failures.size}")
                }
            }
            return Report(iterations, announced, failures, latencies.toLongArray())
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
            server: LocationRegistry,
            client: LocationRegistry,
        ): String = buildString {
            appendLine("  lost refs (${lost.size}): ${lost.take(5)}${if (lost.size > 5) " …" else ""}")
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
