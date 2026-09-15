package civictech.demo.skillmatch

import civictech.query.QueryCompiler
import civictech.query.ast.Query
import civictech.query.diag.CompileResult
import civictech.query.parse.query
import civictech.query.run.CompiledQuery
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema

/**
 * The relational core of [SkillPipeline] written as a `query { }` (computenet-cab.7, cab.7-D9).
 *
 * ```
 * matches(C, S, J)             :- candSkills(C, S), jobSkills(J, S).
 * @count matchCounts(C, J, S)  :- matches(C, S, J).
 * @count required(J, S)        :- jobSkills(J, S).
 * candHas(S)                   :- candSkills(C, S).
 * gap(J, S)                    :- jobSkills(J, S), not candHas(S).
 * @count supply(S, C)          :- candSkills(C, S).
 * @count demand(S, J)          :- jobSkills(J, S).
 * ```
 *
 * Heads are written in the planner's natural column order (a join's columns are its left's
 * then its right's new ones; an aggregate groups by every head variable but the last), so no
 * rule needs a reordering projection. Every use site reuses the defining rule's variable
 * names, so the planner inlines a value-equal body and lowering shares it ([QRY1-LOWER-11]):
 * `matchCounts` counts over the one `matches` join rather than a second copy.
 *
 * Not expressible, and therefore absent: `qualification` and `market` (expression-valued
 * enrichments over two aggregates, residual R1), and `gap`'s direct keying of `candSkills` by
 * skill — `not candSkills(C, S)` with `C` positively unbound is unsafe, hence the `candHas`
 * projection (residual R2). See [SkillMatchQueryStructureTest] and doc/demo-findings.md.
 */
object SkillMatchQuery {

    val catalog: Catalog = Catalog(
        mapOf(
            "candSkills" to RelationSchema(
                attributes = listOf(Attribute("candidate", AttrType.STRING), Attribute("skill", AttrType.STRING)),
                rowKey = setOf("candidate", "skill"),
            ),
            "jobSkills" to RelationSchema(
                attributes = listOf(Attribute("job", AttrType.STRING), Attribute("skill", AttrType.STRING)),
                rowKey = setOf("job", "skill"),
            ),
        ),
    )

    val query: Query = query(catalog) {
        val c = v("C")
        val s = v("S")
        val j = v("J")
        val candSkills = relation("candSkills")
        val jobSkills = relation("jobSkills")
        val matches = derived("matches")
        val candHas = derived("candHas")

        rule(matches(c, s, j)) {
            +candSkills(c, s)
            +jobSkills(j, s)
        }
        rule(derived("matchCounts")(c, j, s), aggregate = count()) { +matches(c, s, j) }
        rule(derived("required")(j, s), aggregate = count()) { +jobSkills(j, s) }
        rule(candHas(s)) { +candSkills(c, s) }
        rule(derived("gap")(j, s)) {
            +jobSkills(j, s)
            not(candHas(s))
        }
        rule(derived("supply")(s, c), aggregate = count()) { +candSkills(c, s) }
        rule(derived("demand")(s, j), aggregate = count()) { +jobSkills(j, s) }
    }

    /** Compiles [query]; a rejection is a broken fixture, so it fails loudly with every reason. */
    fun compile(): CompiledQuery = when (val result = QueryCompiler.compile(query)) {
        is CompileResult.Compiled -> result.query
        is CompileResult.Rejected -> error("SkillMatchQuery was rejected: ${result.rejections}")
    }
}
