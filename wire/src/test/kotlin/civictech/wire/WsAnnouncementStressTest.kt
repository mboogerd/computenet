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
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.URI
import java.util.UUID

/**
 * computenet-dqy.34's reproduction harness for "timed out awaiting: collector
 * announced".
 *
 * The defect this exists for is a ~1%-per-suite-run flake: two different tests
 * (`WsReconnectLoopBoundTest`, `WsTransportSmokeTest`) have been seen to time out
 * on an *announcement* await after their socket had already connected. A single
 * suite run exercises the announcement path about twice, so measuring the rate
 * through `./gradlew :wire:test` needs hundreds of Gradle runs to see one event
 * and can never separate 1% from 0.3%. This drives the same path thousands of
 * times inside one JVM instead, so the per-connection rate is measurable with
 * useful power in minutes.
 *
 * Both shapes the failures were seen in are exercised per iteration:
 *
 * - **catch-up**: the collector is published *before* the peering exists, so it
 *   reaches the dialer through `Peering.announceTo`'s `localRefs()` sweep. This
 *   is `WsReconnectLoopBoundTest`'s shape.
 * - **live**: the collector is published *after* `connect` returned, so it
 *   reaches the dialer through the `onLocalPublish` hook — or, if the listener
 *   has not processed the dialer's hello yet, through the later catch-up. This
 *   is `WsTransportSmokeTest`'s shape, and the interesting one: `connect`
 *   returns on socket open, while the hello exchange is still in flight.
 *
 * Every iteration that misses its await keeps waiting up to [LOST_AFTER_MS], so
 * the report separates a slow announcement from a lost one, and captures
 * everything the transport, the registry and the host schedulers wrote to
 * `System.err` during that iteration — the three places this path reports a
 * swallowed failure (`LocationRegistry.notify`, `WsListener.onError`,
 * `VirtualThreadScheduler`'s backstop).
 *
 * The `@Test` runs [DEFAULT_ITERATIONS] cycles so it stays a cheap regression
 * gate in the fast lane; `-Dwire.stress.iterations=N` (or `main`, below) turns
 * it into the measurement instrument.
 *
 * Do not over-trust the default gate: 25 iterations is 50 awaits, which catches
 * a regression that loses 1% of announcements only ~39% of the time (it would
 * catch a 10% one ~99.5% of the time). It is a smoke test at that size, and the
 * measurement power lives in the `-Dwire.stress.iterations` runs, not here.
 * Raising [DEFAULT_ITERATIONS] buys power at ~11ms per iteration — 0.28s at 25,
 * measured — so it is a fast-lane budget decision, not a technical limit.
 *
 * ## What this gate is worth on Linux CI
 *
 * Unlike [WsListenerAcceptRstTest] this test is deliberately **not** platform
 * scoped, and it is not vacuous anywhere: every iteration performs two real
 * announcement awaits against a real listener and fails if either is lost, so on
 * `ubuntu-latest` it is a genuine (if low-power) gate on the announcement path
 * against *any* loss mechanism.
 *
 * But it has no power against the *specific* mechanism computenet-dqy.34 named.
 * That mechanism is BSD-only — measured, see [WsListenerAcceptRstTest]'s "Platform
 * scope" — so on the Linux runners this test cannot observe it however many
 * iterations it runs, and its passing there is not evidence about it. It stays
 * unscoped because a regression that loses announcements for some *other* reason
 * would be caught on every platform, and that is worth having.
 */
class WsAnnouncementStressTest {

    @Test
    fun `the announcement path completes on every connection`() {
        val report = stress(iterations = System.getProperty("wire.stress.iterations")?.toInt() ?: DEFAULT_ITERATIONS)
        if (report.failures.isNotEmpty()) throw AssertionFailedError(report.render())
    }

    companion object {
        const val DEFAULT_ITERATIONS = 25

        /** Past this, an announcement that has not arrived is treated as lost, not slow. */
        const val LOST_AFTER_MS = 15_000L

        /** The budget the failing tests' own awaits gave the first announcement. */
        const val EXPECT_WITHIN_MS = 3_000L

        class Failure(
            val iteration: Int,
            val shape: String,
            val arrivedAfterMs: Long?,
            val diagnostics: String,
        )

        class Report(val iterations: Int, val awaits: Int, val failures: List<Failure>, val latencies: LongArray) {
            fun render(): String = buildString {
                appendLine("announcement path: ${failures.size} failure(s) in $awaits awaits over $iterations iterations")
                val sorted = latencies.sorted()
                if (sorted.isNotEmpty()) {
                    fun q(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
                    appendLine("arrival latency ms: p50=${q(0.5)} p99=${q(0.99)} max=${sorted.last()}")
                }
                failures.forEach { f ->
                    appendLine("--- iteration ${f.iteration} (${f.shape}): " +
                        (f.arrivedAfterMs?.let { "arrived late after ${it}ms" } ?: "never arrived within ${LOST_AFTER_MS}ms"))
                    appendLine(f.diagnostics)
                }
            }
        }

        private class Stack {
            val registry = LocationRegistry()
            val host = ManagedHost(registry = registry)
            val bridgeHost = ManagedHost(registry = registry)
            val side = Peering.Side(registry, bridgeHost)
        }

        class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
            val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

            init {
                inlet.serve(object : Consumer<String> {
                    override fun provide(input: String) = Unit
                })
            }
        }

        fun stress(iterations: Int, progressEvery: Int = 0): Report {
            val failures = mutableListOf<Failure>()
            val latencies = mutableListOf<Long>()
            var awaits = 0
            repeat(iterations) { i ->
                val captured = ByteArrayOutputStream()
                val realErr = System.err
                System.setErr(PrintStream(TeeStream(realErr, captured), true))
                try {
                    val server = Stack()
                    val client = Stack()
                    // catch-up shape: published before any peering exists
                    val early = CollectingCell()
                    server.host.managementInlet.call.spawn(early)
                    val listener = WsTransport.listen(0, server.side)
                    val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side)
                    try {
                        awaits++
                        observe(i, "catch-up", early.ref, client.registry, server.registry, connection, listener, captured, failures, latencies)
                        // live shape: published after connect returned, while the
                        // hello exchange may still be in flight
                        val late = CollectingCell()
                        server.host.managementInlet.call.spawn(late)
                        awaits++
                        observe(i, "live", late.ref, client.registry, server.registry, connection, listener, captured, failures, latencies)
                    } finally {
                        connection.shutdown()
                        runCatching { listener.stop(1000) }
                    }
                } finally {
                    System.setErr(realErr)
                }
                if (progressEvery > 0 && (i + 1) % progressEvery == 0) {
                    realErr.println("[stress] ${i + 1}/$iterations awaits=$awaits failures=${failures.size}")
                }
            }
            return Report(iterations, awaits, failures, latencies.toLongArray())
        }

        private fun observe(
            iteration: Int,
            shape: String,
            ref: CellRef,
            registry: LocationRegistry,
            server: LocationRegistry,
            connection: WsTransport.WsConnection,
            listener: WsTransport.WsListener,
            captured: ByteArrayOutputStream,
            failures: MutableList<Failure>,
            latencies: MutableList<Long>,
        ) {
            val start = System.currentTimeMillis()
            fun arrived() = registry.location(ref) is LocationRegistry.Remote
            while (!arrived() && System.currentTimeMillis() - start < EXPECT_WITHIN_MS) Thread.sleep(1)
            if (arrived()) {
                latencies += System.currentTimeMillis() - start
                return
            }
            // missed the budget the real tests give it: keep waiting, so the report
            // can say whether this was slow or lost
            while (!arrived() && System.currentTimeMillis() - start < LOST_AFTER_MS) Thread.sleep(10)
            val elapsed = System.currentTimeMillis() - start
            val late = if (arrived()) elapsed else null
            failures += Failure(iteration, shape, late, diagnose(ref, registry, server, connection, listener, captured))
        }

        /**
         * computenet-dqy.40 added the two-sided half of this report. The
         * 2026-08-12 Linux loss was diagnosed from the client's side alone, and
         * that left the two questions that decide where to look unanswerable:
         *
         * - **How much of the burst arrived?** A catch-up announces *every*
         *   local ref, and the server's local set here is three (the collector,
         *   plus the bridge ingress and the registry mirror the peering itself
         *   publishes into the same registry) — measured, and the client holds
         *   all three in a healthy iteration. The failing run held **one**, so
         *   the event was a burst that stopped, not a single announcement that
         *   went missing. Nothing in the old report said so; [expected] and
         *   [announced] below say it outright.
         *
         *   Two limits on that reading, measured in review of computenet-dqy.40
         *   and recorded so it is not requoted past them. **Three is the steady
         *   state, not an invariant**: the mirror and the ingress are published
         *   asynchronously around `announceTo`, and this report has been seen to
         *   render `server localRefs=2` when taken at t≈0. The 2026-08-12 record
         *   was taken 15s in, so three holds there. And **the event is not
         *   attributable to the sweep**: the one ref that did arrive was not the
         *   collector, so it was the mirror or the ingress — exactly the two refs
         *   that race the sweep and may travel the `onLocalPublish` hook instead.
         *   "A burst that stopped" is earned; "the sweep truncated" is not.
         * - **Is it lost or parked?** An announcement stalled at either
         *   scheduler hop behind the socket — the bridge ingress decode, then
         *   the delivery to the registry mirror — parks under the *client's own*
         *   local refs, never under the announced ref. So the old report's
         *   "parked for awaited ref: 0" ruled out nothing at all; the per-local-
         *   ref park depths below are where a stalled hop is visible.
         */
        private fun diagnose(
            ref: CellRef,
            registry: LocationRegistry,
            server: LocationRegistry,
            connection: WsTransport.WsConnection,
            listener: WsTransport.WsListener,
            captured: ByteArrayOutputStream,
        ): String = buildString {
            appendLine("  awaited ref: $ref")
            appendLine("  client location: ${registry.location(ref)}")
            appendLine("  awaited ref on the server: ${server.location(ref)}")
            appendLine("  announced: server localRefs=${server.localRefs().size} -> client remoteRefs=${registry.remoteRefs().size}")
            appendLine("  server localRefs: ${server.localRefs()}")
            appendLine("  client remoteRefs: ${registry.remoteRefs()}")
            appendLine("  client localRefs: ${registry.localRefs()}")
            appendLine("  parked for awaited ref: ${registry.parkedFor(ref).size}")
            // computenet-dqy.40: the two drops on this path that write nothing
            // to stderr, so "stderr: <silent>" no longer means "nothing was
            // dropped". Established by execution in
            // WsAnnouncementSilenceInventoryTest: every other failure here —
            // a throw out of onText (which truncates the catch-up sweep), a
            // failing publish hook, an unknown cell or port, the scheduler
            // backstop — does reach stderr, so these two plus "the delivery
            // never ran" are what a silent loss can be.
            appendLine(
                "  silent drops: client preHello=${connection.preHelloDrops} gate=${connection.refusedAnnouncements}" +
                    " / listener preHello=${listener.preHelloDrops} gate=${listener.refusedAnnouncements}",
            )
            registry.localRefs().forEach { local ->
                appendLine("  parked for client-local $local: ${registry.parkedFor(local).size}")
            }
            server.localRefs().forEach { local ->
                appendLine("  parked for server-local $local: ${server.parkedFor(local).size}")
            }
            val threads = Thread.getAllStackTraces().keys
                .filter { it.isAlive }
                .groupingBy { it.name.replace(Regex("[0-9a-f-]{8,}"), "*") }
                .eachCount()
                .entries.sortedByDescending { it.value }
                .take(12)
            appendLine("  live threads by name: $threads")
            val err = captured.toString().trim()
            appendLine(if (err.isEmpty()) "  stderr during this iteration: <silent>" else "  stderr during this iteration:\n$err")
        }

        private class TeeStream(private val a: OutputStream, private val b: OutputStream) : OutputStream() {
            override fun write(x: Int) {
                a.write(x); b.write(x)
            }

            override fun write(buf: ByteArray, off: Int, len: Int) {
                a.write(buf, off, len); b.write(buf, off, len)
            }

            override fun flush() {
                a.flush(); b.flush()
            }
        }
    }
}

/**
 * Standalone entry point for the long measurement runs: `java -cp <test runtime
 * classpath> civictech.wire.WsAnnouncementStressTestKt <iterations>`. Kept out of
 * the Gradle test task so a 5000-iteration measurement never lands in the fast
 * lane, and so a run's report survives the next run (the JUnit XML does not —
 * see computenet-dqy.34's diagnosability note).
 */
fun main(args: Array<String>) {
    val iterations = args.firstOrNull()?.toInt() ?: 1000
    val report = WsAnnouncementStressTest.stress(iterations, progressEvery = 50)
    println(report.render())
}
