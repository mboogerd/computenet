package civictech.query.run

import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.query.schema.AttrType
import civictech.query.schema.Catalog
import civictech.query.schema.Row
import kotlin.random.Random

/**
 * A seeded add/remove [Script] over every relation of a [Catalog] (cab.6-D8, `[QRY1-ORA-06]`).
 *
 * One `SourceId(relation)` and one `WriterId(relation)` per relation, so a relation's slice has
 * exactly one writer. Per step: pick a relation uniformly; with probability [deletionRatio] —
 * and only when that relation holds a row — emit a `Remove` of a row it holds; otherwise emit
 * an `Add` of a row it does not hold, each attribute drawn from `domain[type]`. Because every
 * `Remove` targets a row its sole writer added and has not yet removed, `Membership.live` of a
 * slice equals the plain held set, which is what `QueryCase.reference` hands the evaluator.
 *
 * If no unheld row is found in [FRESH_ATTEMPTS] draws (a saturated small domain), the step
 * becomes a `Remove` instead, so a step never silently vanishes.
 *
 * Deterministic from [seed]: one `kotlin.random.Random(seed)`, relations taken in name order,
 * held rows kept in insertion order — no hash-order iteration touches the random stream.
 *
 * The achieved split is [stats]; the configured [deletionRatio] is an upper bound on it, since
 * a step on an empty relation always adds.
 */
class QueryScripts(
    val seed: Long,
    val catalog: Catalog,
    val domain: Map<AttrType, List<Any>> = DEFAULT_DOMAIN,
    val steps: Int,
    val deletionRatio: Double,
) {
    /** Achieved counts: total adds and removes, and how many removes each relation received. */
    data class ScriptStats(val adds: Int, val removes: Int, val removedRowsPerRelation: Map<String, Int>) {
        val ops: Int get() = adds + removes

        operator fun plus(other: ScriptStats) = ScriptStats(
            adds = adds + other.adds,
            removes = removes + other.removes,
            removedRowsPerRelation = (removedRowsPerRelation.keys + other.removedRowsPerRelation.keys).associateWith {
                (removedRowsPerRelation[it] ?: 0) + (other.removedRowsPerRelation[it] ?: 0)
            },
        )

        companion object {
            val ZERO = ScriptStats(0, 0, emptyMap())
        }
    }

    val script: Script
    val stats: ScriptStats

    init {
        require(steps >= 0) { "steps must not be negative, got $steps" }
        require(deletionRatio in 0.0..1.0) { "deletionRatio must be in [0, 1], got $deletionRatio" }
        val relations = catalog.relations.keys.sorted()
        require(relations.isNotEmpty()) { "QueryScripts needs at least one catalog relation" }

        val random = Random(seed)
        val held = relations.associateWith { LinkedHashSet<Row>() }
        val events = relations.associateWith { mutableListOf<ScriptEvent>() }
        val removed = relations.associateWith { 0 }.toMutableMap()
        var adds = 0
        var removes = 0

        repeat(steps) {
            val relation = relations[random.nextInt(relations.size)]
            val writer = WriterId(relation)
            val rows = held.getValue(relation)
            val wantsRemove = rows.isNotEmpty() && random.nextDouble() < deletionRatio
            val fresh = if (wantsRemove) null else freshRow(random, relation, rows)
            if (fresh != null) {
                rows += fresh
                events.getValue(relation) += ScriptEvent.Add(writer, fresh)
                adds++
            } else if (rows.isNotEmpty()) {
                val victim = rows.elementAt(random.nextInt(rows.size))
                rows -= victim
                events.getValue(relation) += ScriptEvent.Remove(writer, victim)
                removed[relation] = removed.getValue(relation) + 1
                removes++
            }
        }

        script = Script(relations.map { SourceScript(SourceId(it), events.getValue(it).toList()) })
        stats = ScriptStats(adds, removes, removed.toMap())
    }

    private fun freshRow(random: Random, relation: String, rows: Set<Row>): Row? {
        val attributes = catalog.relations.getValue(relation).attributes
        repeat(FRESH_ATTEMPTS) {
            val row = Row(
                attributes.map { attribute ->
                    val values = domain[attribute.type]
                        ?: error("QueryScripts domain has no values for ${attribute.type} (relation '$relation')")
                    values[random.nextInt(values.size)]
                },
            )
            if (row !in rows) return row
        }
        return null
    }

    companion object {
        /** How many draws [QueryScripts] makes for an unheld row before turning the step into a remove. */
        const val FRESH_ATTEMPTS = 8

        /** Small on purpose, so joins collide and multi-join chains carry dependent rows. */
        val DEFAULT_DOMAIN: Map<AttrType, List<Any>> = mapOf(
            AttrType.INT to listOf(1, 2, 3, 4),
            AttrType.LONG to listOf(1L, 2L, 3L, 4L),
            AttrType.STRING to listOf("a", "b", "c", "d"),
            AttrType.BOOL to listOf(false, true),
            AttrType.DOUBLE to listOf(0.5, 1.0, 1.5, 2.0),
        )
    }
}
