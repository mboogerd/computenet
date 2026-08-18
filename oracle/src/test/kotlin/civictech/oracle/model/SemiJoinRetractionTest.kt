package civictech.oracle.model

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * **BS-5** — the antijoin's non-monotone retraction (`[ORA1-MODEL-07]`, `[24-OP-SEMIJOIN-01]`,
 * `[24-OP-SEMIJOIN-02]`), model-level.
 *
 * The feature's example mapping states it exactly: *given `SemiJoinCell(negated = true)`
 * modelled with left = `{("a", 1)}` and a right side initially without key `"a"`, the output
 * contains `("a", 1)`; when the right side gains `("a", 9)`, the output no longer contains
 * `("a", 1)`.*
 *
 * ## Why this case earns its own file
 *
 * It is the one place in this task's slice where **an addition shrinks the output**. Every
 * other operator here is monotone in its inputs — adding a row can only add combinations —
 * and monotone operators are the ones a wrong model is most likely to agree with anyway. Here
 * the two halves reach the same answer by genuinely different routes:
 *
 * - the kernel cannot borrow an input tag for the re-entry that follows a *right-side*
 *   removal, because no left tag moved, so it mints one per entry (`[24-OP-SEMIJOIN-02]`) and
 *   tombstones exactly what it advertised;
 * - the model has no tag to borrow or mint, and recomputes a set difference.
 *
 * A differential run that found these disagreeing would be reporting a real non-monotone
 * defect, which is what `[ORA1-MODEL-07]` is protecting.
 *
 * Driven through [ReferenceModel.eval] from scripts rather than by handing states to
 * `evaluate` directly, so the retraction is a real one: the left row's liveness comes out of
 * [Membership], and the right row's arrival is an event in a source's log.
 */
class SemiJoinRetractionTest {

    private val writer = WriterId("w")
    private val leftSource = SourceId("left")
    private val rightSource = SourceId("right")

    /** The pair's first component: the antijoin's key on both sides. */
    private val first = ElementKey { element -> (element as Pair<*, *>).first }

    private val antiJoin = ReferenceModel(
        nodes = listOf(
            ModelNode.Source(NodeId("left"), leftSource, SetSourceModel),
            ModelNode.Source(NodeId("right"), rightSource, SetSourceModel),
            ModelNode.Operator(
                NodeId("unmatched"),
                SemiJoinModel(first, first, negated = true),
                NodeId("left"),
                NodeId("right"),
            ),
        ),
        terminals = mapOf("unmatched" to NodeId("unmatched")),
    )

    private fun script(vararg right: ScriptEvent) = Script(
        listOf(
            SourceScript(leftSource, listOf(ScriptEvent.Add(writer, "a" to 1))),
            SourceScript(rightSource, right.toList()),
        ),
    )

    @Test
    fun `BS-5 a matching right-side arrival retracts a live left element from the antijoin`() {
        val beforeTheKeyArrives = antiJoin.eval(script(ScriptEvent.Add(writer, "b" to 7)))
        val afterTheKeyArrives = antiJoin.eval(
            script(ScriptEvent.Add(writer, "b" to 7), ScriptEvent.Add(writer, "a" to 9)),
        )

        withClue("no live right row carries key 'a', so the unmatched left row is live") {
            beforeTheKeyArrives shouldBe mapOf("unmatched" to ModelState.SetState(setOf("a" to 1)))
        }
        withClue("('a', 9) arriving on the RIGHT retracts ('a', 1) from the output — non-monotone") {
            afterTheKeyArrives shouldBe mapOf("unmatched" to ModelState.SetState(emptySet()))
        }
    }

    /**
     * The other direction of the same non-monotonicity, and the reason the kernel mints a tag
     * per entry rather than reusing one: when the right row is *removed*, the left row
     * **re-enters** the output with no fresh left-side event to ride. The model re-enters it
     * because a recomputation has no memory of the exit; the kernel needs `MintedTags` hygiene
     * so the re-entry is not left dead under tombstone folding (`[24-OP-SEMIJOIN-02]`).
     */
    @Test
    fun `BS-5 removing the matching right row re-admits the left element with no left-side event`() {
        val reAdmitted = antiJoin.eval(
            script(ScriptEvent.Add(writer, "a" to 9), ScriptEvent.Remove(writer, "a" to 9)),
        )

        reAdmitted shouldBe mapOf("unmatched" to ModelState.SetState(setOf("a" to 1)))
    }

    /**
     * The retraction is keyed, not element-wise: the right row `("a", 9)` shares no *element*
     * with the left row `("a", 1)`, only its key. An implementation that compared elements
     * rather than projected keys would leave the left row live above and fail here too.
     */
    @Test
    fun `BS-5 the right side retracts by KEY, not by element equality`() {
        val differentValueSameKey = antiJoin.eval(script(ScriptEvent.Add(writer, "a" to 9)))
        val differentKey = antiJoin.eval(script(ScriptEvent.Add(writer, "z" to 1)))

        differentValueSameKey shouldBe mapOf("unmatched" to ModelState.SetState(emptySet()))
        withClue("a right row under another key retracts nothing") {
            differentKey shouldBe mapOf("unmatched" to ModelState.SetState(setOf("a" to 1)))
        }
    }

    /**
     * An **unobserved** right-side remove is a no-op on the right's own membership
     * (`[ORA1-MODEL-05]`, `[24-SET-03]`), so the key stays live there and the left row stays
     * retracted. This is the composition of BS-2 with BS-5: the antijoin sees whatever
     * observed-remove membership says, and never re-derives it.
     */
    @Test
    fun `BS-5 an unobserved remove on the right leaves the key live, so the left row stays out`() {
        val otherWriter = WriterId("other")
        val stillMatched = antiJoin.eval(
            script(ScriptEvent.Add(writer, "a" to 9), ScriptEvent.Remove(otherWriter, "a" to 9)),
        )

        stillMatched shouldBe mapOf("unmatched" to ModelState.SetState(emptySet()))
    }
}
