package civictech.oracle.gen

/**
 * The static element tables a generated script draws its payloads from, sized by
 * `GeneratorConfig.elementDomainSize`.
 *
 * ## Why static tables rather than generated values
 *
 * Two independent reasons, only one of which is about serializability:
 *
 * - **Determinism** is the one that binds *this* file: `[ORA1-GEN-01]` requires identical
 *   `(seed, config)` to produce an identical case across JVMs and machines, so an element
 *   value may not depend on anything the JVM chooses — no `hashCode`, no identity, no
 *   `UUID`, no iteration order of a `HashSet`. A table indexed by an `Int` cannot.
 * - **Serializability** (epic D3) is the reason the *graph* side uses the same idiom
 *   (`GenerativeGraphTest`'s `companion object { val PREDICATES }`): a factory lambda may
 *   capture an index into a static table and stay serializable. A script carries its values
 *   inline, so that argument does not bite here — but keeping one idiom for both halves
 *   means a value can move between them without changing shape.
 *
 * Every entry is a `String`: `equals`/`hashCode`-sound (which `ScriptEvent`'s KDoc requires
 * of a payload, because membership is set membership), cheap to serialize, and readable in a
 * failing case's dump — `"e07"` says more about which element diverged than `7` does when a
 * counter amount is printed beside it.
 *
 * ## Sizing
 *
 * [elements], [keys] and [amounts] return exactly the requested size. The first
 * [TABLE_SIZE] of each are precomputed; a larger request is extended by the same formula, so
 * a domain of 200 and a domain of 20 agree on their first 20 entries — a domain size is a
 * *prefix* choice, not a different alphabet. That prefix property is what makes the
 * element-domain knob's test meaningful: shrinking the knob shrinks the value set rather
 * than replacing it.
 */
object ElementDomains {

    /** How many entries of each domain are precomputed. Beyond this the formula continues. */
    const val TABLE_SIZE: Int = 64

    /** The element domain: `e00`, `e01`, … — the payload of `Add`/`Remove`/`Put`. */
    val ELEMENTS: List<String> = table("e")

    /** The key domain: `k00`, `k01`, … — the key of `Put`/`RemoveKey`. */
    val KEYS: List<String> = table("k")

    /** The counter amount domain: `1L`, `2L`, … — the amount of `Increment`/`Decrement`. */
    val AMOUNTS: List<Long> = (1..TABLE_SIZE).map { it.toLong() }

    /** The first [size] elements of [ELEMENTS]. */
    fun elements(size: Int): List<String> = prefix(ELEMENTS, size) { label("e", it) }

    /** The first [size] keys of [KEYS]. */
    fun keys(size: Int): List<String> = prefix(KEYS, size) { label("k", it) }

    /** The first [size] counter amounts of [AMOUNTS] — `1..size`. */
    fun amounts(size: Int): List<Long> = prefix(AMOUNTS, size) { (it + 1).toLong() }

    private fun table(prefix: String): List<String> = (0 until TABLE_SIZE).map { label(prefix, it) }

    private fun label(prefix: String, index: Int): String = prefix + index.toString().padStart(2, '0')

    private fun <T> prefix(table: List<T>, size: Int, extend: (Int) -> T): List<T> {
        require(size > 0) { "Element domain size must be positive: $size" }
        return if (size <= table.size) table.subList(0, size) else List(size) { if (it < table.size) table[it] else extend(it) }
    }
}
