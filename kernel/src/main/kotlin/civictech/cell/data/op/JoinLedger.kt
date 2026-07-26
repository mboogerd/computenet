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
 * - [MintedLedger] ([JoinSetCell], [SemiJoinCell]): mints a FRESH [Timestamp]
 *   per entry via [MintedTags] (tag hygiene, 21 — re-entry after the other
 *   side's removal needs a fresh tag, never borrowed from inputs).
 * - [AdvertisedLedger] ([IntersectSetCell]): advertises the union of both
 *   sides' OBSERVED input tags, computed by the caller and passed through
 *   [tagsIfAdvertised] — no minting at all.
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
}

/** Advertises the caller-supplied tag set on entry, verbatim — [IntersectSetCell]'s ledger. */
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
}
