package civictech.concord.value

/**
 * The neutral, JSON-shaped value model shared by L1 (scenario schema) and L3
 * (driver SPI). Scalars, arrays and objects only — no Kotlin types, no cell
 * references, nothing implementation-specific (Concord P5). Golden expectations
 * in checks, op payloads in `apply` steps, and `readView` returns are all this
 * type, so the harness can compare a driver's answer to a scenario's golden
 * without either side knowing the other's language.
 *
 * A number is split into [IntVal] and [RealVal] deliberately: a golden of `100`
 * (an increment count) must not silently equal `100.0`, and the batch oracle
 * (W1-B) folds integer counts. YAML's untyped scalars are widened to the
 * narrowest of int → real → bool → string on decode (see the test-source
 * `ValueYaml` serializer).
 *
 * Serialization lives outside `main` on purpose: every YAML front end (kaml,
 * today) is test-scope because the runner is a test harness, so the concrete
 * `KSerializer<Value>` is provided by the parsing layer and this model stays a
 * pure, dependency-free data type. Schema fields that hold a [Value] are marked
 * `@Contextual`; the parsing layer registers the serializer in its
 * `SerializersModule`.
 */
sealed interface Value {

    /** A string scalar (`apple`, `pear`). */
    data class StrVal(val value: String) : Value

    /** An integer scalar (`100`, `-3`). */
    data class IntVal(val value: Long) : Value

    /** A floating-point scalar (`1.5`). */
    data class RealVal(val value: Double) : Value

    /** A boolean scalar (`true`, `false`). */
    data class BoolVal(val value: Boolean) : Value

    /** The explicit null / absent value. */
    data object NullVal : Value

    /** An ordered array of values (`[pear, plum]`). */
    data class ListVal(val items: List<Value>) : Value

    /** A string-keyed object (`{k: v}`). */
    data class MapVal(val entries: Map<String, Value>) : Value

    companion object {
        /** Wrap a Kotlin value as a [Value] — convenience for oracles and drivers. */
        fun of(v: Any?): Value = when (v) {
            null -> NullVal
            is Value -> v
            is String -> StrVal(v)
            is Boolean -> BoolVal(v)
            is Int -> IntVal(v.toLong())
            is Long -> IntVal(v)
            is Double -> RealVal(v)
            is Float -> RealVal(v.toDouble())
            is List<*> -> ListVal(v.map { of(it) })
            is Map<*, *> -> MapVal(v.entries.associate { (k, value) -> k.toString() to of(value) })
            else -> StrVal(v.toString())
        }
    }
}
