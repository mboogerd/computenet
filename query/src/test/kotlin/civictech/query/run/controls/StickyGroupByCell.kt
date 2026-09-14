package civictech.query.run.controls

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.graph.TypedCellFactory
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.query.expr.RowKey
import civictech.query.lower.AggregateSpec
import civictech.query.schema.Row
import java.util.UUID

/**
 * A deliberately WRONG grouped COUNT — the mutation of `[QRY1-ORA-08]` (BS-16, cab.6-D7):
 * "group-by lowered without last retraction removal".
 *
 * It tracks each input row's live tags (a row is live while it holds at least one tag) and a
 * live-row count per group, and emits `puts[group] = count` whenever a group's count changes —
 * but it NEVER emits a removal. A group whose last row is retracted therefore stays present
 * with `0L`, where `GroupByCell` removes it (`[24-OP-GROUPBY-02]`) and `BatchEvaluator` has no
 * such key at all.
 *
 * The group key is [key] applied to the row, or the constant `"global"` when [key] is `null`
 * (the `GroupByCell.global` convention). Only `COUNT` is modelled; [StickyGroupByFactory]
 * refuses any other [AggregateSpec].
 *
 * Test scope only: `NoCellClassArchitectureTest` gates `query/src/main` (`[QRY1-LOWER-03]`).
 * It emits on every delta (never absorbs a wave), because `absorbAck` is kernel-internal.
 */
class StickyGroupByCell(
    private val key: RowKey?,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<Any?, Any?>>>())
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Row>>>())

    private val tags = HashMap<Row, MutableSet<Timestamp>>()
    private val counts = HashMap<Any?, Long>()

    private fun groupOf(row: Row): Any? = key?.invoke(row) ?: "global"

    init {
        inlet.serve(object : Propagate<SetDelta<Row>> {
            override fun propagate(value: SetDelta<Row>) {
                val touched = value.adds.keys + value.dels.keys
                val liveBefore = touched.filterTo(HashSet()) { tags[it].orEmpty().isNotEmpty() }
                value.adds.forEach { (row, added) -> tags.getOrPut(row) { HashSet() } += added }
                value.dels.forEach { (row, removed) ->
                    tags[row]?.let { held ->
                        held -= removed
                        if (held.isEmpty()) tags.remove(row)
                    }
                }
                val puts = LinkedHashMap<Any?, Any?>()
                touched.forEach { row ->
                    val was = row in liveBefore
                    val now = tags[row].orEmpty().isNotEmpty()
                    if (was == now) return@forEach
                    val group = groupOf(row)
                    val count = counts.getOrDefault(group, 0L) + if (now) 1L else -1L
                    counts[group] = count // the mutation: a zero count is kept, never removed
                    puts[group] = count
                }
                outlet.call.propagate(MapDelta(puts, emptySet()))
            }
        })
    }
}

/**
 * The data-class factory `SpecMutations.stickyGroupBy` substitutes for a root's
 * `GroupByFactory`. [spec] must be [AggregateSpec.Count].
 */
data class StickyGroupByFactory(val key: RowKey?, val spec: AggregateSpec) : TypedCellFactory<StickyGroupByCell> {
    init {
        if (spec != AggregateSpec.Count) error("StickyGroupByFactory models COUNT only, got $spec")
    }

    override fun create(ref: CellRef): StickyGroupByCell = StickyGroupByCell(key, ref)
}
