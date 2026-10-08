package civictech.concord.driver.kernel

import civictech.cell.Timestamp
import civictech.cell.link.PeerId
import civictech.cell.membrane.SignatureVerifier
import civictech.cell.replication.CountingWriteSigner
import civictech.cell.replication.SignedWrite
import civictech.cell.replication.WriteAuthorityBytes
import civictech.cell.replication.WriteSigner
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Deterministic scenario-local signing directory for the Concord dist driver.
 *
 * Actor names are neutral corpus handles. The kernel binding maps each one to
 * `PeerId(actor)` and a deterministic secret derived from that handle; neither
 * value escapes into the scenario. Actors are registered lazily when a cell
 * declares them or a signed step uses them. Verification resolves only actors
 * already registered in this run, so a name cannot acquire a key merely by
 * appearing inside a received envelope.
 */
internal class StubWriteSigning {
    private inner class Actor(val name: String) {
        val peerId = PeerId(name)
        val secret = digest("concord-write-authority:$name".toByteArray(StandardCharsets.UTF_8))
        val signer = object : WriteSigner {
            override val peerId: PeerId = this@Actor.peerId

            override fun sign(input: ByteArray): ByteArray = digest(secret + input)
        }
        val writes = CountingWriteSigner(signer) { 0L }
        val tagCounters = mutableMapOf<UUID, Long>()

        @Synchronized
        fun freshTag(logicalId: UUID): Timestamp {
            val counter = (tagCounters[logicalId] ?: 0L) + 1L
            tagCounters[logicalId] = counter
            val source = UUID.nameUUIDFromBytes(
                "concord-write-tag:$name:$logicalId".toByteArray(StandardCharsets.UTF_8),
            )
            return Timestamp(source, counter)
        }
    }

    private val actors = ConcurrentHashMap<String, Actor>()

    val verifier = SignatureVerifier { author, counter, payload, signature ->
        val actor = actors[author.name]
        val input = runCatching { WriteAuthorityBytes.canonicalBytes(author, counter, payload) }.getOrNull()
        actor != null && actor.peerId == author && input != null &&
            digest(actor.secret + input).contentEquals(signature)
    }

    fun principal(actor: String): PeerId = actor(actor).peerId

    fun signer(actor: String): WriteSigner = actor(actor).signer

    fun signed(actor: String, logicalId: UUID, payload: Any?): SignedWrite =
        actor(actor).writes.sign(logicalId, WriteAuthorityBytes.encodePayload(payload))

    fun freshTag(actor: String, logicalId: UUID): Timestamp = actor(actor).freshTag(logicalId)

    private fun actor(name: String): Actor {
        require(name.isNotBlank()) { "write-authority actor must not be blank" }
        return actors.computeIfAbsent(name) { Actor(it) }
    }

    private fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
