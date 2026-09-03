package civictech.dialogue.apply

import civictech.cell.CellRef

/**
 * Which canonical population a recorded op or failure belongs to.
 *
 * [STANCE] is a *data* write, not a structure op — it never creates or
 * removes an agora node — but it can be rejected exactly as a structure write
 * can (an unknown node, an out-of-range value), and `[AGO1-APPLY-07]`'s
 * no-silent-drop rule covers it too, so it gets its own kind rather than
 * being folded into [CLAIM].
 */
enum class ApplyKind { CLAIM, RELATION, STANCE }

/**
 * One agora *structure* operation [GraphApplier.reconcile] issued and which
 * returned normally (epic computenet-2aw §3.4 [AGO1-APPLY-02]).
 *
 * The order of these within a [ReconcileReport] is the applier's fixed
 * emission order — relation removals, claim removals, claim creates, relation
 * creates — and is asserted, not incidental: removing an edge before its
 * endpoint claim is what keeps the applier from relying on agora's own
 * dangling-edge cascade for writes it is itself responsible for.
 */
data class ApplyOp(val kind: OpKind, val key: String, val ref: CellRef) {
    enum class OpKind { REMOVE_RELATION, REMOVE_CLAIM, CREATE_CLAIM, CREATE_RELATION }
}

/**
 * One agora write that was rejected, recorded against the offending key
 * ([AGO1-APPLY-06]).
 *
 * [key] is the canonical key's string form — `ClaimKey.value`,
 * `RelationKey.value`, or `"<speaker>@<claim key>"` for a stance — rather
 * than the typed key, because the three populations share one ledger and the
 * ledger is a status surface F5 serves.
 */
data class ApplyFailure(val kind: ApplyKind, val key: String, val reason: String)

/**
 * What one [GraphApplier.reconcile] call did: the structure ops it issued
 * *this call*, the failures it recorded this call, and how many stance writes
 * it made.
 *
 * Deliberately per-call, where [ApplyAccounting] is cumulative:
 * [AGO1-APPLY-02]'s "zero structure operations on an unchanged canonical set"
 * is a statement about one call, and a cumulative counter alone cannot
 * express it without the caller remembering a previous reading.
 */
data class ReconcileReport(
    val ops: List<ApplyOp>,
    val failures: List<ApplyFailure>,
    val stanceWrites: Int,
) {
    /** Creates + removes issued this call that returned normally. */
    val structureOps: Int get() = ops.size
}

/**
 * The applier's cumulative ledger ([AGO1-APPLY-06]/-07): every structure op
 * it has issued and every write agora rejected, across all reconciles.
 *
 * The app-level analogue of the kernel's no-silent-drop accounting — a
 * canonical item that cannot be applied leaves a record here rather than
 * disappearing. Mutations are single-threaded by construction (only
 * [GraphApplier.reconcile] writes, and the applier is the sole writer into
 * the agora graph, invoked from the driver at quiescence), so no locking.
 */
class ApplyAccounting {
    private val _failures = mutableListOf<ApplyFailure>()

    /** Structure ops issued across every reconcile that returned normally. */
    var structureOps: Int = 0
        private set

    /** Stance writes issued across every reconcile that returned normally. */
    var stanceWrites: Int = 0
        private set

    /** Every rejection recorded so far, in the order they were recorded. */
    val failures: List<ApplyFailure> get() = _failures.toList()

    /** The failures recorded against [kind], in record order. */
    fun failures(kind: ApplyKind): List<ApplyFailure> = _failures.filter { it.kind == kind }

    /** Whether any write against [key] of [kind] was rejected. */
    fun failed(kind: ApplyKind, key: String): Boolean =
        _failures.any { it.kind == kind && it.key == key }

    internal fun recordOp() {
        structureOps++
    }

    internal fun recordStance() {
        stanceWrites++
    }

    internal fun record(failure: ApplyFailure) {
        _failures += failure
    }
}
