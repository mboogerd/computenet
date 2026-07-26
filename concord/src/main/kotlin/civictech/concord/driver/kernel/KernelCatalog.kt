package civictech.concord.driver.kernel

import civictech.cell.Cell
import civictech.cell.data.CountCell
import civictech.cell.data.CounterCell
import civictech.cell.data.IntersectSetCell
import civictech.cell.data.PresenceCountCell
import civictech.cell.data.SetCell
import civictech.cell.data.UnionSetCell
import civictech.cell.host.ObserveCell
import civictech.cell.host.ObservationSink
import civictech.cell.host.View
import civictech.concord.value.Value

/**
 * The neutral cell-catalog → kernel-cell binding (W1-A, CONCORD-PLAN §1.4
 * "catalog → cell via ContractRegistry descriptors"). In practice a direct
 * typed constructor per catalog id is clearer and equally faithful — the
 * generated `ContractRegistry` descriptors are still consulted by
 * `ManagedHost.spawn` at admission (port/manifest validation), so nothing is
 * lost by not reflecting over them here.
 *
 * Only [civictech.concord.driver.kernel] imports `civictech.cell.*`; this object
 * is the single place the neutral vocabulary (catalog id, op verb, `fn`, `of`)
 * is translated to kernel types.
 */
internal object KernelCatalog {

    /** How a view cell's materialized value is folded back into a [Value]. */
    enum class ViewKind { NONE, SET, VALUE, MAP, COUNT }

    /** The outcome of building one catalog cell: the kernel [Cell] plus (for views) the sink to read. */
    data class Built(
        val cell: Cell,
        val sink: ObservationSink<*>? = null,
        val viewKind: ViewKind = ViewKind.NONE,
    )

    /**
     * Construct a kernel cell for catalog [type] with [params] (the descriptor
     * fields `of`/`fn`/`glitch-free`…). Throws [UnsupportedCatalogBinding] for a
     * catalog id with no W1-A binding — a real gap for W2/W3, surfaced loudly
     * rather than silently mis-bound.
     */
    fun build(type: String, params: Map<String, Value>): Built {
        val fn = (params["fn"] as? Value.StrVal)?.value
        return when (type) {
            // ---- sources ----------------------------------------------------
            "set-source" -> Built(SetCell<Any?>())
            "counter-source" -> Built(CounterCell())

            // ---- operators --------------------------------------------------
            "union" -> Built(UnionSetCell<Any?>())
            "intersect" -> Built(IntersectSetCell<Any?>())
            "count" -> Built(CountCell<Any?>())
            "presence-count" -> Built(PresenceCountCell<Any?>())
            "map" -> when (fn) {
                "identity", null -> Built(IdentityCell())
                else -> throw UnsupportedCatalogBinding(
                    "map with fn='$fn' has no W1-A binding — only fn=identity is bound " +
                        "(a non-identity element map should bind to FlatMapSetCell singleton, W3-2)",
                )
            }
            "combine-latest" -> when (fn) {
                "sum" -> Built(ScalarSumCombineCell())
                else -> throw UnsupportedCatalogBinding(
                    "combine-latest with fn='$fn' has no W1-A binding — only the scalar fn=sum " +
                        "diamond arm is bound (a keyed CombineLatestCell binding is W3-2)",
                )
            }

            // ---- views ------------------------------------------------------
            "set-view" -> observeCell(View.set<Any?>(), ViewKind.SET)
            "value-view" -> observeCell(scalarCounterView(), ViewKind.VALUE)
            "map-view" -> observeCell(View.map<Any?, Any?>(), ViewKind.MAP)
            "count-view" -> observeCell(View.count<Any?>(), ViewKind.COUNT)

            else -> throw UnsupportedCatalogBinding("no W1-A kernel binding for catalog cell type '$type'")
        }
    }

    private fun <D : Any, S> observeCell(view: View<D, S>, kind: ViewKind): Built {
        val cell = ObserveCell(view)
        return Built(cell, cell, kind)
    }

    /**
     * The kernel inlet port name a scenario link's [scenarioInlet] targets on a
     * cell of catalog [targetType]. The neutral `left`/`right` inlets of a
     * fan-in `union` both collapse to the kernel's single `inlet`; a scalar
     * `combine-latest` keeps the distinct `left`/`right` ports of
     * [ScalarSumCombineCell]. Everything else uses the given name or defaults to
     * `inlet`.
     */
    fun inletName(targetType: String, scenarioInlet: String?): String = when (targetType) {
        "union", "intersect" -> "inlet" // kernel fan-in: left/right merge into one port
        "combine-latest" -> scenarioInlet ?: "left"
        else -> scenarioInlet ?: "inlet"
    }

    /** The kernel outlet port name for a source of catalog [sourceType]; every catalog cell emits on `outlet`. */
    fun outletName(sourceType: String, scenarioOutlet: String?): String = scenarioOutlet ?: "outlet"

    /**
     * Translate a neutral op verb + [Value] payload into a reflective
     * ([methodName], jvm-parameter-type-names, boxed-args) triple the driver
     * routes through the host router to the source cell's `inlet` API. Throws
     * [UnsupportedCatalogBinding] for an unbound (type, op) pair.
     */
    fun op(type: String, op: String, value: Value?): OpCall = when (type) {
        "set-source" -> when (op) {
            "add" -> OpCall("add", OBJECT, listOf(unwrap(value)))
            "remove" -> OpCall("remove", OBJECT, listOf(unwrap(value)))
            else -> throw UnsupportedCatalogBinding("set-source op '$op' unbound (W1-A)")
        }
        "counter-source" -> when (op) {
            "increment" -> OpCall("increment", LONG, listOf(amount(value)))
            "decrement" -> OpCall("decrement", LONG, listOf(amount(value)))
            else -> throw UnsupportedCatalogBinding("counter-source op '$op' unbound (W1-A)")
        }
        else -> throw UnsupportedCatalogBinding("no W1-A op binding for '$op' on catalog type '$type'")
    }

    /** A routable op: the served-handler method and its erased signature. */
    data class OpCall(val methodName: String, val parameterTypes: List<String>, val args: List<Any?>)

    private val OBJECT = listOf("java.lang.Object")
    private val LONG = listOf("long")

    /** A value-less counter op defaults to a unit step. */
    private fun amount(value: Value?): Long = (value as? Value.IntVal)?.value ?: 1L

    /** Unwrap a scalar [Value] to the Kotlin value a source cell holds. */
    private fun unwrap(value: Value?): Any? = when (value) {
        null, Value.NullVal -> null
        is Value.StrVal -> value.value
        is Value.IntVal -> value.value
        is Value.RealVal -> value.value
        is Value.BoolVal -> value.value
        is Value.ListVal -> value.items.map { unwrap(it) }
        is Value.MapVal -> value.entries.mapValues { unwrap(it.value) }
    }

    /** Fold a view cell's current materialized value into the neutral [Value] model. */
    fun readView(kind: ViewKind, current: Any?): Value = when (kind) {
        ViewKind.VALUE -> Value.of(current)
        ViewKind.SET -> Value.ListVal((current as Set<*>).map { Value.of(it) }.sortedBy { it.toString() })
        ViewKind.MAP, ViewKind.COUNT -> Value.of(current)
        ViewKind.NONE -> error("readView on a non-view cell")
    }
}

/** A catalog id / op the W1-A kernel driver cannot bind — a real gap, not a soft failure. */
class UnsupportedCatalogBinding(message: String) : RuntimeException(message)
