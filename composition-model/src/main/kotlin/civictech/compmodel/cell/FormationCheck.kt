package civictech.compmodel.cell

/**
 * The formation rules of COH §2.6 that constrain layer ORDER (F1-F6), stated over the
 * layer properties of COH §2.5 as the note states them, and the leaf rules the model
 * exercises (F8, F12). F7 (no bypass), F10 (exclusive-payload holders) and F11 (effect
 * identity) are not order rules; F11 is exercised by the promotion model.
 *
 * This is the note's rule set transcribed, NOT tuned to the model: `OrderValidity`
 * compares it with what the fault exploration accepts and reports every disagreement.
 */
object FormationCheck {

    data class Refusal(val rule: String, val reason: String)

    fun check(stack: List<Layer>): List<Refusal> {
        val out = ArrayList<Refusal>()
        fun idx(l: Layer) = stack.indexOf(l)
        val d = idx(Layer.DURABLE); val s = idx(Layer.SUPERVISED); val x = idx(Layer.EFFECT_DEDUP)
        val p = idx(Layer.SUSPENDABLE); val a = idx(Layer.ALIGN); val f = idx(Layer.FENCE)

        // F1 "Persistence outermost. If D is present, no layer outside it holds custody or monotone state."
        if (d >= 0) for (i in 0 until d) {
            val l = stack[i]
            if (l.custody || l.monotone) out.add(Refusal("F1", "${l.code} (custody=${l.custody}, monotone=${l.monotone}) outside D"))
        }
        // F2 "Restart innermost. S directly wraps the leaf; no layer with custody or monotone state sits inside S."
        if (s >= 0) {
            if (s != stack.lastIndex) out.add(Refusal("F2", "S does not directly wrap the leaf"))
            for (i in s + 1 until stack.size) {
                val l = stack[i]
                if (l.custody || l.monotone) out.add(Refusal("F2", "${l.code} with custody/monotone state inside S"))
            }
        }
        // F3 "Dedup adjacent to delivery. X is immediately outside S."
        if (x >= 0 && s >= 0 && x != s - 1) out.add(Refusal("F3", "X is not immediately outside S"))
        // F4 "Admission before alignment. P is outside A."
        if (p >= 0 && a >= 0 && p > a) out.add(Refusal("F4", "P inside A"))
        // F5 "Fence before alignment and dedup. F is outside A and X."
        if (f >= 0 && a >= 0 && f > a) out.add(Refusal("F5", "F inside A"))
        if (f >= 0 && x >= 0 && f > x) out.add(Refusal("F5", "F inside X"))
        // F6 "each layer appears at most once on any root-to-leaf path."
        if (stack.toSet().size != stack.size) out.add(Refusal("F6", "a layer appears twice"))
        return out
    }

    fun accepts(stack: List<Layer>): Boolean = check(stack).isEmpty()

    /**
     * Leaf rules over the stack:
     * - F8 "A leaf that is both Effectful and Stateful is refused under D, pending M1."
     * - F12 (added for model finding CELL-1) "Enforcing inlets carry X": a durable term whose
     *   leaf is not idempotent (RELAY, COUNTER) and has no `X` is refused while its RESTART is a
     *   succession, which until M11 is every RESTART.
     * F9 is not a stack rule here: the cell model's leaves are replay-deterministic and the
     * `Effectful` leaf with an outlet lives in the promotion model, which encodes F9's revised
     * text (outputs caused through an `X`-suppressed inlet are logged).
     */
    fun leafRefusal(stack: List<Layer>, leaf: LeafKind): String? = when {
        Layer.DURABLE in stack && leaf == LeafKind.EFFECT_STATEFUL -> "F8"
        Layer.DURABLE in stack && leaf in setOf(LeafKind.RELAY, LeafKind.COUNTER) && Layer.EFFECT_DEDUP !in stack -> "F12"
        else -> null
    }
}

/** All permutations of [items], in a deterministic order. */
fun <T> permutations(items: List<T>): List<List<T>> =
    if (items.size <= 1) listOf(items)
    else items.indices.flatMap { i ->
        val rest = items.filterIndexed { j, _ -> j != i }
        permutations(rest).map { listOf(items[i]) + it }
    }
