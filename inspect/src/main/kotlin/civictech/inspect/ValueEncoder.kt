package civictech.inspect

import civictech.cell.Timestamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic encoding of a cell's materialized state — an observation sink's
 * `current()`, or a `Stateful.snapshot()` — into the contract's `Value` shape
 * (`20-api-contract.md` §DTOs):
 *
 * ```
 * scalar | [Value] | {"k": Value} | {"$table": {"columns": [...], "rows": [[...]]}}
 * ```
 *
 * Two passes, in this order:
 *
 * 1. **Interpretation** ([normalize]) — the kernel's CRDT snapshot shapes are
 *    tag algebra, not state a human wants to read. `SetCell.snapshot()` is
 *    `{adds, dels, counter}` over `Map<E, Set<Timestamp>>`; the *state* is the
 *    OR-set membership those tags encode. The known shapes are folded to their
 *    meaning here so the encoder below sees ordinary sets/maps/scalars.
 * 2. **Encoding** ([encode]) — scalars, collections, maps, and the `$table`
 *    form, under one shared truncation budget.
 *
 * ### Reflection discipline
 *
 * Nothing here reflects over *cells*: descriptors are the authoritative runtime
 * metadata for those (AGENTS.md), and this file never looks at one. What it
 * reflects over is the app's own *payload* values — `Match`, `MarketEntry`,
 * `Timestamp` — because a generic table needs column names and no descriptor
 * carries an element schema. Only **public zero-arg getters** are called (no
 * `setAccessible`, no `kotlin-reflect`), and anything that does not decompose
 * safely degrades to the ticket's `"opaque"` last resort rather than throwing.
 *
 * Deliberately independent of `:concord` (whose neutral `Value` model is prior
 * art this borrows ideas from, per the ticket, but must not depend on).
 */
object ValueEncoder {

    /** Contract §Value: "max 200 rows … per response". */
    const val MAX_ROWS = 200

    /** Contract §Value: "… / 50 KB per response". */
    const val MAX_BYTES = 50_000

    /**
     * V1C-BE — the row allowance a *page* encode passes to
     * [encode]`(state, maxRows, maxBytes)`: none. See that overload's doc for
     * why the read's `limit` is the page's row bound and re-imposing a second
     * one here would cut entries the cursor already advanced past.
     */
    const val PAGE_ROWS_UNBOUNDED = Int.MAX_VALUE

    /** Reserved key: the tabular form of a set/map-like state. */
    const val TABLE = "\$table"

    /** Reserved key: what the truncation budget cut. */
    const val TRUNCATED = "\$truncated"

    /** Reserved key: a value no safe decomposition applies to — type + `toString`. */
    const val OPAQUE = "\$opaque"

    /**
     * Encode [state] as one contract `Value`. The row/byte budget is per call
     * ("per response"), shared across nesting levels, so a response cannot be
     * enlarged by burying rows one table deeper.
     */
    fun encode(state: Any?): JsonElement = value(state, Budget())

    /**
     * V1C-BE — encode [state] under an **explicit** allowance instead of
     * [MAX_ROWS]/[MAX_BYTES]. Purely additive: [encode] above and both constants
     * behave exactly as before for every existing caller.
     *
     * The paged state read passes [PAGE_ROWS_UNBOUNDED] for [maxRows] on
     * purpose. A page's row bound is the *read's* own `limit` — that is the
     * entire point of the bounded read, and re-applying a second row bound here
     * would silently swallow entries the cursor has already advanced past. The
     * budget is shared across nesting levels (so a response cannot be enlarged
     * by burying rows one table deeper), which means a nested collection inside
     * one entry consumes rows too; leaving rows unbounded is therefore what
     * makes "every entry the kernel returned was rendered" achievable. The work
     * stays bounded because [maxBytes] still is: the shared budget stops
     * admitting rows the moment it is spent.
     */
    fun encode(state: Any?, maxRows: Int, maxBytes: Int = MAX_BYTES): JsonElement =
        value(state, Budget(maxRows, maxBytes))

    /**
     * V1C-BE — how many of [total] top-level entries [encoded] actually rendered.
     *
     * Read off the encoder's own **top-level** truncation marker rather than by
     * counting rows, so it means the same thing for both shapes [encode]
     * produces for "many things" (`$table` + a `$truncated` sibling, or a plain
     * array with `$truncated` as its last element). A `$truncated` nested deeper
     * — one wide record abbreviated — is deliberately not counted: that marker
     * says *this value was abbreviated*, never *this walk is incomplete*.
     */
    fun renderedOf(encoded: JsonElement, total: Int): Int {
        val marker = when (encoded) {
            is JsonObject -> encoded[TRUNCATED] as? JsonObject
            is JsonArray -> (encoded.lastOrNull() as? JsonObject)?.takeIf { TRUNCATED in it && it.size == 1 }
                ?.get(TRUNCATED) as? JsonObject

            else -> null
        }
        return (marker?.get("shown") as? JsonPrimitive)?.content?.toIntOrNull() ?: total
    }

    /**
     * A one-line description of *how much* state this is — the
     * `state.summary` event's `cardinality`. Null for values with no natural
     * size (scalars, opaque objects): the contract says null, not `"1 row"`.
     */
    fun cardinality(state: Any?): String? = when (val v = normalize(state)) {
        is Map<*, *> -> rows(v.size)
        is Collection<*> -> rows(v.size)
        is Array<*> -> rows(v.size)
        else -> null
    }

    private fun rows(n: Int): String = if (n == 1) "1 row" else "$n rows"

    // ---------------------------------------------------------------- encoding

    private fun value(raw: Any?, budget: Budget): JsonElement = when (val v = normalize(raw)) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Char -> JsonPrimitive(v.toString())
        is UUID -> JsonPrimitive(v.toString())
        is Enum<*> -> JsonPrimitive(v.name)
        is Map<*, *> -> mapValue(v, budget)
        is Set<*> -> sequenceValue(displayOrder(v), budget)
        is Collection<*> -> sequenceValue(v.toList(), budget)
        is Array<*> -> sequenceValue(v.toList(), budget)
        else -> recordOf(v.javaClass)?.let { record -> recordObject(record, v, budget) } ?: opaque(v)
    }

    /**
     * A map is always tabular: JSON objects take string keys only, and kernel
     * maps are keyed by whatever the app chose (`Map<CandidateJob, QualEntry>`
     * in the pilot), so `{"k": Value}` would silently stringify identity. The
     * value columns are spread when every value is the same record shape —
     * that is what makes the FE's "materialized view (table)" readable —
     * and collapse to a single `value` column otherwise.
     */
    private fun mapValue(map: Map<*, *>, budget: Budget): JsonElement {
        val keys = displayOrder(map.keys)
        val record = uniformRecord(keys.map { map[it] })
        val columns = listOf("key") + (record?.names ?: listOf("value"))
        return table(columns, keys.size, budget) { index ->
            val key = keys[index]
            listOf(value(key, budget)) + cells(record, map[key], budget)
        }
    }

    /**
     * A collection of same-shaped records is a table (columns from the record);
     * anything else is the contract's plain `[Value]` list.
     *
     * Note for the client: columns are discoverable only from an element, so an
     * *empty* set of records is `[]`, not an empty `$table`. A cell's state can
     * therefore switch between the two forms as it empties and refills — both
     * are contract `Value`s, and a renderer must accept either.
     */
    private fun sequenceValue(items: List<Any?>, budget: Budget): JsonElement {
        val record = uniformRecord(items)
        if (record != null) {
            return table(record.names, items.size, budget) { index -> cells(record, items[index], budget) }
        }
        val shown = mutableListOf<JsonElement>()
        for (item in items) {
            if (!budget.admits()) break
            val encoded = value(item, budget)
            budget.spend(encoded)
            shown += encoded
        }
        return buildJsonArray {
            shown.forEach { add(it) }
            // "appended when it does": on a list, the marker is the last element
            if (shown.size < items.size) {
                add(buildJsonObject { put(TRUNCATED, truncation(items.size, shown.size)) })
            }
        }
    }

    private fun cells(record: Record?, cell: Any?, budget: Budget): List<JsonElement> =
        if (record == null) listOf(value(cell, budget)) else record.read(cell).map { value(it, budget) }

    /** `{"$table": {"columns": [...], "rows": [[...]]}}`, budget-truncated. */
    private fun table(columns: List<String>, total: Int, budget: Budget, row: (Int) -> List<JsonElement>): JsonElement {
        val shown = mutableListOf<JsonArray>()
        for (index in 0 until total) {
            if (!budget.admits()) break
            val cells = JsonArray(row(index))
            budget.spend(cells)
            shown += cells
        }
        return buildJsonObject {
            putJsonObject(TABLE) {
                put("columns", JsonArray(columns.map { JsonPrimitive(it) }))
                put("rows", JsonArray(shown))
            }
            // "appended when it does": on a table, alongside $table itself
            if (shown.size < total) put(TRUNCATED, truncation(total, shown.size))
        }
    }

    private fun truncation(total: Int, shown: Int): JsonObject = buildJsonObject {
        put("total", total)
        put("shown", shown)
    }

    /** A record value rendered on its own (not as a table row): `{"k": Value}`. */
    private fun recordObject(record: Record, instance: Any, budget: Budget): JsonElement {
        val read = record.read(instance)
        return buildJsonObject {
            record.names.forEachIndexed { index, name -> put(name, value(read[index], budget)) }
        }
    }

    /**
     * The ticket's "safe reflective-toString last resort clearly marked
     * `opaque`". `toString` is called defensively: a broken one must not fail
     * the whole state read.
     */
    private fun opaque(instance: Any): JsonElement = buildJsonObject {
        putJsonObject(OPAQUE) {
            put("type", instance.javaClass.name.replace('$', '.'))
            put(
                "text",
                runCatching { instance.toString() }
                    .getOrElse { "<toString failed: ${it.javaClass.simpleName}>" },
            )
        }
    }

    /**
     * Stable output for unordered containers: sets and map key sets have no
     * intrinsic order, and a state panel that reshuffles its rows on every
     * poll is unreadable (and untestable). Ordered by rendered key — cheap,
     * total, and deterministic. Lists keep their own order.
     */
    private fun displayOrder(items: Collection<Any?>): List<Any?> =
        items.sortedBy { runCatching { it?.toString() }.getOrNull() ?: "" }

    // --------------------------------------------------- kernel shape interpretation

    /**
     * Fold the kernel's known snapshot shapes to the state they encode. Every
     * predicate checks the *values*, not just the keys, so an app map that
     * happens to be keyed `"adds"`/`"dels"` is not mistaken for an OR-set.
     */
    private fun normalize(raw: Any?): Any? {
        if (raw !is Map<*, *>) return raw
        return orSetMembership(raw) ?: pnCounterTotal(raw) ?: tagStateElements(raw) ?: raw
    }

    /**
     * `SetCell.snapshot()` — `{adds: Map<E, Set<Timestamp>>, dels: …, counter: Long}`.
     * An element is live iff it holds an add-tag with no matching del-tag
     * (`SetCell`'s own rule), so tombstoned elements are absent from the
     * reported state, exactly as `SetView.current()` would report them.
     */
    private fun orSetMembership(map: Map<*, *>): Set<Any?>? {
        if (map.keys != setOf("adds", "dels", "counter")) return null
        val adds = map["adds"] as? Map<*, *> ?: return null
        val dels = map["dels"] as? Map<*, *> ?: return null
        if (map["counter"] !is Number) return null
        return adds.entries
            .filter { (element, tags) ->
                val live = (tags as? Set<*>).orEmpty() - (dels[element] as? Set<*>).orEmpty().toSet()
                live.isNotEmpty()
            }
            .mapTo(LinkedHashSet()) { it.key }
    }

    /** `PnCounterCell.snapshot()` — `{incs: Map<UUID, Long>, decs: …}`; the state is the net total. */
    private fun pnCounterTotal(map: Map<*, *>): Long? {
        if (map.keys != setOf("incs", "decs")) return null
        val incs = map["incs"] as? Map<*, *> ?: return null
        val decs = map["decs"] as? Map<*, *> ?: return null
        if (!incs.values.all { it is Number } || !decs.values.all { it is Number }) return null
        return incs.values.sumOf { (it as Number).toLong() } - decs.values.sumOf { (it as Number).toLong() }
    }

    /**
     * `TagState.snapshot()` — `Map<E, Set<Timestamp>>`, the live tagged
     * membership every set-shaped operator checkpoints (`UnionSetCell`,
     * `FilterCell`, `CountCell`, `GroupByCell`'s first part, the join
     * ledgers). Every entry is already live — `TagState` drops an element
     * when its last tag goes — so the state is simply the key set.
     */
    private fun tagStateElements(map: Map<*, *>): Set<Any?>? {
        if (map.isEmpty()) return null
        val tagged = map.values.all { value ->
            value is Set<*> && value.isNotEmpty() && value.all { it is Timestamp }
        }
        return if (tagged) LinkedHashSet(map.keys) else null
    }

    // ------------------------------------------------------------- record shapes

    /** A value shape that decomposes into named columns. */
    private class Record(val names: List<String>, private val getters: List<Method>) {
        /** Column values of [value]; a failed accessor yields null rather than throwing. */
        fun read(value: Any?): List<Any?> =
            if (value == null) names.map { null }
            else getters.map { getter -> runCatching { getter.invoke(value) }.getOrNull() }
    }

    private val records = ConcurrentHashMap<Class<*>, Optional>()

    /** `ConcurrentHashMap` forbids null values; this is the memoized "not a record". */
    private class Optional(val record: Record?)

    private fun uniformRecord(values: Collection<Any?>): Record? {
        if (values.isEmpty()) return null
        val first = values.firstOrNull() ?: return null
        val type = first.javaClass
        if (values.any { it == null || it.javaClass != type }) return null
        return recordOf(type)
    }

    /**
     * Column names + public accessors for [type], or null when it is not a
     * safely decomposable record shape. Two shapes qualify:
     *
     * - a **Java record** (`java.lang.Record`), decomposed by its components;
     * - a **Kotlin data class**, recognized by its generated `component1()` and
     *   decomposed by pairing each declared instance field with its *public
     *   getter*. Pairing by name (rather than trusting `componentN` to line up
     *   with `declaredFields` order) keeps a column's label and its values from
     *   ever disagreeing; a field without a public getter disqualifies the
     *   whole class, so a partial — and therefore misleading — table is never
     *   produced.
     *
     * Scalars, collections and maps are excluded: they are handled upstream and
     * must never be re-read as records.
     */
    private fun recordOf(type: Class<*>): Record? = records.computeIfAbsent(type) { Optional(deriveRecord(it)) }.record

    private fun deriveRecord(type: Class<*>): Record? {
        if (!Modifier.isPublic(type.modifiers)) return null
        if (type.isRecord) {
            val components = type.recordComponents ?: return null
            if (components.isEmpty()) return null
            return Record(components.map { it.name }, components.map { it.accessor })
        }
        val dataClass = type.methods.any { it.name == "component1" && it.parameterCount == 0 }
        if (!dataClass) return null
        val fields = type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        if (fields.isEmpty()) return null
        val getters = fields.map { field -> publicGetter(type, field.name) ?: return null }
        return Record(fields.map { it.name }, getters)
    }

    /** Kotlin property getters: `getFoo()`, or `isFoo()` for a `val isFoo: Boolean`. */
    private fun publicGetter(type: Class<*>, property: String): Method? {
        val capitalized = property.replaceFirstChar { it.uppercase() }
        val candidates = if (property.startsWith("is")) listOf(property, "get$capitalized") else listOf("get$capitalized")
        return candidates.firstNotNullOfOrNull { name ->
            runCatching { type.getMethod(name) }.getOrNull()?.takeIf { Modifier.isPublic(it.modifiers) }
        }
    }

    // ------------------------------------------------------------------- budget

    /**
     * The shared "max 200 rows / 50 KB per response" allowance. Bytes are
     * measured on the *rendered* JSON of each admitted row, so the budget
     * tracks what actually goes on the wire rather than an estimate of it.
     */
    private class Budget(private var rowsLeft: Int = MAX_ROWS, private var bytesLeft: Int = MAX_BYTES) {
        fun admits(): Boolean = rowsLeft > 0 && bytesLeft > 0

        fun spend(rendered: JsonElement) {
            rowsLeft -= 1
            bytesLeft -= rendered.toString().length
        }
    }
}
