package civictech.runtime

import civictech.cell.wire.PeerTransports
import civictech.identity.Ed25519
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The complete, explicit network topology from which one runtime node boots. */
@Serializable
data class Manifest(
    val nodes: Map<String, NodeSpec>,
    val placements: Map<String, String> = emptyMap(),
) {

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
            if (node.journalTopology && node.journalDir == null) {
                violations += ManifestViolation(
                    "nodes[$name].journalTopology",
                    "journalTopology requires journalDir",
                )
            }
            node.budget?.let { budget ->
                if (!File(budget).isFile) {
                    violations += ManifestViolation(
                        "nodes[$name].budget",
                        "budget policy file not found: '$budget'",
                    )
                }
            }
            node.principals.forEachIndexed { index, encoded ->
                try {
                    decodePrincipalPublicKey(encoded)
                } catch (failure: Exception) {
                    violations += ManifestViolation(
                        "nodes[$name].principals[$index]",
                        "must be a standard Base64 X.509/SPKI Ed25519 public key" +
                            (failure.message?.let { ": $it" } ?: ""),
                    )
                }
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

        placements.forEach { (selector, node) ->
            if (node !in nodes) {
                violations += ManifestViolation(
                    "placements[$selector]",
                    "unknown node '$node'",
                )
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

        /**
         * Decode and validate [text], failing once with every topology violation. Relative budget and key-store
         * paths resolve against the manifest file's directory when loaded from a file, else the working directory;
         * a missing budget file is a [ManifestViolation], while a missing key-store directory is allowed because
         * the identity store mints it at boot.
         */
        fun parse(text: String): Manifest = decode(text).validated()

        /**
         * Read, decode and validate [file]. Relative budget and key-store paths resolve against the manifest file's
         * directory, else the working directory; a missing budget file is a [ManifestViolation].
         */
        fun load(file: File): Manifest =
            decode(file.readText()).resolvingPaths(file.absoluteFile.parentFile).validated()

        private fun decode(text: String): Manifest = json.decodeFromString(serializer(), text)

        private fun Manifest.resolvingPaths(directory: File): Manifest = copy(
            nodes = nodes.mapValues { (_, node) ->
                node.copy(
                    budget = node.budget.resolveAgainst(directory),
                    keyStore = node.keyStore.resolveAgainst(directory),
                )
            },
        )

        private fun String?.resolveAgainst(directory: File): String? =
            if (this == null || File(this).isAbsolute) this else File(directory, this).path
    }
}

/** One node's hosts, transport role, durability, identity and budget configuration. */
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
    /**
     * Optional economic policy file. A relative path resolves against the manifest file's directory when loaded
     * from a file, else the working directory; a missing file is a [ManifestViolation].
     */
    val budget: String? = null,
    /**
     * Optional directory for this node's Ed25519 keypair and durable incarnation. A relative path resolves against
     * the manifest file's directory. The transport [peerName] is unrelated to this key-derived identity.
     */
    val keyStore: String? = null,
    /**
     * Standard Base64 X.509/SPKI Ed25519 public keys accepted as write-authority principals. Each key resolves to
     * its key-derived [civictech.cell.link.PeerId]; the transport [peerName] is unrelated to these principals.
     */
    val principals: List<String> = emptyList(),
    val journalTopology: Boolean = false,
)

/** Decode one manifest principal after [Manifest.validated] has checked its shape. */
internal fun decodePrincipalPublicKey(encoded: String): PublicKey {
    val bytes = Base64.getDecoder().decode(encoded)
    val key = KeyFactory.getInstance(Ed25519.KEY_FACTORY).generatePublic(X509EncodedKeySpec(bytes))
    require(Ed25519.isEd25519(key)) { "public key is not Ed25519" }
    return key
}

/** One reason a [Manifest] is unsafe to launch, naming the offending field. */
data class ManifestViolation(val field: String, val message: String)

/** A manifest refused as a whole; [violations] contains every detected reason. */
class InvalidManifestException(val violations: List<ManifestViolation>) :
    Exception("Invalid Manifest: " + violations.joinToString("; ") { "${it.field}: ${it.message}" })
