package civictech.bench.macro.wire

import civictech.bench.cost.Coordinate
import civictech.bench.macro.SweepPlan
import civictech.bench.macro.SweepRunner
import civictech.bench.macro.shape.Shape
import civictech.bench.macro.shape.ShapeSpec
import civictech.cell.graph.SpawnStep
import civictech.testkit.JvmPeer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("bench")
@Tag("multi-jvm")
@Timeout(5, unit = TimeUnit.MINUTES)
class WirePeerSmokeTest {
    @Test
    fun `two forked wire peers report lossless non-vacuous socket counts`() {
        val coordinate = SweepPlan.BASELINE.copy(hostCount = 2, cardinality = 100)
        val peers = mutableListOf<JvmPeer.Peer>()
        val ports = linkedMapOf<Int, Int>()
        try {
            repeat(coordinate.hostCount) { index ->
                val args = WirePeerProtocol.argv(coordinate, index, ports).toTypedArray()
                val peer = JvmPeer.launch(MAIN_CLASS, *args)
                peers += peer
                ports[index] = peer.port("ws")
            }

            JvmPeer.await("both wire peers ready", peers) {
                peers.withIndex().all { (index, peer) ->
                    peer.output().lineSequence().any { WirePeerProtocol.parseReady(it) == index }
                }
            }

            val sourcePeers = sourcePeers(coordinate)
            require(sourcePeers.size == 1) { "the baseline smoke expects exactly one source peer, got $sourcePeers" }
            val nonSourcePeers = peers.indices.filterNot { it in sourcePeers }

            // A write to another process's stdin only queues the command; ordering those
            // writes alone cannot prove the non-source peer took its snapshot before the
            // source starts driving. A zero-work read is the acknowledgement: it can only
            // answer after that peer processed `go` and stored its snapshot.
            nonSourcePeers.forEach { send(peers[it], WirePeerProtocol.GO) }
            nonSourcePeers.forEach { send(peers[it], WirePeerProtocol.READ) }
            JvmPeer.await("non-source wire peers snapshotted", peers) {
                nonSourcePeers.all { index -> countLines(peers[index], index).size == 1 }
            }
            sourcePeers.forEach { send(peers[it], WirePeerProtocol.GO) }
            peers.forEach { send(it, WirePeerProtocol.READ) }

            JvmPeer.await("both wire peers counted", peers) {
                peers.withIndex().all { (index, peer) ->
                    countLines(peer, index).size == if (index in sourcePeers) 1 else 2
                }
            }
            val reported = peers.mapIndexed { index, peer ->
                checkNotNull(countLines(peer, index).lastOrNull()) {
                    "peer $index produced no counts\n${peer.report()}"
                }
            }
            val sent = reported.sumOf { it.sent }
            val received = reported.sumOf { it.received }
            val enqueued = reported.sumOf { it.enqueued }
            val bytes = reported.sumOf { it.bytes }
            val reports = peers.joinToString("\n\n") { it.report() }
            reported.forEachIndexed { index, counts ->
                println(WirePeerProtocol.counts(index, counts))
            }

            assertEquals(received, sent, "every sent frame must be received across the two-peer mesh\n$reports")
            assertTrue(sent > 0, "the socket measurement must contain at least one workload frame\n$reports")
            assertEquals(received, enqueued, "every received frame must reach the bridge host\n$reports")
            assertTrue(bytes > 0, "the byte tap must observe encoded workload frames\n$reports")
        } finally {
            peers.forEach { peer -> runCatching { send(peer, WirePeerProtocol.STOP) } }
            JvmPeer.destroy(peers)
        }
    }

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

    companion object {
        private const val MAIN_CLASS = "civictech.bench.macro.wire.WirePeerMain"
    }
}
