package civictech.query.run

/**
 * What a reader must subscribe an output handle's outlet with (epic §2.3,
 * `[QRY1-API-03]`'s shape half). Derived from a query root's [civictech.query.plan.PlanNode]
 * kind, never from a runtime probe: [CompiledQuery.outputShapes] is decided at compile time
 * so a caller can pick the right view type ([civictech.cell.data.view.SetView],
 * [civictech.cell.data.view.MapView], [civictech.cell.data.view.CountView]) before ever
 * applying the query to a host.
 *
 * - [SET_OF_ROWS]: every non-aggregate root — its outlet carries a `SetDelta<Row>`.
 * - [MAP_BY_GROUP]: a [civictech.query.plan.GroupAggregate] root with one or more
 *   `groupByColumns` — its outlet carries a `MapDelta` keyed by the group. A *scalar*
 *   aggregate (no `groupByColumns`) that is not [AggregateKind.COUNT][civictech.query.ast.AggregateKind.COUNT]
 *   reports [MAP_BY_GROUP] too, under the single `"global"` key `[QRY1-API-03]` reserves for it.
 * - [COUNTER]: a scalar (no `groupByColumns`) `COUNT` aggregate root — its outlet carries a
 *   `CountDelta`.
 */
enum class OutputShape {
    SET_OF_ROWS,
    MAP_BY_GROUP,
    COUNTER,
}
