package civictech.oracle.model

/**
 * Observed-remove membership, defined on the **script** (`[ORA1-MODEL-04]`, `[ORA1-MODEL-05]`;
 * spec `[24-SET-01]`, `[24-SET-03]`).
 *
 * ## The rule
 *
 * For one source's ordered log:
 *
 * - An **add** at position `i` is *covered* iff some **remove** of the same element at a
 *   later position `k > i` was issued by a writer that had **observed** the add at `i`.
 * - An element is **live** iff it has at least one *uncovered* add.
 * - A remove that observed no add of its element is therefore a no-op, automatically — it
 *   covers nothing.
 *
 * A writer `w` has observed the add at position `i` iff either
 *
 * - `w` issued it (a writer observes its own adds at issue time), or
 * - the log carries an [ScriptEvent.Observe] by `w` at some position `j` with `i < j < k`
 *   — the script-level statement that `w` has seen everything up to `j`.
 *
 * ## Why this is the right instrument
 *
 * The kernel gets add-wins by tag-set union: a remove retracts only the tags it observed, so
 * a concurrent add's tag survives (`[24-SET-03]`). This computes the *same* membership from
 * causality alone — no tag, no tag count, no wave id, no `SetDelta` internal appears
 * (`[ORA1-MODEL-03]`). That independence is the point: a model that re-implemented the tag
 * algebra would agree with the kernel about a shared bug.
 *
 * Two consequences worth naming because they are exactly BS-2 and BS-3:
 *
 * - **BS-2** — writer `A` adds `x`, writer `B` (which never observed it) removes `x`: `B`'s
 *   remove covers nothing, so `x` stays live. Nothing about `B` is remembered afterwards.
 * - **BS-3** — `add(x)`, an observed `remove(x)`, `add(x)` again: the second add is at a
 *   *later* position than the remove, so the remove cannot cover it, and `x` is live. The
 *   kernel gets the same answer by minting a fresh tag; the model never models that tag, and
 *   agrees anyway.
 *
 * The ordering condition `k > i` and the observation condition are the two independent
 * halves of the rule, and each is load-bearing on its own: dropping the ordering condition
 * breaks BS-3 while leaving BS-2 correct, and dropping the observation condition breaks BS-2
 * while leaving BS-3 correct. `MembershipSemanticsTest` mutation-checks both directions.
 *
 * ## Cost
 *
 * `O(events^2)` in the worst case (each remove scans the adds before it). Scripts are
 * generated cases of hundreds of events, not production traffic, and a transparently correct
 * fold is worth more here than a fast one — this is the thing the kernel is checked against.
 */
object Membership {

    /**
     * The live element set of one source's [events], per the rule in this object's KDoc.
     *
     * Only [ScriptEvent.Add], [ScriptEvent.Remove] and [ScriptEvent.Observe] participate;
     * any other event in the slice is ignored, so a mixed-vocabulary slice does not fail
     * here — the source model that owns the slice decides what it accepts.
     */
    fun live(events: List<ScriptEvent>): Set<Any?> {
        val liveElements = LinkedHashSet<Any?>()

        // adds[element] = that element's adds, in log order. Only adds recorded SO FAR are
        // visible to a remove, which is how the rule's `k > i` ordering condition is
        // enforced: a remove cannot reach an add that has not happened yet, and that is
        // exactly why BS-3's re-add survives.
        val adds = LinkedHashMap<Any?, MutableList<Add>>()

        events.forEachIndexed { position, event ->
            when (event) {
                is ScriptEvent.Add ->
                    adds.getOrPut(event.element) { mutableListOf() } += Add(position, event.writer)

                is ScriptEvent.Remove -> {
                    val issued = adds[event.element] ?: return@forEachIndexed
                    issued.forEach { add ->
                        if (!add.covered && observes(events, add, by = event.writer, before = position)) {
                            add.covered = true
                        }
                    }
                }

                else -> Unit
            }
        }

        adds.forEach { (element, issued) -> if (issued.any { !it.covered }) liveElements += element }
        return liveElements
    }

    /** The live set of a whole [SourceScript] — [live] over its events. */
    fun live(slice: SourceScript): Set<Any?> = live(slice.events)

    /** One add's position and issuing writer, plus whether some later observed remove covered it. */
    private class Add(val position: Int, val writer: WriterId) {
        var covered: Boolean = false
    }

    /**
     * Whether [by] had observed the add at [add] by the time position [before] was reached:
     * it issued the add itself, or it declared an [ScriptEvent.Observe] strictly between the
     * add and the remove.
     */
    private fun observes(events: List<ScriptEvent>, add: Add, by: WriterId, before: Int): Boolean {
        if (add.writer == by) return true
        for (position in (add.position + 1) until before) {
            val event = events[position]
            if (event is ScriptEvent.Observe && event.writer == by) return true
        }
        return false
    }
}
