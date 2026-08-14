package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.opentest4j.AssertionFailedError
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.SYNC
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
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
 * ## A long run must survive its own death (computenet-h6a)
 *
 * Two defects, both measured on 2026-08-13 and both fixed here, made the long
 * measurement runs lose exactly the evidence they were launched to collect.
 *
 * **Failures are written to disk when they are observed, not at the end.** The
 * report used to accumulate [Failure]s in memory and render them only after the
 * loop, so a run that died rendered nothing: one 25000-iteration arm reported
 * `failures=2` on its last progress line and both diagnostic blocks were
 * unrecoverable. Every failure now goes to `<artifacts>/failure-<n>-<shape>.txt`
 * through [ArtifactSink], opened with [SYNC], at the moment [observe] gives up on
 * it. `-Dwire.stress.injectFailureAt=1,7` (or `--inject-failure-at`) forces
 * synthetic failures through that same write, so the artifact path is
 * demonstrable without waiting for a rare event; injected records say so on their
 * first line and are counted separately, because an injected record is not an
 * observation of the defect.
 *
 * **The run is bounded, and it exits.** This harness retains about 176 KB per
 * iteration — measured from two heap sizes: OOM at 11250 iterations on a 1.94GiB
 * container-default heap and at 5525 on 1.00GiB — so a run long enough to matter
 * runs out of heap, and it did not *die* of that, it *hung*: the
 * `OutOfMemoryError` reached non-daemon WebSocket threads, `main` stopped writing,
 * and the container stayed `Up 2 hours` with no output and no exit code. So
 * [stress] watches the heap actually retained after the last collection
 * ([retainedHeapFraction]) and stops the loop at [DEFAULT_HEAP_CEILING] of max,
 * before the OOM rather than after it; [main] additionally installs an
 * `OutOfMemoryError` handler that `halt`s, and honours `--deadline-seconds`, so no
 * unattended caller can be left waiting on a wedged JVM. Exit codes are
 * [EXIT_OK]/[EXIT_FAILURES]/[EXIT_BOUNDED]/[EXIT_OOM]/[EXIT_DEADLINE]/[EXIT_CRASH].
 *
 * **Consequence for the measurement.** 100000 awaits is ~4x past where one
 * process dies, so it is not reachable in one JVM on a container-default heap
 * while that retention exists. `scripts/announcement-stress/run.sh` accumulates
 * the awaits across bounded processes instead, summing each process's on-disk
 * progress record — including a process that died — and that, not a single
 * process, is what computenet-dqy.40's ">= 100000 stress awaits" clause should be
 * read against.
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
        val report = stress(
            iterations = System.getProperty("wire.stress.iterations")?.toInt() ?: DEFAULT_ITERATIONS,
            sink = artifactSink(),
            injectFailuresAt = parseInjectFailuresAt(System.getProperty("wire.stress.injectFailureAt")),
        )
        if (report.failures.isNotEmpty()) throw AssertionFailedError(report.render())
    }

    /**
     * computenet-dqy.63: `-Dwire.stress.injectFailureAt` used to be read only by
     * [main], never by this `@Test`, so the KDoc and the injected-record banner
     * advertised a knob that did nothing through `./gradlew :wire:test`, CI, or
     * `SuiteLoop` — silently: no exception, no warning, just zero injected
     * failures. This pins the parsing [parseInjectFailuresAt] shares with [main]
     * against the two ways that regression could return: a malformed or unset
     * value must never be read as "inject at iteration 0" (that would redden
     * every fast-lane run with no `-D` in sight), and a *valid* value must not be
     * silently dropped either.
     */
    @Test
    fun `injectFailuresAt parsing tolerates unset, blank, and malformed values without ever defaulting to iteration 0`() {
        assertEquals(emptySet<Int>(), parseInjectFailuresAt(null))
        assertEquals(emptySet<Int>(), parseInjectFailuresAt(""))
        assertEquals(emptySet<Int>(), parseInjectFailuresAt("   "))
        assertEquals(emptySet<Int>(), parseInjectFailuresAt("not-a-number"))
        assertEquals(setOf(1, 7), parseInjectFailuresAt("1,7"))
        assertEquals(setOf(3, 5), parseInjectFailuresAt(" 3 , 5 "))
        // a malformed entry mixed with a valid one drops only the malformed one,
        // never substitutes 0 for it.
        assertEquals(setOf(7), parseInjectFailuresAt("garbage,7"))
    }

    /**
     * The end-to-end regression guard (computenet-dqy.63): invokes the actual
     * production `@Test` — not a stand-in — with the system properties an
     * operator would pass on the command line, and requires it to fail with an
     * injected record, both in the thrown message and in the on-disk artifact
     * [ArtifactSink] writes for it. `wire.stress.artifacts` is redirected to
     * [tempDir] so this always-triggering injection never leaves a file under
     * the shared `build/announcement-stress` that a later reader could mistake
     * for a real occurrence (the trap computenet-ba27 is about).
     *
     * Checked in two places rather than one because they carry different text:
     * [Report.render] (the thrown [AssertionFailedError]'s message) says
     * "injected synthetic failure(s)"; only the per-[Failure] artifact
     * [ArtifactSink] writes — via [Failure.render], not [Report.render] — carries
     * the literal "INJECTED SYNTHETIC FAILURE" banner the KDoc and this test's
     * name both point at, so asserting only on the thrown message would not
     * prove the banner is real.
     */
    @Test
    fun `-Dwire stress injectFailureAt makes the JUnit path fail with an injected record`(@TempDir tempDir: Path) {
        val priorInject = System.getProperty("wire.stress.injectFailureAt")
        val priorIterations = System.getProperty("wire.stress.iterations")
        val priorArtifacts = System.getProperty("wire.stress.artifacts")
        System.setProperty("wire.stress.injectFailureAt", "0")
        System.setProperty("wire.stress.iterations", "1")
        System.setProperty("wire.stress.artifacts", tempDir.toString())
        try {
            val failure = assertThrows<AssertionFailedError> {
                `the announcement path completes on every connection`()
            }
            assertTrue(
                failure.message?.contains("injected synthetic failure") == true,
                "expected the injected-failure summary in: ${failure.message}",
            )
            val artifactFiles = Files.walk(tempDir).use { it.filter(Files::isRegularFile).toList() }
            assertTrue(
                artifactFiles.any { Files.readString(it).contains("INJECTED SYNTHETIC FAILURE") },
                "expected an on-disk artifact under $tempDir carrying the INJECTED SYNTHETIC FAILURE banner, found: $artifactFiles",
            )
        } finally {
            fun restore(key: String, prior: String?) {
                if (prior == null) System.clearProperty(key) else System.setProperty(key, prior)
            }
            restore("wire.stress.injectFailureAt", priorInject)
            restore("wire.stress.iterations", priorIterations)
            restore("wire.stress.artifacts", priorArtifacts)
        }
    }

    /**
     * computenet-ba27: the guard for the property computenet-h6a delivered — a
     * failure is on disk *before* [observe] returns, not accumulated in memory and
     * rendered after the loop. That property is one line of placement, and nothing
     * failed if a refactor moved it: the fast lane's 25 iterations never fail, so
     * they never write anything, and `:wire:test` stayed green either way. The next
     * long measurement run is where it would have surfaced, which is the two hours
     * this bead family has already paid once.
     *
     * One injected iteration makes the ordering checkable without concurrency:
     * [stress] has returned, so every write it was going to do has happened, and if
     * the write lived after the loop instead the artifact would still not exist.
     * Deliberately asserts nothing about timing and never races the loop to catch
     * absent-then-present.
     *
     * The last assertion is worth as much as the first. An injected record is
     * manufactured, and this bead family has been burned by one being requoted later
     * as evidence of the real event — so the record must be on disk *and* counted as
     * synthetic, never as a reproduction.
     *
     * `@TempDir` keeps the always-triggering injection out of the shared
     * `build/announcement-stress`, which a green fast-lane run leaves nonexistent.
     */
    @Test
    fun `an observed failure is written to disk before stress returns, and is counted as injected rather than observed`(
        @TempDir tempDir: Path,
    ) {
        val report = stress(iterations = 1, injectFailuresAt = setOf(0), sink = artifactSink(tempDir))

        val record = tempDir.resolve("failure-000000-catch-up-injected.txt")
        assertTrue(
            Files.isRegularFile(record),
            "expected the per-failure record on disk once stress() returned, found: " +
                Files.walk(tempDir).use { it.filter(Files::isRegularFile).toList() },
        )
        assertEquals(
            "INJECTED SYNTHETIC FAILURE (--inject-failure-at / -Dwire.stress.injectFailureAt): this record " +
                "was manufactured to exercise the artifact path and is NOT an observation of a lost " +
                "announcement. The diagnostics below are a real snapshot of a healthy iteration.",
            Files.readString(record).lineSequence().first(),
            "the first line of $record must be the injected-synthetic-failure banner",
        )

        val tsv = tempDir.resolve("failures.tsv")
        assertTrue(Files.isRegularFile(tsv), "expected $tsv alongside the per-failure record")
        val rows = Files.readAllLines(tsv).filter { it.isNotBlank() }
        assertTrue(
            rows.any { it.split("\t").getOrNull(3) == "injected" },
            "expected a failures.tsv row whose fourth column is 'injected', found: $rows",
        )

        assertEquals(0, report.observedFailures, "an injected record must never be counted as a reproduction")
        assertEquals(2, report.injectedFailures, "both shapes of the injected iteration should be recorded")
    }

    companion object {
        const val DEFAULT_ITERATIONS = 25

        /** Past this, an announcement that has not arrived is treated as lost, not slow. */
        const val LOST_AFTER_MS = 15_000L

        /** The budget the failing tests' own awaits gave the first announcement. */
        const val EXPECT_WITHIN_MS = 3_000L

        /**
         * Stop the loop when this fraction of max heap is still retained after a
         * collection. Chosen with a wide margin rather than tuned: at ~176 KB
         * retained per iteration and ~11ms per iteration (both measured), the heap
         * grows ~16 KB per millisecond, so the last 20% of a 1GiB heap is some
         * 13 seconds of headroom — thousands of times the interval between two
         * checks, which is one iteration.
         */
        const val DEFAULT_HEAP_CEILING = 0.80

        /** The whole run finished the iterations it was asked for, with no failures. */
        const val EXIT_OK = 0

        /** At least one non-injected failure was observed. Artifacts are on disk. */
        const val EXIT_FAILURES = 2

        /**
         * Stopped early at the heap ceiling (or another bound) with no failures —
         * resume in a fresh process. **Not a discriminator, so do not key on it:**
         * `-XX:+ExitOnOutOfMemoryError`, which `run.sh` always passes, also exits 3
         * (measured in review, 3/3). `result.tsv` presence is what separates a
         * ceiling stop from a death, and that is what `run.sh` reads.
         */
        const val EXIT_BOUNDED = 3

        /**
         * `OutOfMemoryError` reached a thread and [installHaltOnOutOfMemory] got to
         * run. **Best effort, not the normal OOM status:** the handler must write and
         * `halt` with no heap left, and measured in review (`-Xmx256m --heap-ceiling
         * 0`, JDK 26 macOS aarch64) it won that race 1 of 3 times; the others ended
         * `OutOfMemoryError thrown from the UncaughtExceptionHandler in thread
         * "main"` and exited 1. Preallocating its stream was tried and did not help
         * (0/3). The bound guarantees *termination*, not a status — those runs all
         * exited in under 25s instead of hanging.
         */
        const val EXIT_OOM = 70

        /** `--deadline-seconds` elapsed. Halted rather than left hanging. */
        const val EXIT_DEADLINE = 71

        /** Any other throwable out of the run. */
        const val EXIT_CRASH = 72

        class Failure(
            val iteration: Int,
            val shape: String,
            val arrivedAfterMs: Long?,
            val diagnostics: String,
            /** True when `--inject-failure-at` manufactured this record; not an observation of the defect. */
            val injected: Boolean = false,
        ) {
            fun headline(): String = "iteration $iteration ($shape): " + when {
                injected -> "forced by --inject-failure-at; the await was never given a chance to arrive"
                arrivedAfterMs != null -> "arrived late after ${arrivedAfterMs}ms"
                else -> "never arrived within ${LOST_AFTER_MS}ms"
            }

            fun render(): String = buildString {
                if (injected) {
                    appendLine(
                        "INJECTED SYNTHETIC FAILURE (--inject-failure-at / -Dwire.stress.injectFailureAt): this record " +
                            "was manufactured to exercise the artifact path and is NOT an observation of a lost " +
                            "announcement. The diagnostics below are a real snapshot of a healthy iteration.",
                    )
                }
                appendLine(headline())
                append(diagnostics)
            }
        }

        /**
         * Failure artifacts, each written the moment its failure is observed.
         *
         * computenet-h6a: the previous design accumulated failures in memory and
         * rendered them after the loop, so a run that died lost every diagnostic it
         * had collected — measured, on a 25000-iteration arm that had already found
         * two. Files are opened with [SYNC] so the record is on the device, not just
         * in this process's buffers, before the write returns; directories are
         * created lazily so a clean fast-lane run leaves nothing behind.
         */
        class ArtifactSink(val dir: Path) {

            /** Written as `<dir>/failure-<iteration>-<shape>.txt`, plus a row in `failures.tsv`. */
            fun failure(f: Failure): Path {
                val slug = f.shape.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
                val file = dir.resolve("failure-%06d-%s.txt".format(f.iteration, slug))
                write(file, f.render(), append = false)
                write(
                    dir.resolve("failures.tsv"),
                    "${f.iteration}\t${f.shape}\t${f.arrivedAfterMs ?: -1}\t${if (f.injected) "injected" else "observed"}\t${file.fileName}\n",
                    append = true,
                )
                return file
            }

            /** One row per progress tick, so a process that dies still reports how far it got. */
            fun progress(line: String) = write(dir.resolve("progress.tsv"), line, append = true)

            fun note(name: String, text: String) = write(dir.resolve(name), text, append = false)

            private fun write(file: Path, text: String, append: Boolean) {
                Files.createDirectories(file.parent)
                val options = if (append) arrayOf(CREATE, WRITE, APPEND, SYNC) else arrayOf(CREATE, WRITE, TRUNCATE_EXISTING, SYNC)
                Files.newOutputStream(file, *options).use { it.write(text.toByteArray()) }
            }
        }

        /**
         * The sink a run writes to: an explicit directory, else
         * [defaultArtifactRoot] plus a per-run subdirectory. A factory rather than a
         * bare constructor call because a class nested in a companion object is not
         * reachable as `WsAnnouncementStressTest.ArtifactSink` from outside it.
         */
        fun artifactSink(dir: Path? = null): ArtifactSink =
            ArtifactSink(dir ?: defaultArtifactRoot().resolve(runId()))

        fun defaultArtifactRoot(): Path = Paths.get(
            System.getProperty("wire.stress.artifacts")
                ?: System.getenv("WIRE_STRESS_ARTIFACTS")
                ?: "build/announcement-stress",
        )

        fun runId(): String = "%d-%s".format(System.currentTimeMillis(), ProcessHandle.current().pid())

        /**
         * Parses the comma-separated iteration list `--inject-failure-at` /
         * `-Dwire.stress.injectFailureAt` accept. Shared by the `@Test` and
         * [main] (computenet-dqy.63) so the two entry points cannot read the
         * same knob two different ways.
         *
         * Unset, blank, and unparsable entries are all silently dropped rather
         * than thrown, matching the parsing this replaced: a malformed value
         * must never be read as "inject at iteration 0", or an unset/empty
         * property would turn into an injection and redden the fast lane for
         * everyone. A comma list with one bad entry keeps the good ones rather
         * than failing the whole run — this knob exists to unblock unattended
         * long measurements, not to add a new way for them to crash.
         */
        fun parseInjectFailuresAt(raw: String?): Set<Int> =
            raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()

        /**
         * The fraction of max heap still retained *after the last collection*, or
         * null if the JVM does not report collection usage.
         *
         * `Runtime.totalMemory - freeMemory` is not usable for a bound: it counts
         * garbage that has not been collected yet, so it reads near the ceiling on
         * a healthy allocating loop. `MemoryPoolMXBean.getCollectionUsage()` is the
         * occupancy each heap pool was left at by the most recent collection of
         * that pool, which is what "retained" means.
         */
        fun retainedHeapFraction(): Double? {
            val max = ManagementFactory.getMemoryMXBean().heapMemoryUsage.max
            if (max <= 0L) return null
            var used = 0L
            var reported = false
            ManagementFactory.getMemoryPoolMXBeans().forEach { pool ->
                if (pool.type != MemoryType.HEAP) return@forEach
                val usage = pool.collectionUsage ?: return@forEach
                reported = true
                used += usage.used
            }
            return if (reported) used.toDouble() / max else null
        }

        class Report(
            val iterations: Int,
            val awaits: Int,
            val failures: List<Failure>,
            val latencies: LongArray,
            /** Non-null when the loop stopped before [requested] — the bound that stopped it. */
            val stopReason: String? = null,
            val artifacts: Path? = null,
            /** What the caller asked for; differs from [iterations] exactly when a bound stopped the loop. */
            val requested: Int = iterations,
        ) {
            val observedFailures: Int get() = failures.count { !it.injected }
            val injectedFailures: Int get() = failures.count { it.injected }

            fun render(): String = buildString {
                appendLine("announcement path: $observedFailures failure(s) in $awaits awaits over $iterations iterations")
                if (injectedFailures > 0) appendLine("plus $injectedFailures injected synthetic failure(s), which measure nothing")
                if (stopReason != null) appendLine("STOPPED EARLY: $stopReason")
                if (artifacts != null) appendLine("per-failure artifacts: $artifacts")
                val sorted = latencies.sorted()
                if (sorted.isNotEmpty()) {
                    fun q(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
                    appendLine("arrival latency ms: p50=${q(0.5)} p99=${q(0.99)} max=${sorted.last()}")
                }
                failures.forEach { f ->
                    appendLine("--- ${f.headline()}")
                    appendLine(f.diagnostics)
                }
            }

            /** Machine-readable, one `key\tvalue` per line, for the accumulating runner. */
            fun resultRows(): String = buildString {
                appendLine("iterations_requested\t$requested")
                appendLine("iterations_run\t$iterations")
                appendLine("awaits\t$awaits")
                appendLine("failures_observed\t$observedFailures")
                appendLine("failures_injected\t$injectedFailures")
                appendLine("stop_reason\t${stopReason ?: "completed"}")
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

        /**
         * @param sink where each failure is written the moment it is observed.
         * @param injectFailuresAt iterations whose awaits are forced to fail, to exercise
         *   [ArtifactSink] on demand. The forced record travels the same code as a real one.
         * @param heapCeiling stop the loop when [retainedHeapFraction] reaches this; 0 disables.
         */
        fun stress(
            iterations: Int,
            progressEvery: Int = 0,
            sink: ArtifactSink? = null,
            injectFailuresAt: Set<Int> = emptySet(),
            heapCeiling: Double = 0.0,
        ): Report {
            val failures = mutableListOf<Failure>()
            val latencies = mutableListOf<Long>()
            var awaits = 0
            var stopReason: String? = null
            var ran = 0
            for (i in 0 until iterations) {
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
                    val inject = i in injectFailuresAt
                    try {
                        awaits++
                        observe(
                            i, "catch-up", early.ref, client.registry, server.registry,
                            client.bridgeHost, server.bridgeHost,
                            connection, listener, captured, failures, latencies, sink, inject,
                        )
                        // live shape: published after connect returned, while the
                        // hello exchange may still be in flight
                        val late = CollectingCell()
                        server.host.managementInlet.call.spawn(late)
                        awaits++
                        observe(
                            i, "live", late.ref, client.registry, server.registry,
                            client.bridgeHost, server.bridgeHost,
                            connection, listener, captured, failures, latencies, sink, inject,
                        )
                    } finally {
                        connection.shutdown()
                        runCatching { listener.stop(1000) }
                    }
                } finally {
                    System.setErr(realErr)
                }
                ran = i + 1
                if (progressEvery > 0 && ran % progressEvery == 0) {
                    val line = "[stress] $ran/$iterations awaits=$awaits failures=${failures.count { !it.injected }}" +
                        (retainedHeapFraction()?.let { " heapRetained=%.1f%%".format(it * 100) } ?: "")
                    realErr.println(line)
                    sink?.progress("$ran\t$awaits\t${failures.count { !it.injected }}\t${failures.count { it.injected }}\n")
                }
                // computenet-h6a: the bound. This loop retains ~176 KB per
                // iteration, and the OOM it eventually hits does not kill the
                // JVM — non-daemon WebSocket threads keep it alive with the heap
                // gone, so the process hangs instead of exiting. Stop before it.
                if (heapCeiling > 0.0) {
                    val retained = retainedHeapFraction()
                    if (retained != null && retained >= heapCeiling) {
                        stopReason = "heap ceiling at iteration $ran: %.1f%% of max heap (%d MiB) still retained after the last collection, ceiling %.1f%%"
                            .format(
                                retained * 100,
                                ManagementFactory.getMemoryMXBean().heapMemoryUsage.max / (1024 * 1024),
                                heapCeiling * 100,
                            )
                        break
                    }
                }
            }
            if (progressEvery > 0 && (stopReason != null || ran % progressEvery != 0)) {
                sink?.progress("$ran\t$awaits\t${failures.count { !it.injected }}\t${failures.count { it.injected }}\n")
            }
            return Report(ran, awaits, failures, latencies.toLongArray(), stopReason, sink?.dir, iterations)
        }

        private fun observe(
            iteration: Int,
            shape: String,
            ref: CellRef,
            registry: LocationRegistry,
            server: LocationRegistry,
            clientBridge: ManagedHost,
            serverBridge: ManagedHost,
            connection: WsTransport.WsConnection,
            listener: WsTransport.WsListener,
            captured: ByteArrayOutputStream,
            failures: MutableList<Failure>,
            latencies: MutableList<Long>,
            sink: ArtifactSink?,
            inject: Boolean,
        ) {
            val start = System.currentTimeMillis()
            // computenet-h6a: an injected failure is a real trip through this
            // function with the arrival predicate forced false and both budgets
            // collapsed to zero, so the record below — and the write that follows
            // it — is produced by exactly the code a real loss produces it with.
            // Its diagnostics are a truthful snapshot; only the verdict is forced.
            fun arrived() = !inject && registry.location(ref) is LocationRegistry.Remote
            val expectWithin = if (inject) 0L else EXPECT_WITHIN_MS
            val lostAfter = if (inject) 0L else LOST_AFTER_MS
            while (!arrived() && System.currentTimeMillis() - start < expectWithin) Thread.sleep(1)
            if (arrived()) {
                latencies += System.currentTimeMillis() - start
                return
            }
            // missed the budget the real tests give it: keep waiting, so the report
            // can say whether this was slow or lost
            while (!arrived() && System.currentTimeMillis() - start < lostAfter) Thread.sleep(10)
            val elapsed = System.currentTimeMillis() - start
            val late = if (arrived()) elapsed else null
            val failure = Failure(
                iteration = iteration,
                shape = if (inject) "$shape injected" else shape,
                arrivedAfterMs = late,
                diagnostics = diagnose(ref, registry, server, clientBridge, serverBridge, connection, listener, captured),
                injected = inject,
            )
            failures += failure
            // On disk BEFORE this function returns: a run that dies at any later
            // point keeps every failure it had already found (computenet-h6a).
            sink?.let { s ->
                try {
                    s.failure(failure)
                } catch (t: Throwable) {
                    // Never silent: a lost artifact is the defect this bead exists for.
                    System.err.println("[stress] FAILED TO WRITE FAILURE ARTIFACT under ${s.dir}: $t")
                }
            }
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
         * - **Did the delivery run at all?** (computenet-hdq.) Every park depth
         *   above is [LocationRegistry.parkedFor], which counts invocations
         *   parked *before* a host accepted them. An invocation a bridge host
         *   accepted — past `enqueueHostedInvocation`, staged in the attention
         *   scheduler — but never dispatched is parked nowhere any of those
         *   lines can see, and writes nothing to stderr, so it read exactly like
         *   an announcement that was never sent: zero everywhere, `<silent>`.
         *   The `staged` line below is that third outcome's instrument, taken on
         *   both bridge hosts because both hops behind the socket stage on the
         *   client's. It is a *depth*, not an attribution: a non-zero reading
         *   says work was accepted and had not run when the report was taken.
         * - **Was a frame produced, and did it cross?** (computenet-dqy.68.) The
         *   nine occurrences in run 31756952711 read **zero on all four of the
         *   above**, and the client held a strict *prefix* of the server's
         *   `localRefs()` order in every one of them, so a contiguous run of
         *   announcements stopped rather than individual refs going missing.
         *   (That order is this report's own iteration order, which equals the
         *   sweep's send order only for refs published before `announceTo` ran —
         *   the caveat two bullets up applies, and "the sweep truncated" is still
         *   not earned. The statistic does not need it: its null is order-blind.)
         *   Zero
         *   everywhere is compatible with three truncation points and those four
         *   lines cannot separate them: above the socket (no frame produced), at
         *   the socket (handed to java-websocket, never delivered), or below the
         *   peer's socket (delivered, lost before the mirror). The `frames` line
         *   is that cut: `sent` counts frames this side handed to the transport
         *   without the write throwing, `received` counts binary frames the peer
         *   routed into its ingress, and the socket out-queue flag is the
         *   `staged` depth's analogue one layer down — see
         *   [WsTransport.Session.framesSent] and `WsAnnouncementFrameCountTest`.
         */
        private fun diagnose(
            ref: CellRef,
            registry: LocationRegistry,
            server: LocationRegistry,
            clientBridge: ManagedHost,
            serverBridge: ManagedHost,
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
            // computenet-hdq: the depth parkedFor cannot see — work the bridge
            // host accepted whose dispatch task has not run. Per cell and total,
            // both sides. One snapshot per host, summed here rather than read
            // twice: stagedWorkTotal() takes its own snapshot, so calling both
            // accessors on a host with traffic still moving could print a total
            // that does not add up to the map beside it — in the one artifact a
            // future post-mortem has to be able to take literally.
            val clientStaged = clientBridge.stagedWorkDepth()
            val serverStaged = serverBridge.stagedWorkDepth()
            appendLine(
                "  staged (accepted, not yet dispatched): client bridge total=${clientStaged.values.sum()}" +
                    " $clientStaged / server bridge total=${serverStaged.values.sum()}" +
                    " $serverStaged",
            )
            // computenet-dqy.68: the fifth instrument. `staged` above says
            // whether the CLIENT's bridge accepted work; these say whether a
            // frame was handed to the socket at all, and whether the peer's
            // socket delivered it — the two ends the four zero-reading
            // instruments leave unmeasured. Healthy steady state in this shape is
            // server sent=3 / client received=3 (the server's three local refs)
            // and client sent=2 / listener received=2 (the client's two).
            appendLine(
                "  frames: server->client sent=${listener.framesSent} received=${connection.framesReceived}" +
                    " / client->server sent=${connection.framesSent} received=${listener.framesReceived}" +
                    "; socket out-queue non-empty: listener=${listener.socketHasBufferedData}" +
                    " client=${connection.socketHasBufferedData}",
            )
            // computenet-dqy.69: the repair for the reading above, and whether it
            // fired. `listener=true` on the out-queue line with 0 here is a
            // stranded frame the re-arm watchdog did NOT catch; non-zero says it
            // caught one, and is also announced on stderr with the token
            // `computenet-dqy.69 re-armed` — see
            // [WsTransport.WsListener.rearmedWriteDemands].
            appendLine("  write demands re-armed: listener=${listener.rearmedWriteDemands}")
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
 *
 * Flags, all optional (the bare positional iteration count still works):
 *
 * ```
 *   --iterations N          how many cycles to attempt (default 1000)
 *   --artifacts DIR         where per-failure records go (default build/announcement-stress/<runid>)
 *   --progress-every N      progress line + on-disk progress row cadence (default 50)
 *   --inject-failure-at L   comma-separated iteration numbers to force a failure at
 *   --heap-ceiling F        stop at this retained-heap fraction, 0 disables (default 0.80)
 *   --deadline-seconds N    halt the JVM if the run outlives this (default 0, off)
 * ```
 *
 * **This process always exits.** computenet-h6a measured the alternative: the
 * `OutOfMemoryError` this harness eventually provokes was delivered to a
 * non-daemon WebSocket thread, `main` stopped writing, and the container was
 * still `Up 2 hours` with no report and no exit status, which is how one
 * unattended session lost its slot. So the heap ceiling normally stops the loop
 * first; if an `OutOfMemoryError` happens anyway the handler installed below
 * *tries* to `halt` with [WsAnnouncementStressTest.EXIT_OOM] rather than unwinding
 * through code that would need to allocate; `--deadline-seconds` halts a wedged
 * run; and the last statement is an explicit exit so live WebSocket threads cannot
 * hold the JVM open after the measurement is done.
 *
 * What is guaranteed is that it terminates, not which status it terminates with: an
 * OOM was measured exiting 70, 1 and 3 depending on which mechanism fired first.
 * Read [WsAnnouncementStressTest.EXIT_OOM] and
 * [WsAnnouncementStressTest.EXIT_BOUNDED] before branching on `$?`; a caller that
 * needs to know whether the sample is short reads `result.tsv` instead.
 */
fun main(args: Array<String>) {
    installHaltOnOutOfMemory()

    fun flag(name: String): String? {
        val i = args.indexOf("--$name")
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    val iterations = flag("iterations")?.toInt() ?: args.firstOrNull()?.takeIf { !it.startsWith("--") }?.toInt() ?: 1000
    val progressEvery = flag("progress-every")?.toInt() ?: 50
    val heapCeiling = flag("heap-ceiling")?.toDouble()
        ?: System.getProperty("wire.stress.heapCeiling")?.toDouble()
        ?: WsAnnouncementStressTest.DEFAULT_HEAP_CEILING
    val inject = WsAnnouncementStressTest.parseInjectFailuresAt(
        flag("inject-failure-at") ?: System.getProperty("wire.stress.injectFailureAt"),
    )
    val deadlineSeconds = flag("deadline-seconds")?.toLong() ?: 0L

    val sink = WsAnnouncementStressTest.artifactSink(flag("artifacts")?.let { Paths.get(it) })
    val artifacts = sink.dir
    val maxHeapMib = ManagementFactory.getMemoryMXBean().heapMemoryUsage.max / (1024 * 1024)
    sink.note(
        "run.txt",
        buildString {
            appendLine("pid\t${ProcessHandle.current().pid()}")
            appendLine("started\t${java.time.Instant.now()}")
            appendLine("iterations_requested\t$iterations")
            appendLine("max_heap_mib\t$maxHeapMib")
            appendLine("heap_ceiling\t$heapCeiling")
            appendLine("inject_failure_at\t${inject.sorted().joinToString(",")}")
            appendLine("deadline_seconds\t$deadlineSeconds")
            appendLine("java\t${System.getProperty("java.version")} ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        },
    )
    System.err.println(
        "[stress] pid=${ProcessHandle.current().pid()} iterations=$iterations maxHeap=${maxHeapMib}MiB " +
            "heapCeiling=$heapCeiling artifacts=$artifacts inject=${inject.sorted()}",
    )

    if (deadlineSeconds > 0) {
        Thread {
            Thread.sleep(deadlineSeconds * 1000)
            runCatching { sink.note("deadline.txt", "halted after ${deadlineSeconds}s deadline\n") }
            writeToStderrFd("[stress] deadline of ${deadlineSeconds}s elapsed; halting\n")
            Runtime.getRuntime().halt(WsAnnouncementStressTest.EXIT_DEADLINE)
        }.apply { isDaemon = true; name = "stress-deadline" }.start()
    }

    val report = try {
        WsAnnouncementStressTest.stress(iterations, progressEvery, sink, inject, heapCeiling)
    } catch (t: Throwable) {
        // Artifacts for every failure found before this point are already on disk.
        if (t is OutOfMemoryError) {
            writeToStderrFd("[stress] OutOfMemoryError out of the run; halting with ${WsAnnouncementStressTest.EXIT_OOM}\n")
            Runtime.getRuntime().halt(WsAnnouncementStressTest.EXIT_OOM)
        }
        runCatching { sink.note("crash.txt", "$t\n${t.stackTraceToString()}") }
        t.printStackTrace()
        System.err.flush()
        Runtime.getRuntime().halt(WsAnnouncementStressTest.EXIT_CRASH)
        return
    }

    val rendered = report.render()
    println(rendered)
    runCatching { sink.note("summary.txt", rendered) }
    runCatching { sink.note("result.tsv", report.resultRows()) }
    System.out.flush()

    val code = when {
        report.observedFailures > 0 -> WsAnnouncementStressTest.EXIT_FAILURES
        report.stopReason != null -> WsAnnouncementStressTest.EXIT_BOUNDED
        else -> WsAnnouncementStressTest.EXIT_OK
    }
    // Explicit: live non-daemon WebSocket threads would otherwise hold this JVM open.
    kotlin.system.exitProcess(code)
}

/**
 * Halt on `OutOfMemoryError`, in any thread, without allocating.
 *
 * computenet-h6a measured both shapes of the hang this prevents: an OOM that
 * reached `main` ("Exception: java.lang.OutOfMemoryError thrown from the
 * UncaughtExceptionHandler in thread \"main\"" — the handler itself could not
 * allocate) and an OOM that reached only a `WebSocketConnectReadThread` while
 * `main` carried on and then stopped writing. Both left the JVM alive with no
 * exit code. The message bytes are built here, before the heap is gone, and the
 * handler only writes them to the stderr file descriptor and calls `halt`, which
 * skips shutdown hooks and any code that might need to allocate.
 */
private fun installHaltOnOutOfMemory() {
    val message = "[stress] OutOfMemoryError: halting with code ${WsAnnouncementStressTest.EXIT_OOM}\n".toByteArray()
    Thread.setDefaultUncaughtExceptionHandler { _, e ->
        if (e is OutOfMemoryError) {
            FileOutputStream(FileDescriptor.err).write(message)
            Runtime.getRuntime().halt(WsAnnouncementStressTest.EXIT_OOM)
        } else {
            e.printStackTrace()
        }
    }
}

private fun writeToStderrFd(text: String) = FileOutputStream(FileDescriptor.err).write(text.toByteArray())
