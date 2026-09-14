package civictech.query.run

import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.RunOutcome
import civictech.oracle.run.StateDifference
import civictech.query.run.controls.lastWinsProjection
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema
import civictech.query.schema.Row
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * `[QRY1-ORA-09]`'s replay half (cab.6-D11): ONE failing case of the BS-3 divergence control,
 * pinned as literals — the source, the catalog, the seed and the script — so it replays with no
 * generator, no sweep and no dependency on `DivergenceControlTest`'s constants.
 *
 * **Recorded case.** `DivergenceControlTest`'s generated sweep
 * (`QueryScripts(seed, r(a0 INT, a1 LONG), INT ∈ {1, 2}, LONG ∈ 1..6, steps = 40,
 * deletionRatio = 0.5)`) with `q`'s `Project` replaced by `LastWinsProjectCell` failed on 13 of
 * seeds `0 until 50`: 4, 5, 6, 9, 14, 21, 22, 25, 30, 38, 41, 42, 44. Seed 4 is pinned — the one
 * where BOTH answers are lost: expected `{[1], [2]}`, actual `{}`. Measured on darwin/arm64 at
 * base 46d4e795 (computenet-cab.6.4). The script below is `QueryScripts(4L, …).script`'s single
 * slice transcribed event by event; the replay does not regenerate it, so a later change to
 * `QueryScripts` cannot move this case.
 *
 * The expected/actual states are quiescent folds only (cab.6-D12), so they do not depend on the
 * seed's interleaving; the seed is pinned anyway because it is part of the recorded counterexample.
 */
class QueryDifferentialReplayTest {

    private fun row(x: Int, y: Long) = Row(listOf(x, y))

    /** `+x:y` is an add of `r(x, y)`, `-x:y` a remove. */
    private fun events(vararg ops: String): List<ScriptEvent> = ops.map { op ->
        val (x, y) = op.substring(1).split(":")
        val element = row(x.toInt(), y.toLong())
        when (op[0]) {
            '+' -> ScriptEvent.Add(WriterId("r"), element)
            '-' -> ScriptEvent.Remove(WriterId("r"), element)
            else -> error("bad op $op")
        }
    }

    private val source = "q(X) :- r(X, Y)."

    private val catalog = Catalog(
        mapOf("r" to RelationSchema(listOf(Attribute("a0", AttrType.INT), Attribute("a1", AttrType.LONG)))),
    )

    private val seed = 4L

    private val script = Script(
        listOf(
            SourceScript(
                SourceId("r"),
                events(
                    "+1:4", "+1:3", "-1:3", "+1:3", "-1:4", "-1:3", "+2:6", "+1:2", "-1:2", "-2:6",
                    "+2:2", "+2:6", "+2:1", "-2:2", "-2:6", "+1:6", "+1:3", "+1:1", "+2:3", "+1:5",
                    "-2:3", "+1:2", "+2:5", "-1:2", "-1:5", "-1:6", "+1:2", "+2:3", "+1:4", "-2:1",
                    "+1:6", "+1:5", "-1:3", "-2:3", "+2:1", "+2:2", "-1:1", "+1:3", "-1:3", "-2:2",
                ),
            ),
        ),
    )

    private fun replay(): RunOutcome {
        val case = QueryCase.compile(source, catalog)
        return case.copy(compiled = case.compiled.lastWinsProjection("q")).check(seed, script)
    }

    @Test
    fun `QRY1 §ORA-09 the pinned last-wins control case replays to its recorded mismatch`() {
        // The unmutated lowering agrees on this very case, so the mismatch is the control's.
        QueryCase.compile(source, catalog).assertSuccess(seed, script)

        val mismatch = replay().shouldBeInstanceOf<RunOutcome.Mismatch>()
        mismatch.seed shouldBe seed
        mismatch.terminal shouldBe "q"
        mismatch.expected shouldBe ModelState.SetState(setOf(Row(listOf(1)), Row(listOf(2))))
        mismatch.actual shouldBe ModelState.SetState(emptySet())
        mismatch.difference shouldBe StateDifference.SetDifference(
            onlyInExpected = setOf(Row(listOf(1)), Row(listOf(2))),
            onlyInActual = emptySet(),
        )
        mismatch.renderedGraphSpec.contains(source) shouldBe true
    }

    @Test
    fun `QRY1 §ORA-09 replaying the pinned case twice gives equal outcomes`() {
        val first = replay()
        val second = replay()
        first.shouldBeInstanceOf<RunOutcome.Mismatch>()
        first shouldBe second
    }
}
