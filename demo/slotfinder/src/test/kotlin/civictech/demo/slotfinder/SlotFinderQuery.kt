package civictech.demo.slotfinder

import civictech.query.QueryCompiler
import civictech.query.diag.CompileResult
import civictech.query.parse.query
import civictech.query.run.CompiledQuery
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema

/**
 * Slotfinder's RELATIONAL portion ([QRY1-ORA-10], cab.7-D3) as a `query { }` builder query
 * (cab.7-D9: demo queries live in the demo module's TEST source set, since `:query`'s
 * `ModuleDependencyTest` forbids the reverse dependency even from test scope).
 *
 * The comparison boundary is AFTER [SlotPipeline]'s quorum fan-in: `QuorumSetCell` has no
 * `[24-OP-*]` id and is excluded from the plan vocabulary (cab.4-D4; `Lowering`'s KDoc), so
 * this query consumes `common(day, hour)` — [SlotPipeline.Refs.common]'s output — as an EDB
 * relation rather than expressing the quorum itself. `nearMiss` sits entirely on the far side
 * of that boundary and has no relational twin here; it is not compared by
 * [SlotFinderQueryAgreementTest].
 *
 * `common` carries its full row key `{day, hour}` because a `Slot` is a set element (no two
 * held slots are ever equal-but-distinct), which is what lets `byDay`'s COUNT compile: the
 * aggregate's body plan (`filtered`, a `Select` over the `common` scan) is key-preserving
 * regardless of computenet-afnwl, which concerns only relations declared with NO row key.
 *
 * ```
 * filtered(D, H)      :- common(D, H), H >= 9, H <= 17.   // Select, Slot.BUSINESS_HOURS
 * @count byDay(D, H)  :- filtered(D, H).                  // GroupByCell keyed on D (day)
 * ```
 *
 * `byDay`'s aggregate groups by every head variable but the last (`Planner.plan`'s rule): the
 * head is `(D, H)`, so the group key is `D` (day) and the aggregated column is `H` (hour),
 * counted — the same semantics [SlotPipeline.Refs.byDay]'s `GroupByCell(keyFn = day,
 * aggregator = count)` computes over the same population.
 */
object SlotFinderQuery {

    val catalog: Catalog = Catalog(
        mapOf(
            "common" to RelationSchema(
                attributes = listOf(
                    Attribute("day", AttrType.STRING),
                    Attribute("hour", AttrType.INT),
                ),
                rowKey = setOf("day", "hour"),
            ),
        ),
    )

    val compiled: CompiledQuery = run {
        val ast = query(catalog) {
            val common = relation("common")
            val filtered = derived("filtered")
            val byDay = derived("byDay")
            val day = v("D")
            val hour = v("H")

            rule(filtered(day, hour)) {
                +common(day, hour)
                hour ge const(Slot.BUSINESS_HOURS.first)
                hour le const(Slot.BUSINESS_HOURS.last)
            }
            rule(byDay(day, hour), aggregate = count()) {
                +filtered(day, hour)
            }
        }
        when (val result = QueryCompiler.compile(ast)) {
            is CompileResult.Compiled -> result.query
            is CompileResult.Rejected ->
                error("SlotFinderQuery did not compile: ${result.rejections}\n$ast")
        }
    }
}
