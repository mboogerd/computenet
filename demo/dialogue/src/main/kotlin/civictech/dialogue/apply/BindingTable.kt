package civictech.dialogue.apply

import civictech.cell.CellRef
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.TopoEvent
import civictech.dialogue.ClaimKey
import civictech.dialogue.RelationKey
import java.util.UUID

/**
 * Read-only dialogue-key view over the kernel's folded live topology.
 *
 * Claims and relations are bound exactly while their [TopoEvent.Spawn] is live
 * under the dialogue handle for that key. The fold is the single durable
 * record: despawn removes the binding, and topology recovery restores it before
 * frame replay. This class owns no state and writes no files.
 */
class BindingTable(private val context: ApplyContext) {

    /** The ref claim [key] is currently bound to, or `null`. */
    fun refOf(key: ClaimKey): CellRef? = refOf(handleFor(key))

    /** The ref relation [key] is currently bound to, or `null`. */
    fun refOf(key: RelationKey): CellRef? = refOf(handleFor(key))

    fun isBound(key: ClaimKey): Boolean = refOf(key) != null

    fun isBound(key: RelationKey): Boolean = refOf(key) != null

    /** Every claim key whose spawn is currently live. */
    fun boundClaims(): Set<ClaimKey> = boundKeys(CLAIM_HANDLE_PREFIX, ::ClaimKey)

    /** Every relation key whose spawn is currently live. */
    fun boundRelations(): Set<RelationKey> = boundKeys(RELATION_HANDLE_PREFIX, ::RelationKey)

    /** The key currently bound to [ref], or `null` — the reverse of [refOf]. */
    fun keyOf(ref: CellRef): BoundKey? {
        val handle = context.live().spawns[ref]?.handle ?: return null
        return when {
            handle.startsWith(CLAIM_HANDLE_PREFIX) ->
                BoundKey.OfClaim(ClaimKey(handle.removePrefix(CLAIM_HANDLE_PREFIX)))

            handle.startsWith(RELATION_HANDLE_PREFIX) ->
                BoundKey.OfRelation(RelationKey(handle.removePrefix(RELATION_HANDLE_PREFIX)))

            else -> null
        }
    }

    private fun refOf(handle: String): CellRef? {
        val topology = context.live()
        return topology.handles[handle]?.takeIf(topology.spawns::containsKey)
    }

    private fun <K> boundKeys(prefix: String, key: (String) -> K): Set<K> =
        context.live().spawns.values.mapNotNullTo(linkedSetOf()) { spawn ->
            spawn.handle.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.let(key)
        }

    companion object {
        private const val CLAIM_HANDLE_PREFIX = "dialogue:claim:"
        private const val RELATION_HANDLE_PREFIX = "dialogue:relation:"

        fun handleFor(key: ClaimKey): String = "$CLAIM_HANDLE_PREFIX${key.value}"

        fun handleFor(key: RelationKey): String = "$RELATION_HANDLE_PREFIX${key.value}"

        /** Deterministic, restart-stable ref for a claim key. */
        fun refFor(key: ClaimKey): CellRef =
            CellRef(UUID.nameUUIDFromBytes(handleFor(key).toByteArray()))

        /** Deterministic, restart-stable ref for a relation key. */
        fun refFor(key: RelationKey): CellRef =
            CellRef(UUID.nameUUIDFromBytes(handleFor(key).toByteArray()))
    }
}

/** The reverse of [BindingTable.refOf] — which kind of key a ref is bound to. */
sealed interface BoundKey {
    data class OfClaim(val key: ClaimKey) : BoundKey
    data class OfRelation(val key: RelationKey) : BoundKey
}
