package civictech.bench.macro.wire

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsTable
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim
import civictech.bench.cost.Coordinate
import civictech.bench.cost.CostSchema
import civictech.bench.cost.CostSubject
import civictech.bench.cost.PointOutcome
import civictech.bench.cost.TransportValidation
import civictech.bench.macro.PointBudget
import civictech.bench.macro.SweepPlan
import civictech.bench.macro.SweepRunner
import civictech.bench.macro.shape.Shape
import civictech.bench.macro.shape.ShapeSpec
import civictech.bench.micro.FootprintReport
import civictech.cell.graph.SpawnStep
import civictech.testkit.JvmPeer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.MINUTES
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Cross-checks loopback structural counts against the real WebSocket transport (BS-16). */
@Tag("bench")
@Tag("multi-jvm")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WireCrossCheckTest {
    private val points = ConcurrentHashMap<Int, CrossCheckPoint>()

    fun hostCounts(): List<Int> = HOST_COUNTS

    @ParameterizedTest(name = "hostCount={0}")
    @MethodSource("hostCounts")
    @Timeout(value = 20, unit = MINUTES)
    fun `compares loopback and socket structural counts`(hostCount: Int) {
        val at = SweepPlan.BASELINE.copy(hostCount = hostCount)
        val loopback = SweepRunner(
            budget = PointBudget(simSteps = Int.MAX_VALUE),
            determinismCheck = true,
        ).run(at)
        val measuredLoopback = assertIs<PointOutcome.Ok>(loopback)
        assertExportMatches(at, measuredLoopback)

        val socket = runSocket(at)
        assertEquals(null, points.put(hostCount, CrossCheckPoint(at, loopback, socket)))
    }

    @AfterAll
    fun `marks the export and prints the comparison finding`() {
        assertEquals(HOST_COUNTS.toSet(), points.keys, "both host counts must produce an outcome")
        val ordered = HOST_COUNTS.map(points::getValue)
        val comparisons = ordered.flatMap(::comparisons)
        val export = exportPath()
        val beforeText = Files.readString(export)
        val before = CostSchema.parse(beforeText)
        val verdict = exportVerdict(ordered, comparisons)

        when (verdict) {
            is ExportVerdict.Marked -> TransportValidation.mark(export, verdict.validated)
            is ExportVerdict.Untouched -> Unit
        }

        val afterText = Files.readString(export)
        val after = CostSchema.parse(afterText)
        when (verdict) {
            is ExportVerdict.Marked -> after.structural.forEach { entry ->
                if (entry.at.hostCount >= 2) {
                    assertEquals(
                        verdict.validated,
                        entry.transportValidated,
                        "every loopback-derived multi-host entry must carry the cross-check verdict",
                    )
                } else {
                    assertEquals(null, entry.transportValidated, "single-host entries have no transport")
                }
            }

            is ExportVerdict.Untouched -> {
                assertEquals(beforeText, afterText, "an incomplete socket cross-check must not rewrite the export")
                assertEquals(before, after)
                after.structural.filter { it.at.hostCount == 1 }.forEach { entry ->
                    assertEquals(null, entry.transportValidated, "single-host entries have no transport")
                }
            }
        }

        print(renderFindings(ordered, comparisons, verdict))
    }

    private fun runSocket(at: Coordinate): SocketOutcome {
        val peers = mutableListOf<JvmPeer.Peer>()
        val ports = linkedMapOf<Int, Int>()
        try {
            repeat(at.hostCount) { index ->
                val peer = try {
                    JvmPeer.launch(
                        MAIN_CLASS,
                        *WirePeerProtocol.argv(at, index, ports).toTypedArray(),
                    )
                } catch (failure: IOException) {
                    return SocketOutcome.Unschedulable(describe(failure), "peers=${peers.size}")
                } catch (failure: OutOfMemoryError) {
                    return SocketOutcome.Unschedulable(describe(failure), "peers=${peers.size}")
                }
                peers += peer
                ports[index] = peer.port("ws")
            }

            awaitProtocol("all wire peers ready", peers) {
                peers.withIndex().all { (index, peer) ->
                    peer.output().lineSequence().any { WirePeerProtocol.parseReady(it) == index }
                }
            }

            val sourcePeers = sourcePeers(at)
            require(sourcePeers.size == 1) {
                "the baseline cross-check expects exactly one source peer, got $sourcePeers"
            }
            val nonSourcePeers = peers.indices.filterNot { it in sourcePeers }
            nonSourcePeers.forEach { send(peers[it], WirePeerProtocol.GO) }
            if (nonSourcePeers.isNotEmpty()) readRound(peers, nonSourcePeers)
            sourcePeers.forEach { send(peers[it], WirePeerProtocol.GO) }

            var previous: List<PeerCounts>? = null
            repeat(SETTLE_ROUNDS) {
                val current = readRound(peers, peers.indices.toList())
                if (current == previous) {
                    val sent = current.sumOf { it.sent }
                    val received = current.sumOf { it.received }
                    val enqueued = current.sumOf { it.enqueued }
                    val bytes = current.sumOf { it.bytes }
                    if (sent != received) {
                        return SocketOutcome.Failed("frame loss: sent=$sent received=$received")
                    }
                    if (received != enqueued) {
                        return SocketOutcome.Failed(
                            "bridge loss: received=$received enqueued=$enqueued",
                        )
                    }
                    return SocketOutcome.Counts(sent, received, enqueued, bytes)
                }
                previous = current
            }
            return SocketOutcome.NonConvergent(
                "settleRounds=$SETTLE_ROUNDS,quietMillis=${WirePeerProtocol.QUIET_MILLIS}",
            )
        } catch (failure: ReportedPeerFailure) {
            return SocketOutcome.Failed(failure.causeText)
        } finally {
            peers.forEach { peer -> runCatching { send(peer, WirePeerProtocol.STOP) } }
            JvmPeer.destroy(peers)
        }
    }

    private fun readRound(peers: List<JvmPeer.Peer>, indices: List<Int>): List<PeerCounts> {
        val before = indices.associateWith { countLines(peers[it], it).size }
        indices.forEach { send(peers[it], WirePeerProtocol.READ) }
        awaitProtocol("wire count round from peers ${indices.joinToString()}", peers) {
            indices.all { index -> countLines(peers[index], index).size > before.getValue(index) }
        }
        return indices.map { index ->
            checkNotNull(countLines(peers[index], index).lastOrNull()) {
                "peer $index produced no counts\n${peers[index].report()}"
            }
        }
    }

    private fun awaitProtocol(
        what: String,
        peers: List<JvmPeer.Peer>,
        condition: () -> Boolean,
    ) {
        JvmPeer.await(what, peers) {
            reportedFailure(peers) != null || peers.any { !it.process.isAlive } || condition()
        }
        reportedFailure(peers)?.let { failure ->
            throw ReportedPeerFailure("peer ${failure.index}: ${failure.cause}")
        }
        assertTrue(
            peers.all { it.process.isAlive },
            "a launched wire peer exited before $what\n\n${peers.joinToString("\n\n") { it.report() }}",
        )
        assertTrue(condition(), "protocol wait ended without satisfying '$what'")
    }

    private fun reportedFailure(peers: List<JvmPeer.Peer>): WirePeerProtocol.PeerFailure? =
        peers.asSequence()
            .flatMap { it.output().lineSequence() }
            .mapNotNull(WirePeerProtocol::parseFailed)
            .firstOrNull()

    private fun countLines(peer: JvmPeer.Peer, index: Int): List<PeerCounts> =
        peer.output().lineSequence()
            .mapNotNull(WirePeerProtocol::parseCounts)
            .filter { it.index == index }
            .map { it.counts }
            .toList()

    private fun send(peer: JvmPeer.Peer, command: String) {
        peer.process.outputStream.write("$command\n".toByteArray(StandardCharsets.UTF_8))
        peer.process.outputStream.flush()
    }

    private fun sourcePeers(at: Coordinate): Set<Int> {
        val shape = Shape.valueOf(at.shape)
        val generated = ShapeSpec(
            shape = shape,
            seed = at.shapeSeed,
            cardinality = SweepRunner.nodeCount(shape, at.fanOut),
            fanOut = at.fanOut,
            hostCount = at.hostCount,
        ).generate()
        return generated.spec.lowered()
            .filterIsInstance<SpawnStep>()
            .filter { it.handle in generated.sources }
            .mapTo(linkedSetOf()) { step ->
                requireNotNull(step.placement).removePrefix("host").toInt()
            }
    }

    private fun assertExportMatches(at: Coordinate, loopback: PointOutcome.Ok) {
        val model = CostSchema.parse(Files.readString(exportPath()))
        val exported = checkNotNull(
            model.structural.singleOrNull { entry ->
                entry.subject == CostSubject.PROPAGATION && entry.at == at
            },
        ) { "export has no unique PROPAGATION entry at $at" }
        assertEquals(
            loopback.counts.crossHostFrames,
            exported.counts.crossHostFrames,
            "stale export at $at: runner frames=${loopback.counts.crossHostFrames}, " +
                "export frames=${exported.counts.crossHostFrames}",
        )
        assertEquals(
            loopback.counts.wireBytes,
            exported.counts.wireBytes,
            "stale export at $at: runner bytes=${loopback.counts.wireBytes}, " +
                "export bytes=${exported.counts.wireBytes}",
        )
    }

    private fun comparisons(point: CrossCheckPoint): List<Comparison> {
        val loopback = point.loopback as? PointOutcome.Ok ?: return emptyList()
        val socket = point.socket as? SocketOutcome.Counts ?: return emptyList()
        return listOf(
            Comparison(
                hostCount = point.at.hostCount,
                metric = Metric.FRAMES,
                loopback = loopback.counts.crossHostFrames,
                socket = socket.sent,
                tolerance = FRAME_TOLERANCE,
            ),
            Comparison(
                hostCount = point.at.hostCount,
                metric = Metric.BYTES,
                loopback = loopback.counts.wireBytes,
                socket = socket.bytes,
                tolerance = BYTE_TOLERANCE,
            ),
        )
    }

    private fun exportVerdict(
        points: List<CrossCheckPoint>,
        comparisons: List<Comparison>,
    ): ExportVerdict {
        val incomplete = points.filterNot { it.socket is SocketOutcome.Counts }
        if (incomplete.isNotEmpty()) {
            return ExportVerdict.Untouched(
                incomplete.joinToString("; ") { point ->
                    "hostCount=${point.at.hostCount}: ${point.socket.summary()}"
                },
            )
        }
        check(comparisons.size == HOST_COUNTS.size * Metric.entries.size)
        return ExportVerdict.Marked(validated = comparisons.all { it.agrees })
    }

    private fun renderFindings(
        points: List<CrossCheckPoint>,
        comparisons: List<Comparison>,
        verdict: ExportVerdict,
    ): String {
        val harnessSha = requiredHarnessSha()
        val env = RunEnvironment.forRun(
            measuringJvm = FootprintReport.inProcessMeasuringJvm(),
            jmhMode = SOCKET_MODE,
            forkCount = HOST_COUNTS.max(),
            warmupIterations = 1,
            measurementIterations = 1,
            harnessCommitSha = harnessSha,
        )
        val measured = points.mapNotNull { point ->
            (point.socket as? SocketOutcome.Counts)?.let { point.at.hostCount to it }
        }
        check(measured.isNotEmpty()) { "no socket count exists for the findings result table" }
        val rows = measured.flatMap { (_, counts) ->
            listOf(
                BenchResult(counts.sent.toDouble(), "crossHostFrames/point", 0.0, Drive.REAL, env),
                BenchResult(counts.bytes.toDouble(), "wireBytes/point", 0.0, Drive.REAL, env),
            )
        }
        val labels = measured.flatMap { (hostCount, _) ->
            listOf("hostCount=$hostCount crossHostFrames", "hostCount=$hostCount wireBytes")
        }
        val entry = Findings.entry(
            date = System.getProperty(DATE_PROPERTY)?.takeIf(String::isNotBlank)
                ?: LocalDate.now().toString(),
            subject = SUBJECT,
            results = FindingsTable(
                results = rows,
                labels = labels,
                dispersionObserved = List(rows.size) { false },
            ),
            trigger = TriggerClaim.None,
        )

        return buildString {
            appendLine(entry)
            appendLine()
            appendLine("### Comparison")
            appendLine()
            appendLine("| hostCount | metric | loopback (SIM) | socket (REAL, Σ peers) | relative error | tolerance | verdict |")
            appendLine("| ---: | --- | ---: | ---: | ---: | ---: | --- |")
            points.forEach { point ->
                val byMetric = comparisons.filter { it.hostCount == point.at.hostCount }
                    .associateBy(Comparison::metric)
                Metric.entries.forEach { metric ->
                    val comparison = byMetric[metric]
                    if (comparison != null) {
                        appendLine(comparison.markdownRow())
                    } else {
                        val loopback = point.loopback as PointOutcome.Ok
                        val loopbackValue = when (metric) {
                            Metric.FRAMES -> loopback.counts.crossHostFrames
                            Metric.BYTES -> loopback.counts.wireBytes
                        }
                        appendLine(
                            "| ${point.at.hostCount} | ${metric.label} | $loopbackValue | — | — | " +
                                "${decimal(metric.tolerance)} | NOT MEASURED: ${point.socket.summary()} |",
                        )
                    }
                }
            }
            appendLine()
            appendLine("### Systematic offset")
            appendLine()
            appendLine(systematicOffset(points))
            appendLine()
            appendLine("### Export marking")
            appendLine()
            appendLine(
                when (verdict) {
                    is ExportVerdict.Marked -> if (verdict.validated) {
                        "Applied `transportValidated = true`: both host counts completed and every " +
                            "frame and byte comparison was within its fixed tolerance."
                    } else {
                        "Applied `transportValidated = false`: both host counts completed, but at " +
                            "least one measured frame or byte comparison exceeded its fixed tolerance."
                    }

                    is ExportVerdict.Untouched ->
                        "Left the export untouched because the socket cross-check was incomplete: ${verdict.reason}."
                },
            )
            appendLine()
            appendLine("### Omissions")
            appendLine()
            appendLine("- Host counts 5 and 8 were not run over sockets; BEN2 §2.4 limits the real-transport cross-check to 2 and 3.")
            appendLine("- REAL-drive wall time was not compared; [BEN2-33] separates REAL calibration time from SIM structural counts.")
            appendLine()
            appendLine("### Reset hazard")
            appendLine()
            append(
                "A later `MacroSweepTest` whole-export rewrite or `SubjectMerge` replacement drops " +
                    "`transportValidated`; rerun this cross-check after either operation (0cnmw-D17).",
            )
        }
    }

    private fun systematicOffset(points: List<CrossCheckPoint>): String {
        val measured = points.mapNotNull { point ->
            val loopback = point.loopback as? PointOutcome.Ok ?: return@mapNotNull null
            val socket = point.socket as? SocketOutcome.Counts ?: return@mapNotNull null
            val total = socket.bytes - loopback.counts.wireBytes
            val perFrame = if (socket.sent == 0L) Double.NaN else total.toDouble() / socket.sent
            Triple(point.at.hostCount, total, perFrame)
        }
        if (measured.isEmpty()) {
            return "No completed socket point exists, so the sign or size of a systematic byte offset cannot be assessed; modelling remains PLC1's question (0cnmw-D6)."
        }
        val details = measured.joinToString("; ") { (hostCount, total, perFrame) ->
            val sign = when {
                total > 0 -> "+"
                total < 0 -> "−"
                else -> "±"
            }
            "h=$hostCount: $sign${abs(total)} bytes total (${decimal(perFrame)} bytes/frame)"
        }
        val perFrameValues = measured.map { it.third }
        val constant = perFrameValues.all { value ->
            value.isFinite() && abs(value - perFrameValues.first()) <= PER_FRAME_OFFSET_EPSILON
        }
        val reading = if (constant) "constant at the measured precision" else "not constant across host counts"
        return "Socket minus loopback encoded-byte offset: $details; the per-frame offset is $reading. " +
            "It is reported rather than modelled; whether PLC1 should price it remains PLC1's question (0cnmw-D6)."
    }

    private fun exportPath(): Path = Path.of(requiredRepoRoot(), CostSchema.STABLE_PATH)

    private fun requiredHarnessSha(): String =
        requireNotNull(System.getProperty(HARNESS_SHA_PROPERTY)?.takeIf(String::isNotBlank)) {
            "set -D$HARNESS_SHA_PROPERTY to the harness commit that produced this measurement"
        }

    private fun requiredRepoRoot(): String =
        requireNotNull(System.getProperty(REPO_ROOT_PROPERTY)?.takeIf(String::isNotBlank)) {
            "system property $REPO_ROOT_PROPERTY must name the export checkout"
        }

    private data class CrossCheckPoint(
        val at: Coordinate,
        val loopback: PointOutcome,
        val socket: SocketOutcome,
    )

    private sealed interface SocketOutcome {
        data class Counts(
            val sent: Long,
            val received: Long,
            val enqueued: Long,
            val bytes: Long,
        ) : SocketOutcome

        data class Unschedulable(val reason: String, val observedLimit: String) : SocketOutcome
        data class NonConvergent(val budget: String) : SocketOutcome
        data class Failed(val cause: String) : SocketOutcome

        fun summary(): String = when (this) {
            is Counts -> "sent=$sent received=$received enqueued=$enqueued bytes=$bytes"
            is Unschedulable -> "Unschedulable ($reason; $observedLimit)"
            is NonConvergent -> "NonConvergent ($budget)"
            is Failed -> "Failed ($cause)"
        }
    }

    private enum class Metric(val label: String, val tolerance: Double) {
        FRAMES("crossHostFrames", FRAME_TOLERANCE),
        BYTES("wireBytes", BYTE_TOLERANCE),
    }

    private data class Comparison(
        val hostCount: Int,
        val metric: Metric,
        val loopback: Long,
        val socket: Long,
        val tolerance: Double,
    ) {
        val relativeError: Double = when {
            loopback == 0L && socket == 0L -> 0.0
            loopback == 0L -> Double.POSITIVE_INFINITY
            else -> abs(socket - loopback).toDouble() / loopback.toDouble()
        }
        val agrees: Boolean = relativeError <= tolerance

        fun markdownRow(): String =
            "| $hostCount | ${metric.label} | $loopback | $socket | ${decimal(relativeError)} | " +
                "${decimal(tolerance)} | ${if (agrees) "AGREE" else "DISAGREE"} |"
    }

    private sealed interface ExportVerdict {
        data class Marked(val validated: Boolean) : ExportVerdict
        data class Untouched(val reason: String) : ExportVerdict
    }

    private class ReportedPeerFailure(val causeText: String) : RuntimeException(causeText)

    companion object {
        val HOST_COUNTS: List<Int> = listOf(2, 3)
        const val SETTLE_ROUNDS: Int = 10

        /**
         * First observed loopback/socket frame pairs: h=2 was 21,458/21,458 and h=3
         * was 26,583/26,583, relative error 0 at both points (2026-10-16). Both paths
         * count each encoded bridge frame exactly once, so exact agreement is the
         * contract and the tolerance is fixed at 0 rather than widened to absorb drift.
         */
        const val FRAME_TOLERANCE: Double = 0.0

        /**
         * First observed loopback/socket byte pairs: h=2 was 10,789,569/10,789,569 and
         * h=3 was 13,605,747/13,605,747, relative error 0 at both points (2026-10-16).
         * Each byte tap sees the same pre-WebSocket `WireCodec` payload as the loopback
         * counter, so exact agreement is required and the tolerance is fixed at 0.
         */
        const val BYTE_TOLERANCE: Double = 0.0

        private const val MAIN_CLASS = "civictech.bench.macro.wire.WirePeerMain"
        private const val HARNESS_SHA_PROPERTY = "civictech.bench.harnessSha"
        private const val DATE_PROPERTY = "civictech.bench.date"
        private const val REPO_ROOT_PROPERTY = "computenet.repo.root"
        private const val PER_FRAME_OFFSET_EPSILON = 0.000_001
        private const val SOCKET_MODE =
            "REAL structural cross-check (JUnit @Tag(\"bench\") + @Tag(\"multi-jvm\"); " +
                "forked WsTransport peers; not JMH)"
        private const val SUBJECT =
            "wire cross-check — baseline structural counts over forked WsTransport peers " +
                "versus the deterministic in-process loopback mesh (BS-16)"

        private fun decimal(value: Double): String =
            if (value.isFinite()) String.format(Locale.ROOT, "%.9f", value) else "infinite"

        private fun describe(failure: Throwable): String =
            "${failure.javaClass.name}: ${failure.message}"
    }
}
