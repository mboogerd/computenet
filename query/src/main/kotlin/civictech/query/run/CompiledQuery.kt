package civictech.query.run

import civictech.cell.data.SetApi
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.TypedRef
import civictech.cell.host.HostManagementApi
import civictech.cell.port.Use
import civictech.query.ast.AggregateKind
import civictech.query.lower.LoweringDiagnostic
import civictech.query.lower.LoweringResult
import civictech.query.plan.GroupAggregate
import civictech.query.plan.LogicalPlan
import civictech.query.plan.PlanNode
import civictech.query.schema.Row
import java.io.Serializable

/**
 * A [LogicalPlan] lowered to a [GraphSpec] plus everything a caller needs to apply it and
 * read its answers (epic §2.3, `[QRY1-LOWER-01]`'s symbol table, `[QRY1-API-03]`): the plan
 * itself (for diagnosis/display), the [spec] the plan lowered to, the EDB source and root
 * output handle tables ([sourceHandles], [outputHandles] — copied from
 * [LoweringResult.Lowered] rather than re-derived, so `CompiledQuery` and the lowering it
 * came from can never disagree), an [OutputShape] per root, and the non-fatal
 * [diagnostics] the lowering produced. `Serializable` end to end (`[QRY1-API-06]`'s
 * in-process half) so a compiled query can be persisted or shipped and re-applied later.
 *
 * Deliberately carries no live refs (cab.4-D7): [applyTo] is the only way to get one, and it
 * mints fresh ones every call — see [AppliedQuery].
 */
data class CompiledQuery(
    val plan: LogicalPlan,
    val spec: GraphSpec,
    val sourceHandles: Map<String, String>,
    val outputHandles: Map<String, String>,
    val outputShapes: Map<String, OutputShape>,
    val diagnostics: List<LoweringDiagnostic>,
) : Serializable {

    /**
     * Applies [spec] to [host] ([GraphSpec.applyTo]'s local, co-located, synchronous-failure
     * semantics) and narrows the resulting handle table into typed refs for every source and
     * output this query names.
     */
    fun applyTo(host: Use<HostManagementApi>): AppliedQuery {
        val handles = spec.applyTo(host)
        val sources = sourceHandles.mapValues { (_, handle) ->
            TypedRef<SetApi<Row>>(handles.getValue(handle))
        }
        val outputs = outputHandles.mapValues { (_, handle) ->
            TypedRef<Any>(handles.getValue(handle))
        }
        return AppliedQuery(handles, sources, outputs)
    }

    companion object {

        /**
         * Builds a [CompiledQuery] from a successful [Lowering.lower][civictech.query.lower.Lowering.lower]
         * result and the [plan] it lowered. [LoweringResult.Refused] has no `CompiledQuery` to
         * build — mapping it to a rejection is computenet-cab.5's, not this function's.
         */
        fun from(lowered: LoweringResult.Lowered, plan: LogicalPlan): CompiledQuery {
            val outputShapes = lowered.outputHandles.keys.associateWith { root ->
                outputShapeOf(plan.roots.getValue(root))
            }
            return CompiledQuery(
                plan = plan,
                spec = lowered.spec,
                sourceHandles = lowered.sourceHandles,
                outputHandles = lowered.outputHandles,
                outputShapes = outputShapes,
                diagnostics = lowered.diagnostics,
            )
        }

        /**
         * A root's [OutputShape], per the epic's §2.3 sketch: a [GroupAggregate] with one or
         * more `groupByColumns` reads as [OutputShape.MAP_BY_GROUP]; a scalar (no
         * `groupByColumns`) `COUNT` aggregate reads as [OutputShape.COUNTER]; any other scalar
         * aggregate reads as [OutputShape.MAP_BY_GROUP] under the single `"global"` key; every
         * other root kind is [OutputShape.SET_OF_ROWS]. A root [GroupAggregate] refuses to
         * lower on this task's base (`Lowering.NOT_YET_LOWERED`) so only the `SET_OF_ROWS`
         * branch is reachable today — the aggregate branches are here for when the completion
         * task lands a rule for it.
         */
        internal fun outputShapeOf(node: PlanNode): OutputShape = when {
            node is GroupAggregate && node.groupByColumns.isNotEmpty() -> OutputShape.MAP_BY_GROUP
            node is GroupAggregate && node.aggregate.kind == AggregateKind.COUNT -> OutputShape.COUNTER
            node is GroupAggregate -> OutputShape.MAP_BY_GROUP
            else -> OutputShape.SET_OF_ROWS
        }
    }
}
