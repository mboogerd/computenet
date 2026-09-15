package civictech.query.run

import civictech.cell.CellRef
import civictech.cell.data.SetApi
import civictech.cell.data.op.CountSetApi
import civictech.cell.data.op.GroupByApi
import civictech.cell.graph.TypedRef
import civictech.query.schema.Row
import java.io.Serializable

/**
 * The result of [CompiledQuery.applyTo] (cab.4-D7): a compiled artifact carries spawn
 * HANDLES, and typed refs come back only once the spec is actually applied to a host — a
 * deterministically pre-minted [TypedRef] would collide on a second apply to one host
 * ([civictech.cell.graph.IdentityBinding.FreshLogical]'s per-apply mint).
 *
 * [handles] is every [civictech.cell.graph.SpawnStep.handle] in the applied
 * [civictech.cell.graph.GraphSpec], resolved to the [CellRef] the host assigned it —
 * [CompiledQuery.sourceHandles] and [CompiledQuery.outputHandles] name the subset of these
 * keys a caller cares about. [sources] narrows that to the query's EDB sources, typed as
 * [SetApi] so a caller can write rows in directly (`sources.getValue("r").inlet.call.add(...)`).
 * [outputs] narrows it to the query's roots, star-projected; [setOutput], [mapOutput] and
 * [counterOutput] narrow further, by [outputShapes] (`[QRY1-API-03]`), to the API type a
 * reader actually needs — so a caller subscribes an output's outlet or observes it without a
 * spawn handle, a port-name string, or a hand-rolled `TypedRef<...>(ref)` cast.
 */
data class AppliedQuery(
    val handles: Map<String, CellRef>,
    val sources: Map<String, TypedRef<SetApi<Row>>>,
    val outputs: Map<String, TypedRef<*>>,
    val outputShapes: Map<String, OutputShape>,
) : Serializable {

    /**
     * The [OutputShape.SET_OF_ROWS] output named [name], as a [SetApi]-typed ref. Throws
     * [IllegalArgumentException] naming [name], [OutputShape.SET_OF_ROWS] and the shape it
     * actually compiled as when they differ, and [NoSuchElementException] naming the known
     * outputs when [name] is not one of them.
     */
    fun setOutput(name: String): TypedRef<SetApi<Row>> {
        @Suppress("UNCHECKED_CAST")
        return shapeChecked(name, OutputShape.SET_OF_ROWS) as TypedRef<SetApi<Row>>
    }

    /**
     * The [OutputShape.MAP_BY_GROUP] output named [name], as a [GroupByApi]-typed ref (the
     * grouped key is whatever [civictech.query.expr.RowKey] emits for the rule's
     * `groupByColumns`, or the single `"global"` key for a non-COUNT scalar aggregate —
     * cab.6-D5). Throws as [setOutput] does, for [OutputShape.MAP_BY_GROUP].
     */
    fun mapOutput(name: String): TypedRef<GroupByApi<Row, Any?, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return shapeChecked(name, OutputShape.MAP_BY_GROUP) as TypedRef<GroupByApi<Row, Any?, Any?>>
    }

    /**
     * The [OutputShape.COUNTER] output named [name], as a [CountSetApi]-typed ref (the scalar
     * `COUNT` case). Throws as [setOutput] does, for [OutputShape.COUNTER].
     */
    fun counterOutput(name: String): TypedRef<CountSetApi<Row>> {
        @Suppress("UNCHECKED_CAST")
        return shapeChecked(name, OutputShape.COUNTER) as TypedRef<CountSetApi<Row>>
    }

    private fun shapeChecked(name: String, requested: OutputShape): TypedRef<*> {
        val compiled = outputShapes[name]
            ?: throw NoSuchElementException(
                "AppliedQuery: no output named '$name'; known outputs: ${outputShapes.keys.sorted()}",
            )
        require(compiled == requested) {
            "AppliedQuery: output '$name' compiled as $compiled, not the requested $requested"
        }
        return outputs.getValue(name)
    }
}
