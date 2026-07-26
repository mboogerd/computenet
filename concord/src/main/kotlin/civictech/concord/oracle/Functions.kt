package civictech.concord.oracle

import civictech.concord.value.Value

/**
 * The Concord function catalog (CONCORD-PLAN function-catalog.md, v1) implemented
 * once for the harness — the batch oracle uses these, and every driver must bind
 * the identical semantics (a divergence surfaces as an `incremental-equals-batch`
 * failure, which is a catalog-definition bug). Pure and neutral: `Value` in,
 * `Value`/`Boolean` out.
 *
 * Parameterised ids carry their arg(s) in the id — `gt(3)`, `mod-eq(2,0)`,
 * `concat(-x)`, `add(5)`, `eq(apple)`. Three kinds:
 * - **predicates** (`(Value) -> Boolean`): `eq`, `gt`, `lt`, `mod-eq`, `even`, `odd`;
 * - **transforms** (`(Value) -> Value`): `identity`, `concat`, `add`, `key-of`;
 * - **aggregators** (`(List<Value>) -> Value`): `sum`, `min`, `max`, `count`.
 */
object Functions {

    private data class Call(val name: String, val args: List<String>)

    private fun parse(id: String): Call {
        val open = id.indexOf('(')
        if (open < 0) return Call(id.trim(), emptyList())
        val close = id.lastIndexOf(')')
        val argStr = id.substring(open + 1, if (close > open) close else id.length)
        val args = if (argStr.isBlank()) emptyList() else argStr.split(',').map { it.trim() }
        return Call(id.substring(0, open).trim(), args)
    }

    /** Widen a string arg to a [Value] (int -> real -> bool -> string), matching the YAML core-schema widening. */
    private fun widen(s: String): Value {
        s.toLongOrNull()?.let { return Value.IntVal(it) }
        s.toDoubleOrNull()?.let { return Value.RealVal(it) }
        return when (s) {
            "true" -> Value.BoolVal(true)
            "false" -> Value.BoolVal(false)
            else -> Value.StrVal(s)
        }
    }

    /** A catalog predicate by id (usable by `filter` and `observations-all-satisfy`). */
    fun predicate(id: String): (Value) -> Boolean {
        val c = parse(id)
        return when (c.name) {
            "eq" -> { x: Value -> x == widen(c.args[0]) }
            "gt" -> { x: Value -> Values.asDouble(x)?.let { it > c.args[0].toDouble() } ?: false }
            "lt" -> { x: Value -> Values.asDouble(x)?.let { it < c.args[0].toDouble() } ?: false }
            "mod-eq" -> { x: Value -> Values.asLong(x)?.let { Math.floorMod(it, c.args[0].toLong()) == c.args[1].toLong() } ?: false }
            "even" -> { x: Value -> Values.asLong(x)?.let { it % 2L == 0L } ?: false }
            "odd" -> { x: Value -> Values.asLong(x)?.let { Math.floorMod(it, 2L) == 1L } ?: false }
            else -> error("not a catalog predicate: $id")
        }
    }

    /** A catalog transform by id (usable by `map`; `key-of` also extracts join/group keys). */
    fun transform(id: String): (Value) -> Value {
        val c = parse(id)
        return when (c.name) {
            "identity" -> { x: Value -> x }
            "concat" -> { x: Value -> Value.StrVal(Values.render(x) + c.args[0]) }
            "add" -> { x: Value -> Value.IntVal((Values.asLong(x) ?: 0L) + c.args[0].toLong()) }
            "key-of" -> { x: Value -> keyOf(x) }
            else -> error("not a catalog transform: $id")
        }
    }

    /**
     * Extract the join/group key of an element: the first component of a pair
     * (`[k, v]`), else the element itself (identity when the element *is* its key).
     */
    fun keyOf(x: Value): Value = if (x is Value.ListVal && x.items.isNotEmpty()) x.items[0] else x

    /** The value component of a keyed element (`[k, v]` -> `v`), else the element itself. */
    fun valueOf(x: Value): Value = if (x is Value.ListVal && x.items.size >= 2) x.items[1] else x

    /** A catalog aggregator by id (usable by `group-by` and `combine-latest`). */
    fun aggregate(id: String, values: List<Value>): Value {
        return when (parse(id).name) {
            "sum" -> {
                if (values.all { it is Value.IntVal }) {
                    Value.IntVal(values.sumOf { (it as Value.IntVal).value })
                } else {
                    Value.RealVal(values.sumOf { Values.asDouble(it) ?: 0.0 })
                }
            }
            "min" -> values.minWithOrNull(Values::compare) ?: Value.NullVal
            "max" -> values.maxWithOrNull(Values::compare) ?: Value.NullVal
            "count" -> Value.IntVal(values.size.toLong())
            else -> error("not a catalog aggregator: $id")
        }
    }
}
