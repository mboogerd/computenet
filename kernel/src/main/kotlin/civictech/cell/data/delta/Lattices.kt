package civictech.cell.data.delta

/**
 * The shared pointwise-max fold (T07 finding 4, DRY audit): every per-key
 * `Long` lattice in this package — [PnCounterDelta]'s per-source cumulative
 * totals, [WatermarkDelta]'s per-(replica,source) delivered frontier and its
 * per-slot suspend epoch — merges by taking, key by key, the max of the two
 * sides against a declared [identity] for the key each side doesn't carry.
 * Commutative, associative, idempotent: the join-semilattice every one of
 * these deltas relies on for gossip echoes to die out (spec 24/42).
 *
 * [identity] is deliberately a parameter, not a hardcoded default, because the
 * two current callers need different bottoms and picking one silently for
 * both is exactly the drift this extraction closes:
 * - [PnCounterDelta] uses `0L` — a source that has never incremented/decremented
 *   has contributed nothing, so "absent" and "zero" coincide.
 * - [WatermarkDelta] uses `Long.MIN_VALUE` — an as-yet-undelivered row or a
 *   never-suspended slot must read as **bottom**, strictly below any real
 *   counter/epoch; treating "absent" as `0L` there would let a freshly-joined,
 *   not-yet-caught-up member's row look delivered-through-zero instead of
 *   not-yet-known, corrupting the covering-quorum read
 *   ([civictech.cell.replication.Replication.replicaFrontier]).
 */
internal fun <K> mergeMax(a: Map<K, Long>, b: Map<K, Long>, identity: Long): Map<K, Long> =
    (a.keys + b.keys).associateWith { maxOf(a[it] ?: identity, b[it] ?: identity) }
