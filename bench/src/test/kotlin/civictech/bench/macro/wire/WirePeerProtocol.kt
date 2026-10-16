package civictech.bench.macro.wire

import civictech.bench.Drive
import civictech.bench.cost.Coordinate

/** Pure argv/stdin/stdout protocol shared by the forked wire peer and its parent tests. */
object WirePeerProtocol {
    const val GO = "go"
    const val READ = "read"
    const val STOP = "stop"
    const val QUIET_MILLIS: Long = 1_000

    private const val READY_PREFIX = "computenet-ready "
    private const val COUNTS_PREFIX = "computenet-counts "
    private const val FAILED_PREFIX = "computenet-failed "

    private val countsLine = Regex(
        "^computenet-counts (\\d+) sent=(\\d+) received=(\\d+) enqueued=(\\d+) bytes=(\\d+)$",
    )
    private val failedLine = Regex("^computenet-failed (\\d+) (.+)$")

    data class Launch(
        val coordinate: Coordinate,
        val index: Int,
        val peerPorts: Map<Int, Int>,
    )

    data class IndexedCounts(val index: Int, val counts: PeerCounts)

    data class PeerFailure(val index: Int, val cause: String)

    fun argv(coordinate: Coordinate, index: Int, peerPorts: Map<Int, Int>): List<String> {
        require(coordinate.drive == Drive.SIM) { "wire structural coordinates must use SIM drive" }
        require(!coordinate.replication) { "wire peers do not support replication=true" }
        require(!coordinate.durability) { "wire peers do not support durability=true" }
        validatePeer(index, coordinate.hostCount, peerPorts)
        return buildList {
            addAll(listOf("--shape", coordinate.shape))
            addAll(listOf("--seed", coordinate.shapeSeed.toString()))
            addAll(listOf("--cardinality", coordinate.cardinality.toString()))
            addAll(listOf("--batch", coordinate.batchSize.toString()))
            addAll(listOf("--hosts", coordinate.hostCount.toString()))
            addAll(listOf("--fanout", coordinate.fanOut.toString()))
            addAll(listOf("--index", index.toString()))
            peerPorts.toSortedMap().forEach { (peer, port) ->
                addAll(listOf("--peer", "$peer=$port"))
            }
        }
    }

    fun parseArgv(args: Array<String>): Launch {
        require(args.size % 2 == 0) { "wire peer argv must contain flag/value pairs" }
        val scalar = linkedMapOf<String, String>()
        val peerPorts = linkedMapOf<Int, Int>()
        args.asList().chunked(2).forEach { (flag, value) ->
            when (flag) {
                "--peer" -> {
                    val (peerText, portText) = value.split('=', limit = 2)
                        .takeIf { it.size == 2 }
                        ?: throw IllegalArgumentException("--peer must be <index>=<port>, got '$value'")
                    val peer = peerText.toIntOrNull()
                        ?: throw IllegalArgumentException("--peer index is not an integer: '$peerText'")
                    val port = portText.toIntOrNull()
                        ?: throw IllegalArgumentException("--peer port is not an integer: '$portText'")
                    require(peerPorts.put(peer, port) == null) { "duplicate --peer index $peer" }
                }

                in SCALAR_FLAGS -> require(scalar.put(flag, value) == null) { "duplicate $flag" }
                else -> throw IllegalArgumentException("unknown wire peer flag '$flag'")
            }
        }

        fun required(flag: String): String =
            scalar[flag] ?: throw IllegalArgumentException("missing $flag")

        fun long(flag: String): Long = required(flag).toLongOrNull()
            ?: throw IllegalArgumentException("$flag is not a long: '${required(flag)}'")

        fun int(flag: String): Int = required(flag).toIntOrNull()
            ?: throw IllegalArgumentException("$flag is not an integer: '${required(flag)}'")

        val coordinate = Coordinate(
            shape = required("--shape"),
            shapeSeed = long("--seed"),
            cardinality = long("--cardinality"),
            batchSize = int("--batch"),
            hostCount = int("--hosts"),
            fanOut = int("--fanout"),
            replication = false,
            durability = false,
            drive = Drive.SIM,
        )
        require(coordinate.cardinality >= 0) { "--cardinality must be non-negative" }
        require(coordinate.batchSize >= 1) { "--batch must be at least 1" }
        require(coordinate.hostCount >= 1) { "--hosts must be at least 1" }
        require(coordinate.fanOut >= 1) { "--fanout must be at least 1" }
        val index = int("--index")
        validatePeer(index, coordinate.hostCount, peerPorts)
        return Launch(coordinate, index, peerPorts.toMap())
    }

    fun ready(index: Int): String = "$READY_PREFIX$index"

    fun parseReady(line: String): Int? =
        line.takeIf { it.startsWith(READY_PREFIX) }
            ?.removePrefix(READY_PREFIX)
            ?.toIntOrNull()

    fun counts(index: Int, counts: PeerCounts): String =
        "$COUNTS_PREFIX$index sent=${counts.sent} received=${counts.received} " +
            "enqueued=${counts.enqueued} bytes=${counts.bytes}"

    fun parseCounts(line: String): IndexedCounts? {
        val match = countsLine.matchEntire(line) ?: return null
        val values = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
        val index = values[0].takeIf { it <= Int.MAX_VALUE }?.toInt() ?: return null
        return IndexedCounts(
            index,
            PeerCounts(
                sent = values[1],
                received = values[2],
                enqueued = values[3],
                bytes = values[4],
            ),
        )
    }

    fun failed(index: Int, cause: String): String =
        "$FAILED_PREFIX$index ${cause.replace(Regex("[\\r\\n]+"), " ")}"

    fun parseFailed(line: String): PeerFailure? {
        val match = failedLine.matchEntire(line) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: return null
        return PeerFailure(index, match.groupValues[2])
    }

    private fun validatePeer(index: Int, hostCount: Int, peerPorts: Map<Int, Int>) {
        require(index in 0 until hostCount) { "--index $index is outside 0 until $hostCount" }
        peerPorts.forEach { (peer, port) ->
            require(peer in 0 until index) { "--peer $peer must be lower than index $index" }
            require(port in 1..65_535) { "--peer $peer port $port is outside 1..65535" }
        }
        require(peerPorts.keys == (0 until index).toSet()) {
            "peer ports for index $index must name exactly ${0 until index}, got ${peerPorts.keys.sorted()}"
        }
    }

    private val SCALAR_FLAGS = setOf(
        "--shape",
        "--seed",
        "--cardinality",
        "--batch",
        "--hosts",
        "--fanout",
        "--index",
    )
}

data class PeerCounts(
    val sent: Long,
    val received: Long,
    val enqueued: Long,
    val bytes: Long,
) {
    init {
        require(sent >= 0 && received >= 0 && enqueued >= 0 && bytes >= 0) {
            "peer counts must be non-negative"
        }
    }

    operator fun minus(start: PeerCounts): PeerCounts = PeerCounts(
        sent = sent - start.sent,
        received = received - start.received,
        enqueued = enqueued - start.enqueued,
        bytes = bytes - start.bytes,
    )
}
