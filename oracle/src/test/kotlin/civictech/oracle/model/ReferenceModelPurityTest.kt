package civictech.oracle.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [ReferenceModel.eval] as `[ORA1-MODEL-01]`'s carrier — every terminal's state from a
 * complete script alone, executing no kernel cell — and `[ORA1-MODEL-11]`'s purity: two
 * evaluations of one script produce equal results and the script is structurally unchanged
 * afterwards.
 *
 * This is the **engine-level** purity test. The full-vocabulary version — one script of ~200
 * mixed operations across every registered operator — belongs to computenet-4ru.5.3, which
 * closes the vocabulary out.
 */
class ReferenceModelPurityTest {

    private val writer = WriterId("w")
    private val left = SourceId("left")
    private val right = SourceId("right")

    /**
     * A small but genuinely layered graph: two OR-set sources, a union over both, a filter
     * over the union, a count over the filter, and a quorum straight over the two sources.
     * Four terminals, two of them below a shared node, so a fold that leaked state between
     * nodes or between calls would show.
     */
    private fun pipeline(): ReferenceModel {
        val leftNode = ModelNode.Source(NodeId("left"), left, SetSourceModel)
        val rightNode = ModelNode.Source(NodeId("right"), right, SetSourceModel)
        val union = ModelNode.Operator(NodeId("union"), UnionSetModel, listOf(leftNode.id, rightNode.id))
        val filter = ModelNode.Operator(
            NodeId("filter"),
            FilterModel { element -> element.toString().length == 1 },
            listOf(union.id),
        )
        val count = ModelNode.Operator(NodeId("count"), CountModel, listOf(filter.id))
        val quorum = ModelNode.Operator(
            NodeId("quorum"),
            QuorumSetModel { arms -> arms },
            listOf(leftNode.id, rightNode.id),
        )
        return ReferenceModel(
            // Deliberately NOT in dependency order: eval sorts the nodes itself.
            nodes = listOf(count, filter, union, quorum, rightNode, leftNode),
            terminals = mapOf(
                "union" to union.id,
                "filter" to filter.id,
                "count" to count.id,
                "quorum" to quorum.id,
            ),
        )
    }

    private fun script(): Script = Script(
        listOf(
            SourceScript(
                left,
                listOf(
                    ScriptEvent.Add(writer, "a"),
                    ScriptEvent.Add(writer, "bb"),
                    ScriptEvent.Add(writer, "shared"),
                    ScriptEvent.Remove(writer, "bb"),
                    ScriptEvent.Add(writer, "bb"),
                ),
            ),
            SourceScript(
                right,
                listOf(
                    ScriptEvent.Add(writer, "c"),
                    ScriptEvent.Add(writer, "shared"),
                ),
            ),
        ),
    )

    @Test
    fun `eval computes every terminal from the script alone`() {
        val result = pipeline().eval(script())

        result["union"] shouldBe ModelState.SetState(setOf("a", "bb", "shared", "c"))
        result["filter"] shouldBe ModelState.SetState(setOf("a", "c"))
        result["count"] shouldBe ModelState.ScalarState(2)
        withClue("intersection quorum: only 'shared' is asserted by both sources") {
            result["quorum"] shouldBe ModelState.SetState(setOf("shared"))
        }
    }

    /**
     * `[ORA1-MODEL-11]`. Both halves, in one test because they are one requirement: equal
     * results across two evaluations, and a structurally unchanged script.
     *
     * The script comparison is against an independently constructed twin rather than against
     * the same object, so it is a *structural* check — comparing an object with itself would
     * pass however much [ReferenceModel.eval] had mutated it.
     */
    @Test
    fun `evaluating one script twice yields equal results and leaves the script unchanged`() {
        val model = pipeline()
        val subject = script()
        val untouchedTwin = script()

        val first = model.eval(subject)
        val second = model.eval(subject)

        first shouldBe second
        withClue("[ORA1-MODEL-11]: evaluation must not mutate the script") {
            subject shouldBe untouchedTwin
        }
    }

    @Test
    fun `a source the script never drives folds to its empty state rather than failing`() {
        val undriven = ModelNode.Source(NodeId("undriven"), SourceId("nobody"), SetSourceModel)
        val model = ReferenceModel(listOf(undriven), mapOf("t" to undriven.id))

        model.eval(Script.EMPTY)["t"] shouldBe ModelState.SetState(emptySet())
    }

    @Test
    fun `operator inputs are handed over in declaration order`() {
        val first = ModelNode.Source(NodeId("first"), left, SetSourceModel)
        val second = ModelNode.Source(NodeId("second"), right, SetSourceModel)
        val recorder = object : OperatorModel {
            override fun evaluate(inputs: List<ModelState>): ModelState =
                ModelState.ScalarState(inputs.map { (it as ModelState.SetState).elements.toList() })
        }
        val ordered = ModelNode.Operator(NodeId("ordered"), recorder, listOf(second.id, first.id))
        val model = ReferenceModel(listOf(first, second, ordered), mapOf("t" to ordered.id))

        val result = model.eval(
            Script(
                listOf(
                    SourceScript(left, listOf(ScriptEvent.Add(writer, "L"))),
                    SourceScript(right, listOf(ScriptEvent.Add(writer, "R"))),
                ),
            ),
        )

        withClue("port order is [second, first], so the R-side state comes first") {
            result["t"] shouldBe ModelState.ScalarState(listOf(listOf("R"), listOf("L")))
        }
    }

    @Test
    fun `a dependency cycle fails by name rather than folding an arbitrary iteration`() {
        val a = ModelNode.Operator(NodeId("a"), UnionSetModel, listOf(NodeId("b")))
        val b = ModelNode.Operator(NodeId("b"), UnionSetModel, listOf(NodeId("a")))
        val model = ReferenceModel(listOf(a, b), mapOf("t" to a.id))

        val failure = shouldThrow<IllegalStateException> { model.eval(Script.EMPTY) }

        failure.message!! shouldContain "dependency cycle"
        failure.message!! shouldContain "[a, b]"
    }

    @Test
    fun `a terminal naming an undeclared node is rejected at construction`() {
        val node = ModelNode.Source(NodeId("s"), left, SetSourceModel)

        val failure = shouldThrow<IllegalArgumentException> {
            ReferenceModel(listOf(node), mapOf("t" to NodeId("missing")))
        }

        failure.message!! shouldContain "which the model does not declare"
    }

    @Test
    fun `an operator naming an undeclared input is rejected at construction`() {
        val node = ModelNode.Operator(NodeId("op"), CountModel, listOf(NodeId("missing")))

        val failure = shouldThrow<IllegalArgumentException> { ReferenceModel(listOf(node), emptyMap()) }

        failure.message!! shouldContain "which the model does not declare"
    }

    @Test
    fun `a script may not carry two slices for one source`() {
        val failure = shouldThrow<IllegalArgumentException> {
            Script(listOf(SourceScript(left, emptyList()), SourceScript(left, emptyList())))
        }

        failure.message!! shouldContain "at most one slice per source"
    }
}
