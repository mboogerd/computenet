package civictech.runtime

import civictech.cell.wire.PeerTransports
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The complete, explicit network topology from which one runtime node boots. */
@Serializable
data class Manifest(val nodes: Map<String, NodeSpec>) {

    /** Validate a directly constructed manifest with the same rules [parse] applies. */
    fun validated(): Manifest {
        val violations = mutableListOf<ManifestViolation>()
        val schemes = PeerTransports.providers().keys

        nodes.forEach { (name, node) ->
            if (node.hosts.isEmpty()) {
                violations += ManifestViolation("nodes[$name].hosts", "a node needs at least one host")
            }
            if (node.transport !in schemes) {
                violations += ManifestViolation(
                    "nodes[$name].transport",
                    "unknown transport scheme '${node.transport}' (available: ${schemes.sorted()})",
                )
            }
            node.dial.forEachIndexed { index, targetName ->
                val target = nodes[targetName]
                when {
                    target == null -> violations += ManifestViolation(
                        "nodes[$name].dial[$index]",
                        "unknown node '$targetName'",
                    )

                    target.listen == null -> violations += ManifestViolation(
                        "nodes[$name].dial[$index]",
                        "node '$targetName' has no listen address",
                    )
                }
            }
        }

        val mutualPairs = mutableSetOf<Pair<String, String>>()
        nodes.forEach { (name, node) ->
            node.dial.filter { it in nodes && name in nodes.getValue(it).dial }.forEach { target ->
                val pair = if (name <= target) name to target else target to name
                if (mutualPairs.add(pair)) {
                    violations += ManifestViolation(
                        "nodes[${pair.first}].dial<->nodes[${pair.second}].dial",
                        "mutual dial is refused: '${pair.first}' and '${pair.second}' both dial each other",
                    )
                }
            }
        }

        if (violations.isNotEmpty()) throw InvalidManifestException(violations)
        return this
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        /** Decode and validate [text], failing once with every topology violation. */
        fun parse(text: String): Manifest = json.decodeFromString(serializer(), text).validated()

        /** Read, decode and validate [file]. */
        fun load(file: File): Manifest = parse(file.readText())
    }
}

/** One node's hosts, transport role, durability and budget configuration. */
@Serializable
data class NodeSpec(
    val hosts: List<String> = listOf("main"),
    val transport: String = "ws",
    val transportConfig: Map<String, String> = emptyMap(),
    val listen: String? = null,
    val dial: List<String> = emptyList(),
    val journalDir: String? = null,
    val replica: Long? = null,
    val peerName: String? = null,
    val budget: String? = null,
)

/** One reason a [Manifest] is unsafe to launch, naming the offending field. */
data class ManifestViolation(val field: String, val message: String)

/** A manifest refused as a whole; [violations] contains every detected reason. */
class InvalidManifestException(val violations: List<ManifestViolation>) :
    Exception("Invalid Manifest: " + violations.joinToString("; ") { "${it.field}: ${it.message}" })
