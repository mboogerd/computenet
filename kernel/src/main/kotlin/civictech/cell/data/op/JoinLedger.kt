package civictech.cell.data.op

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.delta.MintedTags
import civictech.cell.data.delta.SetDelta
import java.io.Serializable

/**
 * Output-tag ledger for a binary set join's emitted rows (RS-5.3): the shared
 * SHAPE (advertise-once-then-idempotent entry, exact-tag exit, snapshot/
 * restore) held by [KeyedBinarySetJoin]-based operators and [IntersectSetCell]
 * — injected, not unified. [enter]/[exit] only record and retrieve; they never
 * decide the tag-assignment POLICY, which stays genuinely different per
 * implementation:
 * - [MintedLedger] ([JoinSetCell], [SemiJoinCell], [IntersectSetCell],
 *   [QuorumSetCell]): mints a FRESH [Timestamp] per entry via [MintedTags] (tag
 *   hygiene, 21 — re-entry after the other side's removal needs a fresh tag,
 *   never borrowed from inputs). [IntersectSetCell] moved here from
 *   [AdvertisedLedger] in computenet-vvre and [QuorumSetCell] in
 *   computenet-s6l2; see their KDocs for why borrowing is unsound across a
 *   reconvergent path.
 * - [AdvertisedLedger] (**no production user since computenet-s6l2**):
 *   advertises the OBSERVED input tags its caller computes and passes through
 *   [tagsIfAdvertised] — no minting at all.
 *
 * ### When borrowing is sound (the [AdvertisedLedger] precondition)
 *
 * Borrowing an input tag is sound only when **both** hold, and an operator
 * that cannot state both belongs on [MintedLedger]:
 *
 * 1. **No downstream can see the borrowed tag by a second path.** The
 *   consumers in this kernel fold a tagged set by `(element, tag)` — a
 *   [UnionSetCell] deduplicates a diamond fan-in into ONE fact by design
 *   (`[24-OP-UNION-01]`) — so a borrowed tag arriving both directly and
 *   re-advertised is one fact, and this ledger's exit retracts the *direct*
 *   path's still-live contribution along with its own. This is a property of
 *   the GRAPH, not of the operator, so an operator can only satisfy it by
 *   forbidding reconvergence, which nothing here does.
 * 2. **Every membership flip-ON rides a fresh input add-tag on the flipping
 *   element** (21 §Tag hygiene's own precondition for pass-through). An
 *   operator whose membership flips ON because some *other* input moved — an
 *   intersection, a quorum, a join — re-advertises on entry a tag its previous
 *   exit already deleted, which 21 prohibits outright and which a
 *   tombstone-folding consumer drops on the floor.
 *
 * `filter`/`map`/`flatMap`-shaped operators satisfy (2) trivially and are the
 * only pass-through shapes 24's convergence classes endorse; both operators
 * that once used this ledger failed (2) and were measured failing (1)
 * (computenet-vvre, computenet-s6l2).
 */
interface JoinLedger<X> {
    val isEmpty: Boolean

    /** Current advertisements as a delta-from-empty (G-22) — the catch-up emission. */
    val entries: Map<X, Set<Timestamp>>

    /**
     * Called when [x] becomes wanted. Returns the newly-advertised tag(s), or
     * null if already advertised (idempotent). [tagsIfAdvertised] supplies the
     * tags to advertise for an implementation that doesn't mint its own —
     * [MintedLedger] ignores it.
     */
    fun enter(x: X, tagsIfAdvertised: () -> Set<Timestamp>): Set<Timestamp>?

    /** Called when [x] stops being wanted. Returns the retracted tag(s), or null if not advertised. */
    fun exit(x: X): Set<Timestamp>?

    fun asDelta(): SetDelta<X> = SetDelta(adds = entries)

    fun snapshot(): Serializable
    fun restore(saved: Serializable)

    // ------------------------------------------------------- bounded read
    // V1C-OPS: the three read accessors a paged walk of a ledger sub-state
    // needs. A ledger pages *through its owning cell* (the cell declares the
    // sub-state ordinals, and a second paging interface in this package would
    // invite two orderings for one walk), so this grows read accessors rather
    // than a `readBounded`. [entries] cannot serve: both implementations build
    // a fresh map on every call, so a per-page use of it would be O(n) per page
    // and O(n²) per walk — the shape `BoundedStateful` rules out.

    /**
     * Live view of the advertised keys (V1C-OPS) — no copy, unlike [entries],
     * so a walk can freeze the key order at O(n) *references* at walk start.
     */
    val advertisedKeys: Set<X>

    /** The tags advertised for [x], or null if [x] is not advertised (V1C-OPS). O(1). */
    fun tagsOf(x: X): Set<Timestamp>?

    /**
     * Cell-level scalar state of this ledger that is part of its [snapshot] but
     * is not an entry, keyed for [civictech.cell.StatePage.attributes]
     * (V1C-OPS, Decision D). Empty for a ledger that holds none.
     *
     * Evaluated **twice per walk** (opening page and closing page), never per
     * page: an implementation is free to pay an O(n) copy here.
     */
    fun readerAttributes(): Map<String, Serializable> = emptyMap()
}

/** Mints a fresh [Timestamp] per entry via [MintedTags] — [JoinSetCell]/[SemiJoinCell]'s ledger. */
class MintedLedger<X>(ref: CellRef, name: String) : JoinLedger<X> {
    private val minted = MintedTags<X>(ref, name)

    override val isEmpty: Boolean get() = minted.isEmpty
    override val entries: Map<X, Set<Timestamp>> get() = minted.entries.mapValues { setOf(it.value) }

    override fun enter(x: X, tagsIfAdvertised: () -> Set<Timestamp>): Set<Timestamp>? =
        minted.enter(x)?.let { setOf(it) }

    override fun exit(x: X): Set<Timestamp>? = minted.exit(x)?.let { setOf(it) }

    override fun snapshot(): Serializable = minted.snapshot()
    override fun restore(saved: Serializable) = minted.restore(saved)

    /** [MintedTags.entries] is the live advertisement map itself, so this copies nothing. */
    override val advertisedKeys: Set<X> get() = minted.entries.keys

    override fun tagsOf(x: X): Set<Timestamp>? = minted.entries[x]?.let { setOf(it) }

    /**
     * The mint counter (V1C-OPS, Decision D): `MintedTags.snapshot()` is
     * `[advertised, counter]` and the counter is genuinely state — a restored
     * instance must not re-mint a spent tag ([MintedTags]) — so a walk whose
     * union is to equal `snapshot()`'s content has to carry it, and on *every*
     * page, since a caller may join a walk at page 4 or abandon it at page 1.
     *
     * Read out of [snapshot] because [MintedTags] exposes the counter nowhere
     * else and `civictech.cell.data.delta` is outside V1C-OPS's file claim; the
     * cost is one shallow map copy, twice per walk, inside passes that are
     * already O(n). A one-line accessor on [MintedTags] would remove it.
     */
    override fun readerAttributes(): Map<String, Serializable> =
        mapOf(OperatorPaging.MINT_COUNTER to (minted.snapshot() as List<*>)[1] as Serializable)
}

/**
 * Advertises the caller-supplied tag set on entry, verbatim.
 *
 * **No production cell uses this since computenet-s6l2** — [QuorumSetCell] was
 * the last, and moved to [MintedLedger] for the reasons the interface KDoc's
 * "When borrowing is sound" section states. It is kept as the borrowing half of
 * the injected-policy seam this interface exists to express; a new user must
 * satisfy BOTH preconditions there, which no operator in this package does.
 */
class AdvertisedLedger<X> : JoinLedger<X> {
    private val advertised = mutableMapOf<X, Set<Timestamp>>()

    override val isEmpty: Boolean get() = advertised.isEmpty()
    override val entries: Map<X, Set<Timestamp>> get() = advertised.toMap()

    override fun enter(x: X, tagsIfAdvertised: () -> Set<Timestamp>): Set<Timestamp>? {
        if (x in advertised) return null
        val tags = tagsIfAdvertised()
        advertised[x] = tags
        return tags
    }

    override fun exit(x: X): Set<Timestamp>? = advertised.remove(x)

    override fun snapshot(): Serializable = HashMap(advertised)

    @Suppress("UNCHECKED_CAST")
    override fun restore(saved: Serializable) {
        advertised.clear()
        advertised.putAll(saved as Map<X, Set<Timestamp>>)
    }

    /** The advertisement map's own key set, so this copies nothing (V1C-OPS). */
    override val advertisedKeys: Set<X> get() = advertised.keys

    override fun tagsOf(x: X): Set<Timestamp>? = advertised[x]

    // readerAttributes(): none. `snapshot()` is a bare map — this ledger
    // advertises the caller's observed input tags and mints nothing, so it has
    // no scalar to carry (V1C-OPS, Decision D; contrast MintedLedger).
}
