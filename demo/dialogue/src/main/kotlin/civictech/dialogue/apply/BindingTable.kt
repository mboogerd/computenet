package civictech.dialogue.apply

import civictech.cell.CellRef
import civictech.dialogue.ClaimKey
import civictech.dialogue.RelationKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * The Key → CellRef binding table F4's applier (sibling task computenet-2aw.4.2)
 * consumes to make agora writes idempotent, and to reconcile the canonical
 * sets against what has already been applied (epic DESIGN D3, 2aw.F4-D2).
 *
 * Copies the pattern from [civictech.cell.host.KeyedCells] — deterministic
 * refs via `nameUUIDFromBytes`, an append-on-write fsync'd side log, replay
 * that lets the LAST record for a key win — without extending or depending
 * on it: `KeyedCells` owns a live *cell* per key and spawns it; this table
 * owns no cells and never imports `civictech.agora` or spawns anything. It
 * is pure bookkeeping the applier consults before and after it talks to
 * `AgoraService`.
 *
 * **Ordering is the applier's responsibility, not this table's** (D2, load-
 * bearing): a binding is recorded only AFTER the corresponding agora write
 * succeeds — [bind] is called once `createClaim`/`createEdge` has returned,
 * [unbind] once `remove` has returned. This class does not verify that
 * ordering; it just durably remembers whatever the applier tells it.
 *
 * [journalDir] `null` = ephemeral: no file is ever touched, exactly
 * `KeyedCells`' contract; bind/unbind still work in memory for the lifetime
 * of this instance. Non-null: every bind/unbind event is appended as one
 * line of JSON to `bindings.jsonl` inside [journalDir], fsync'd before the
 * call returns (the `KeyedCells.appendKey` idiom — `FileOutputStream`
 * append, flush, `fd.sync()`), and on construction that log is replayed in
 * order so a fresh instance opened on the same directory reconstructs the
 * same bindings a prior instance held, survivable across a `kill -9`.
 */
class BindingTable(private val journalDir: File? = null) {

    /** One line of [journalFile]: `op` is "bind" or "unbind", `kind` is "claim" or "relation". */
    @Serializable
    private data class Record(val op: String, val kind: String, val key: String, val ref: String)

    private val lock = Any()

    private val claimBindings = mutableMapOf<ClaimKey, CellRef>()
    private val relationBindings = mutableMapOf<RelationKey, CellRef>()

    /** Reverse index: F5's /provenance is keyed by ref (epic §2.4). */
    private val reverse = mutableMapOf<CellRef, BoundKey>()

    private val journalFile = journalDir?.let { File(it, JOURNAL_FILE) }

    init {
        // Replay in file order; each apply* overwrites/removes the prior
        // in-memory entry for that key, so the LAST record for a key wins —
        // a key-level fact, not a per-line one — with no explicit "latest
        // wins" bookkeeping needed beyond ordinary map semantics.
        journalFile?.takeIf { it.exists() }
            ?.readLines()?.filter { it.isNotBlank() }
            ?.forEach { line ->
                val record = Json.decodeFromString(Record.serializer(), line)
                when (record.op) {
                    "bind" -> applyBind(record.kind, record.key, CellRef(UUID.fromString(record.ref)))
                    "unbind" -> applyUnbind(record.kind, record.key)
                    else -> error("BindingTable: unknown journal op '${record.op}'")
                }
            }
    }

    /** Record claim [key] as bound to its deterministic ref and return it. */
    fun bind(key: ClaimKey): CellRef = synchronized(lock) {
        val ref = refFor(key)
        applyBind(KIND_CLAIM, key.value, ref)
        append(Record(op = "bind", kind = KIND_CLAIM, key = key.value, ref = ref.id.toString()))
        ref
    }

    /** Record relation [key] as bound to its deterministic ref and return it. */
    fun bind(key: RelationKey): CellRef = synchronized(lock) {
        val ref = refFor(key)
        applyBind(KIND_RELATION, key.value, ref)
        append(Record(op = "bind", kind = KIND_RELATION, key = key.value, ref = ref.id.toString()))
        ref
    }

    /** Forget claim [key]'s binding. A no-op (no journal record) if it was not bound. */
    fun unbind(key: ClaimKey): Unit = synchronized(lock) {
        val ref = claimBindings[key] ?: return
        applyUnbind(KIND_CLAIM, key.value)
        append(Record(op = "unbind", kind = KIND_CLAIM, key = key.value, ref = ref.id.toString()))
    }

    /** Forget relation [key]'s binding. A no-op (no journal record) if it was not bound. */
    fun unbind(key: RelationKey): Unit = synchronized(lock) {
        val ref = relationBindings[key] ?: return
        applyUnbind(KIND_RELATION, key.value)
        append(Record(op = "unbind", kind = KIND_RELATION, key = key.value, ref = ref.id.toString()))
    }

    /** The ref claim [key] is currently bound to, or `null`. */
    fun refOf(key: ClaimKey): CellRef? = synchronized(lock) { claimBindings[key] }

    /** The ref relation [key] is currently bound to, or `null`. */
    fun refOf(key: RelationKey): CellRef? = synchronized(lock) { relationBindings[key] }

    fun isBound(key: ClaimKey): Boolean = synchronized(lock) { key in claimBindings }

    fun isBound(key: RelationKey): Boolean = synchronized(lock) { key in relationBindings }

    /** Every claim key currently bound. */
    fun boundClaims(): Set<ClaimKey> = synchronized(lock) { claimBindings.keys.toSet() }

    /** Every relation key currently bound. */
    fun boundRelations(): Set<RelationKey> = synchronized(lock) { relationBindings.keys.toSet() }

    /** The key currently bound to [ref], or `null` — the reverse of [refOf]. */
    fun keyOf(ref: CellRef): BoundKey? = synchronized(lock) { reverse[ref] }

    private fun applyBind(kind: String, key: String, ref: CellRef) {
        when (kind) {
            KIND_CLAIM -> {
                val claimKey = ClaimKey(key)
                claimBindings[claimKey] = ref
                reverse[ref] = BoundKey.OfClaim(claimKey)
            }
            KIND_RELATION -> {
                val relationKey = RelationKey(key)
                relationBindings[relationKey] = ref
                reverse[ref] = BoundKey.OfRelation(relationKey)
            }
            else -> error("BindingTable: unknown journal kind '$kind'")
        }
    }

    private fun applyUnbind(kind: String, key: String) {
        when (kind) {
            KIND_CLAIM -> claimBindings.remove(ClaimKey(key))?.let { reverse.remove(it) }
            KIND_RELATION -> relationBindings.remove(RelationKey(key))?.let { reverse.remove(it) }
            else -> error("BindingTable: unknown journal kind '$kind'")
        }
    }

    /** fsync the new record so it survives a crash the moment [bind]/[unbind] admits it. */
    private fun append(record: Record) {
        val file = journalFile ?: return
        file.parentFile?.mkdirs()
        val line = Json.encodeToString(Record.serializer(), record) + "\n"
        FileOutputStream(file, true).use { out ->
            out.write(line.toByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    companion object {
        /** The durable record inside a table's [journalDir]. */
        const val JOURNAL_FILE = "bindings.jsonl"

        private const val KIND_CLAIM = "claim"
        private const val KIND_RELATION = "relation"

        /**
         * Deterministic, restart-stable ref for a claim key: pure in [key],
         * so two independent tables (fresh directories, or ephemeral) agree
         * on it without ever talking to each other.
         */
        fun refFor(key: ClaimKey): CellRef =
            CellRef(UUID.nameUUIDFromBytes("dialogue:claim:${key.value}".toByteArray()))

        /**
         * Deterministic, restart-stable ref for a relation key. The
         * `dialogue:relation:` prefix keeps this namespace disjoint from
         * [refFor] for [ClaimKey] even when the two keys' string values are
         * equal, and from the pipeline's own `namespace:handle` cell refs
         * and agora's `agora:hub` (none share this class's prefixes).
         */
        fun refFor(key: RelationKey): CellRef =
            CellRef(UUID.nameUUIDFromBytes("dialogue:relation:${key.value}".toByteArray()))
    }
}

/** The reverse of [BindingTable.refOf] — which kind of key a ref is bound to. */
sealed interface BoundKey {
    data class OfClaim(val key: ClaimKey) : BoundKey
    data class OfRelation(val key: RelationKey) : BoundKey
}
