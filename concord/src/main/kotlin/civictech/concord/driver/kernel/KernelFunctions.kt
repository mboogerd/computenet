package civictech.concord.driver.kernel

import civictech.cell.data.Aggregator
import civictech.cell.data.Aggregators

/**
 * The Concord **function catalog**, bound kernel-side (W3-0, CONCORD-PLAN §1.2
 * function-catalog.md). The oracle (`civictech.concord.oracle.Functions`) implements
 * the identical semantics over the neutral [civictech.concord.value.Value] model;
 * this object implements them over the **unwrapped Kotlin values** the kernel cells
 * actually hold (the driver unwraps a scenario [Value] to `String`/`Long`/`Double`/
 * `Boolean`/`List<Any?>` before an op or a link carries it — see
 * [KernelCatalog.unwrap]). A divergence between the two surfaces as an
 * `incremental-equals-batch` failure, which is a catalog-definition bug (P5), so the
 * two must agree element for element.
 *
 * Three kinds, exactly as the catalog: **predicates** `(Any?) -> Boolean` (filter,
 * observation checks); **transforms** `(Any?) -> Any?` (map); **aggregators**, here
 * as kernel [Aggregator] builders (group-by / partition). `combine-latest`'s only
 * bound combiner (`sum`) lives in [ScalarSumCombineCell], not here.
 */
internal object KernelFunctions {

    private data class Call(val name: String, val args: List<String>)

    private fun parse(id: String): Call {
        val open = id.indexOf('(')
        if (open < 0) return Call(id.trim(), emptyList())
        val close = id.lastIndexOf(')')
        val argStr = id.substring(open + 1, if (close > open) close else id.length)
        val args = if (argStr.isBlank()) emptyList() else argStr.split(',').map { it.trim() }
        return Call(id.substring(0, open).trim(), args)
    }

    /**
     * The join/group key of an element: the first component of a pair
     * (`[k, v]`), else the element itself (identity when the element IS its
     * key). Mirrors `oracle.Functions.keyOf`.
     */
    fun keyOf(x: Any?): Any? = if (x is List<*> && x.isNotEmpty()) x[0] else x

    /** The value component of a keyed element (`[k, v]` → `v`), else the element itself. */
    fun valueOf(x: Any?): Any? = if (x is List<*> && x.size >= 2) x[1] else x

    /** Integer projection (long/int/whole-real/parseable-string), or null when not a number. */
    fun asLong(x: Any?): Long? = when (x) {
        is Long -> x
        is Int -> x.toLong()
        is Double -> x.toLong()
        is String -> x.toLongOrNull()
        else -> null
    }

    private fun asDouble(x: Any?): Double? = when (x) {
        is Long -> x.toDouble()
        is Int -> x.toDouble()
        is Double -> x
        is String -> x.toDoubleOrNull()
        else -> null
    }

    /** A stable textual rendering — matches `oracle.Values.render` for scalars (used by `concat`). */
    private fun render(x: Any?): String = when (x) {
        null -> "null"
        is String -> x
        is Long -> x.toString()
        is Int -> x.toLong().toString()
        is Double -> x.toString()
        is Boolean -> x.toString()
        else -> x.toString()
    }

    /** A catalog predicate by id (filter, `observations-all-satisfy`). */
    fun predicate(id: String): (Any?) -> Boolean {
        val c = parse(id)
        return when (c.name) {
            "eq" -> { x -> render(x) == c.args[0] || asDouble(x)?.let { it == c.args[0].toDoubleOrNull() } == true }
            "gt" -> { x -> asDouble(x)?.let { it > c.args[0].toDouble() } ?: false }
            "lt" -> { x -> asDouble(x)?.let { it < c.args[0].toDouble() } ?: false }
            "mod-eq" -> { x -> asLong(x)?.let { Math.floorMod(it, c.args[0].toLong()) == c.args[1].toLong() } ?: false }
            "even" -> { x -> asLong(x)?.let { it % 2L == 0L } ?: false }
            "odd" -> { x -> asLong(x)?.let { Math.floorMod(it, 2L) == 1L } ?: false }
            else -> throw UnsupportedCatalogBinding("not a catalog predicate: '$id'")
        }
    }

    /** A catalog transform by id (map). `key-of` also extracts join/group keys. */
    fun transform(id: String): (Any?) -> Any? {
        val c = parse(id)
        return when (c.name) {
            "identity" -> { x -> x }
            "concat" -> { x -> render(x) + c.args[0] }
            "add" -> { x -> (asLong(x) ?: 0L) + c.args[0].toLong() }
            "key-of" -> { x -> keyOf(x) }
            else -> throw UnsupportedCatalogBinding("not a catalog transform: '$id'")
        }
    }

    /**
     * A catalog aggregator by id as a kernel [Aggregator] over set-stream
     * elements, folding each group's element **value components** (`valueOf`).
     * `count` ignores the values (cardinality); `sum`/`min`/`max` are
     * integer-valued (the catalog's numeric aggregators), matching the oracle's
     * `aggregate(id, group.map { valueOf(it) })`. The aggregate type is always
     * `Long`, so a `count-view` folds the result.
     */
    fun aggregator(id: String): Aggregator<Any?, Long, *> = when (parse(id).name) {
        "count" -> Aggregators.count()
        "sum" -> Aggregators.sumOf { asLong(valueOf(it)) ?: 0L }
        "min" -> Aggregators.minOf { asLong(valueOf(it)) ?: 0L }
        "max" -> Aggregators.maxOf { asLong(valueOf(it)) ?: 0L }
        else -> throw UnsupportedCatalogBinding(
            "not a catalog aggregator: '$id' — group-by/partition accept count|sum|min|max",
        )
    }
}
