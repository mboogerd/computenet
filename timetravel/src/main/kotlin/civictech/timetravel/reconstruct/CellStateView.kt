package civictech.timetravel.reconstruct

import java.io.Serializable

/**
 * `:timetravel`'s own minimal, deterministic rendering of a reconstructed cell's state —
 * modelled on `inspect/src/main/kotlin/civictech/inspect/ValueEncoder.kt`'s vocabulary
 * (`scalar | [Value] | {"k": Value}`) without depending on `:inspect`
 * (`ModuleDependencyTest`; TTD2 adapts this to `ValueEncoder` later). TTD1 F4 (`computenet-6tm33`
 * D13).
 *
 * [kind] names the shape (`"map"`, `"set"`, `"list"`, `"scalar"`), [summary] is a one-line count
 * or value, and [entries] is the flattened key/value pairs, each rendered one level deep by
 * `toString()`.
 */
data class CellStateView(
    val kind: String,
    val summary: String,
    val entries: List<Pair<String, String>>,
) {
    companion object {
        /**
         * Renders [value] per the rules pinned by `computenet-6tm33` D13:
         * - a [Map] renders as `"map"`, `"<n> entries"`, entries `(key.toString(), value.toString())`
         *   sorted by the key's string form;
         * - a [Collection] renders as `"set"` (for a [Set]) or `"list"` (otherwise), `"<n> rows"`,
         *   entries `("<i>", element.toString())` — iteration order for a list, sorted by the
         *   element's string form for a set;
         * - anything else renders as `"scalar"`, `value.toString()`, a single `("value", ...)` entry.
         */
        fun of(value: Serializable): CellStateView = when (value) {
            is Map<*, *> -> {
                val entries = value.entries
                    .map { (k, v) -> k.toString() to v.toString() }
                    .sortedBy { it.first }
                CellStateView(kind = "map", summary = "${entries.size} entries", entries = entries)
            }
            is Set<*> -> {
                val rendered = value.map { it.toString() }.sorted()
                CellStateView(
                    kind = "set",
                    summary = "${rendered.size} rows",
                    entries = rendered.mapIndexed { i, s -> i.toString() to s },
                )
            }
            is Collection<*> -> {
                val rendered = value.map { it.toString() }
                CellStateView(
                    kind = "list",
                    summary = "${rendered.size} rows",
                    entries = rendered.mapIndexed { i, s -> i.toString() to s },
                )
            }
            else -> CellStateView(kind = "scalar", summary = value.toString(), entries = listOf("value" to value.toString()))
        }
    }
}
