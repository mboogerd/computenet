package civictech.cell.data

import java.io.Serializable

/**
 * The paging machinery the six map-/set-backed data cells share (V1C-CELLS):
 * one cursor token shape and one deterministic key order.
 *
 * **Why this is a file and not six copies.** `MapCell`, `KeyedSetCell`,
 * `WatermarkCell`, `InstanceSet` and `ShardCell` all page a keyed collection,
 * and all five need the same two things: a resume token that survives mutation
 * of the underlying map, and a key order that does not depend on how the map
 * happens to be laid out. Six copies of that would be six chances to get the
 * enumeration-order trap wrong in five different ways.
 *
 * **Why it is here.** `civictech.cell.data` is `V1C-CELLS`' own package;
 * `V1C-OPS` owns `civictech.cell.data.op` and does not need to edit this file
 * to use it, which is the constraint the two parallel tickets are split under.
 * It is `internal`, so it is module-private and no public surface grows.
 * `ShardCell` (in `civictech.cell.partition`) reaches it over the
 * `partition -> data` package edge it already has.
 */

/**
 * A cell's cursor token for a keyed walk (V1C-CELLS) — the encoding
 * `civictech.cell.data.SetCell`'s `SetWalk` established, generalized over the
 * five keyed families that copy it.
 *
 * **[order] is the walk's enumeration order, computed once at walk start and
 * frozen for the walk's lifetime**, and **[next] indexes that frozen list, not
 * the live map.** Both halves matter:
 *
 * - *Frozen* is what makes "no key twice in one walk" true. Every backing map
 *   here is a `LinkedHashMap`, so a remove-then-re-add moves a key to the tail
 *   and could hand it to a later page a second time, and `restore()` rebuilds
 *   the map from a `HashMap` and reorders it wholesale.
 * - *Indexed into a frozen list* is what makes a resume O(1) instead of O(n).
 *   The list never changes, so an index into it is as stable as the key it
 *   names — a removal elsewhere in the map shifts nothing — while a rescan of
 *   the live map to re-find the last key would cost O(n) per page and O(n²)
 *   per walk, the shape the C7 measurement gate ruled out.
 *
 * A key that disappears from the live map after the walk opened is simply
 * skipped when the walk reaches its position; a key added after the walk opened
 * is not in [order] and so is not returned, which is the documented smear.
 *
 * [opening] carries a family-specific stamp (a `TagFrontier` for the two
 * tag-minting families) so a walk need not recompute an O(n) frontier per page.
 * Null for a family that has no such stamp.
 */
internal class KeyWalk<K>(
    val order: List<K>,
    val next: Int,
    val opening: Serializable? = null,
) : Serializable

/**
 * The deterministic total order a bounded walk enumerates arbitrary keys in
 * (V1C-CELLS).
 *
 * **Imposed, not inherited.** The alternative — walking the backing
 * `LinkedHashMap` in whatever order it currently holds, frozen at walk start —
 * is stable *within* one instance's lifetime but not across a
 * `snapshot()`/`restore()` round trip, because every one of these cells
 * restores from a `HashMap`/`HashSet`. Two instances holding identical state
 * would then enumerate differently, which makes a walk's order an accident of
 * history rather than a property of the state. This comparator makes the order
 * a function of the keys' *values*, so a restored instance walks identically to
 * the one that wrote the checkpoint.
 *
 * The order, in priority:
 *
 * 1. nulls first (a map key may be null);
 * 2. same runtime class and [Comparable] — natural order. This is the common
 *    and cheap path: `String`, `Int`, `Long`, `UUID` keys sort naturally;
 * 3. different runtime classes — by class name, so a heterogeneous key space is
 *    grouped deterministically rather than interleaved by hash;
 * 4. `hashCode()`, then `toString()`.
 *
 * **The residual, stated rather than hidden.** Two distinct keys of the same
 * non-`Comparable` class that agree on both `hashCode()` and `toString()` are
 * left in encounter order, and a key whose `hashCode()`/`toString()` are
 * identity-derived is not value-stable across a restore. Neither is repairable
 * from outside the key type, and a key of the second kind does not survive a
 * checkpoint round trip as an `equals` value either, so the walk order is the
 * lesser of its problems.
 */
internal object EntryOrder : Comparator<Any?> {

    override fun compare(a: Any?, b: Any?): Int {
        if (a === b) return 0
        if (a == null) return -1
        if (b == null) return 1
        val classA = a.javaClass
        val classB = b.javaClass
        if (classA == classB) {
            if (a is Comparable<*>) {
                @Suppress("UNCHECKED_CAST")
                val natural = (a as Comparable<Any>).compareTo(b)
                if (natural != 0) return natural
            }
        } else {
            val byClass = classA.name.compareTo(classB.name)
            if (byClass != 0) return byClass
        }
        val byHash = a.hashCode().compareTo(b.hashCode())
        if (byHash != 0) return byHash
        return a.toString().compareTo(b.toString())
    }

    /**
     * Freeze [keys] into a walk order: the [admit]-passing keys, sorted by this
     * comparator. One O(n log n) pass per walk — never per page.
     */
    fun <K> freeze(keys: Iterable<K>, admit: (K) -> Boolean): List<K> =
        keys.filterTo(ArrayList()) { admit(it) }.apply { sortWith(EntryOrder) }
}

/**
 * Crude per-entry size estimates for [civictech.cell.StateRead.byteBudget]
 * (V1C-CELLS), matching the register `SetCell` uses: the budget is **advisory**
 * and cell-estimated, so these are rough JVM object sizes, not an encoder's
 * measurement. A cell honours the budget only once its page already carries an
 * entry, so a walk always makes progress.
 */
internal object PageBudget {
    const val ENTRY_OVERHEAD_BYTES = 64
    const val TAG_BYTES = 48

    /** Would adding another entry exceed [byteBudget], given [bytes] already accumulated? */
    fun exhausted(bytes: Int, byteBudget: Int): Boolean = bytes >= byteBudget
}
