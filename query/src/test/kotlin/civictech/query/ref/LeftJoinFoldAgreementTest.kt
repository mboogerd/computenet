package civictech.query.ref

import civictech.query.parse.ParseResult
import civictech.query.parse.QueryParser
import civictech.query.plan.OuterJoin
import civictech.query.plan.OuterJoinSide
import civictech.query.plan.Planner
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import civictech.query.schema.Row
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * cab.6-D10, one of `BatchEvaluator`'s `[QRY1-HONEST-01]` defences: the evaluator agrees with
 * the hand-rolled left-join fold of `kernel/src/test/kotlin/civictech/cell/graph/RelationalGraphsTest.kt`
 * (`left join - incremental result equals batch recompute on every seed`) on that test's own
 * script distribution. That fold is not importable from `:query` (no test-fixtures
 * configuration), so both its script generator and its fold are TRANSCRIBED here, and the
 * transcription is kept literal on purpose: same `java.util.Random(seed)` call sequence, same
 * domains, same 60/40 add rule, 40 events, seeds `0 until 100`.
 *
 * The kernel test's elements are strings keyed by their first character and combined as
 * `"$a|$b"` / `"$a|-"`; here each element is split into `(first char, rest)` so it is a row of
 * `l(k, a)` / `r(k, b)`, and the fold's `"$a|$b"` is the row `(k, a, b)`, `"$a|-"` the row
 * `(k, a, null)`.
 *
 * Limit: agreement is shown on this one script shape and one join; it says nothing about
 * other plan node kinds.
 */
class LeftJoinFoldAgreementTest {

    private val catalog = Catalog(
        mapOf(
            "l" to RelationSchema(listOf(Attribute("k", AttrType.STRING), Attribute("a", AttrType.STRING))),
            "r" to RelationSchema(listOf(Attribute("k", AttrType.STRING), Attribute("b", AttrType.STRING))),
        ),
    )

    private val plan = Planner.plan(
        (QueryParser.parse("define j(K, A, B) := l(K, A) left outer join r(K, B) on K = K.", catalog) as ParseResult.Parsed).query,
    )

    /** RelationalGraphsTest's `key`. */
    private fun key(e: String) = e.first().toString()

    private fun toRow(e: String): Row = Row(listOf(key(e), e.substring(1)))

    private class Held(val left: Set<String>, val right: Set<String>)

    /** RelationalGraphsTest's script generator; the live sets at the end of the script are the held sets. */
    private fun script(seed: Long): Held {
        val rnd = Random(seed)
        val leftDomain = listOf("ax", "ay", "bx", "cx")
        val rightDomain = listOf("a1", "a2", "b1", "c1")
        val heldLeft = mutableSetOf<String>()
        val heldRight = mutableSetOf<String>()
        repeat(40) {
            if (rnd.nextBoolean()) {
                val e = leftDomain[rnd.nextInt(leftDomain.size)]
                if (rnd.nextInt(10) < 6 || e !in heldLeft) heldLeft += e else heldLeft -= e
            } else {
                val e = rightDomain[rnd.nextInt(rightDomain.size)]
                if (rnd.nextInt(10) < 6 || e !in heldRight) heldRight += e else heldRight -= e
            }
        }
        return Held(heldLeft, heldRight)
    }

    /** RelationalGraphsTest's fold (its lines 179-187), producing `(k, a, b?)` rows. */
    private fun fold(liveLeft: Set<String>, liveRight: Set<String>): Set<Row> =
        liveLeft.flatMap { a ->
            val matches = liveRight.filter { key(it) == key(a) }
            if (matches.isEmpty()) {
                listOf(Row(listOf(key(a), a.substring(1), null)))
            } else {
                matches.map { b -> Row(listOf(key(a), a.substring(1), b.substring(1))) }
            }
        }.toSet()

    @Test
    fun `the plan under test is a LEFT OuterJoin over l and r`() {
        val root = plan.roots.getValue("j").shouldBeInstanceOf<OuterJoin>()
        root.side shouldBe OuterJoinSide.LEFT
        root.outputColumns shouldBe listOf("K", "A", "B")
    }

    @Test
    fun `QRY1 §HONEST-01 BatchEvaluator agrees with RelationalGraphsTest's left-join fold on every seed 0 until 100`() {
        var seedsWithMatchedAndPadded = 0
        forEachSeed(0L until 100L) { seed ->
            val held = script(seed)
            val expected = fold(held.left, held.right)
            val db = mapOf("l" to held.left.map(::toRow).toSet(), "r" to held.right.map(::toRow).toSet())

            val actual = BatchEvaluator.evaluate(plan, db)

            withClue("seed $seed, l=${held.left}, r=${held.right}") {
                actual shouldBe mapOf("j" to RelationValue.Rows(expected))
            }
            if (expected.any { it.values[2] == null } && expected.any { it.values[2] != null }) seedsWithMatchedAndPadded++
        }
        withClue("non-vacuity: at least one seed must yield both a matched and a null-padded row") {
            (seedsWithMatchedAndPadded > 0) shouldBe true
        }
        println("LeftJoinFoldAgreementTest: $seedsWithMatchedAndPadded of 100 seeds yield matched and null-padded rows")
    }
}
