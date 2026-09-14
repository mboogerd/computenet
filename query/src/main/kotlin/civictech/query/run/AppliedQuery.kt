package civictech.query.run

import civictech.cell.CellRef
import civictech.cell.data.SetApi
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
 * [outputs] narrows it to the query's roots; a reader picks the matching view
 * ([CompiledQuery.outputShapes]) and subscribes the ref's outlet.
 */
data class AppliedQuery(
    val handles: Map<String, CellRef>,
    val sources: Map<String, TypedRef<SetApi<Row>>>,
    val outputs: Map<String, TypedRef<*>>,
) : Serializable
