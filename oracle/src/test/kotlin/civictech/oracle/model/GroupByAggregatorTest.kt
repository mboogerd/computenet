package civictech.oracle.model

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * **BS-6** — grouped aggregation and the seven `Aggregators` families, model-level
 * (`ORA1 §MODEL-06`, `[24-OP-GROUPBY-01]`, `[24-OP-GROUPBY-02]`, `[24-AGG-01]`).
 *
 * The feature's example mapping fixes the shape: *given `GroupByCell(keyFn = { it.first },
 * Aggregators.maxOf { it.second })` over adds `("g", 3)`, `("g", 7)`, `("h", 1)`; when `("g",
 * 3)` and `("g", 7)` are both retracted, the result map is `{"h" → 1}` — key `"g"` absent, not
 * `"g" → null` and not `"g" → identity`.* `ORA1 §MODEL-06` widens it to **each** of
 * count/sumOf/avgOf/minOf/maxOf/topK/collectToSet, which is what [everyAggregate] enumerates.
 *
 * Every case is driven through [ReferenceModel.eval] from a [Script], not by handing a
 * [ModelState] to `evaluate`: the retraction has to be a real observed remove folded by
 * [Membership], because "the group's last member was retracted" is the whole point.
 *
 * ## Widths
 *
 * The expected values carry the runtime type the kernel aggregator produces, because
 * [ModelState] equality is structural and a differential run compares it against a real
 * terminal: `count` and `sumOf` are `Long`, `avgOf` is `Double`, `topK` is a `List`,
 * `collectToSet` is a `Set` of the group's **elements**. `minOf`/`maxOf` carry the selector's
 * own type, which is why this file selects an `Int` for them (the kernel bounds those
 * selectors only as `V : Comparable<V>, V : Serializable`) and a `Long` for `sumOf`/`avgOf`
 * (whose kernel selectors are `(E) -> Long` — float sums are order-sensitive).
 */
class GroupByAggregatorTest {

    private val writer = WriterId("w")
    private val source = SourceId("elements")

    /** The group key: the pair's first component. */
    private val first = ElementKey { element -> (element as Pair<*, *>).first }

    /** The extremum/topK selector — an `Int`, which the kernel's `V : Comparable<V>` bound admits. */
    private val secondAsInt = ElementSelector { element -> (element as Pair<*, *>).second }

    /** The sum/avg selector — a `Long`, the width `Aggregators.sumOf`/`avgOf` require. */
    private val secondAsLong = object : LongSelector {
        override fun selectLong(element: Any?): Long = ((element as Pair<*, *>).second as Number).toLong()
        override fun toString(): String = "secondAsLong"
    }

    /**
     * The seven aggregate families, each with what it produces for the live group `{("g", 3),
     * ("g", 7)}` and for `{("h", 1)}`. One table so the group-death assertion below is stated
     * once and holds for every family, rather than seven near-copies of one test.
     */
    private fun everyAggregate(): List<AggregateCase> = listOf(
        AggregateCase("count", Aggregates.count(), groupG = 2L, groupH = 1L),
        AggregateCase("sumOf", Aggregates.sumOf(secondAsLong), groupG = 10L, groupH = 1L),
        AggregateCase("avgOf", Aggregates.avgOf(secondAsLong), groupG = 5.0, groupH = 1.0),
        AggregateCase("minOf", Aggregates.minOf(secondAsInt), groupG = 3, groupH = 1),
        AggregateCase("maxOf", Aggregates.maxOf(secondAsInt), groupG = 7, groupH = 1),
        AggregateCase("topK", Aggregates.topK(2, secondAsInt), groupG = listOf(7, 3), groupH = listOf(1)),
        AggregateCase(
            "collectToSet",
            Aggregates.collectToSet(),
            groupG = setOf("g" to 3, "g" to 7),
            groupH = setOf("h" to 1),
        ),
    )

    private data class AggregateCase(
        val name: String,
        val aggregate: AggregateFunction,
        val groupG: Any?,
        val groupH: Any?,
    )

    private fun groupedBy(key: ElementKey, aggregate: AggregateFunction) = ReferenceModel.terminal(
        "grouped",
        ModelNode.Operator(NodeId("grouped"), GroupByModel(key, aggregate), NodeId("elements")),
        ModelNode.Source(NodeId("elements"), source, SetSourceModel),
    )

    private fun script(vararg events: ScriptEvent) = Script.of(source, *events)

    private val threeAdds = arrayOf<ScriptEvent>(
        ScriptEvent.Add(writer, "g" to 3),
        ScriptEvent.Add(writer, "g" to 7),
        ScriptEvent.Add(writer, "h" to 1),
    )

    @Test
    fun `every aggregate family folds each group from its live elements alone`() {
        everyAggregate().forEach { case ->
            val grouped = groupedBy(first, case.aggregate).eval(script(*threeAdds))

            withClue(case.name) {
                grouped shouldBe mapOf(
                    "grouped" to ModelState.MapState(mapOf("g" to case.groupG, "h" to case.groupH)),
                )
            }
        }
    }

    /**
     * **BS-6 proper** (`ORA1 §MODEL-06`, `[24-OP-GROUPBY-02]`): retracting every member of a
     * group removes the **key**. Not `"g" -> null`, not `"g" -> 0` / `"g" -> emptyList()` /
     * `"g" -> emptySet()` — the identity value each of these families would have started its
     * accumulator from, and the most plausible wrong answer.
     */
    @Test
    fun `BS-6 retracting a group's last member removes the key, for every aggregate family`() {
        val bothGMembersRetracted = script(
            *threeAdds,
            ScriptEvent.Remove(writer, "g" to 3),
            ScriptEvent.Remove(writer, "g" to 7),
        )

        everyAggregate().forEach { case ->
            val grouped = groupedBy(first, case.aggregate).eval(bothGMembersRetracted)

            withClue("${case.name}: group 'g' must be ABSENT, not present with a stale or identity value") {
                grouped shouldBe mapOf("grouped" to ModelState.MapState(mapOf("h" to case.groupH)))
            }
        }
    }

    @Test
    fun `BS-6 retracting every element yields an empty map, not a map of identity values`() {
        val everythingRetracted = script(
            *threeAdds,
            ScriptEvent.Remove(writer, "g" to 3),
            ScriptEvent.Remove(writer, "g" to 7),
            ScriptEvent.Remove(writer, "h" to 1),
        )

        everyAggregate().forEach { case ->
            withClue(case.name) {
                groupedBy(first, case.aggregate).eval(everythingRetracted) shouldBe
                    mapOf("grouped" to ModelState.EMPTY_MAP)
            }
        }
    }

    /**
     * A group survives a *partial* retraction — the case that makes the one above about group
     * death rather than about retraction generally. Retracting `("g", 7)` alone leaves `"g"`
     * present with the aggregate of what remains.
     */
    @Test
    fun `a partial retraction leaves the group present with the aggregate of what remains`() {
        val oneGMemberRetracted = script(*threeAdds, ScriptEvent.Remove(writer, "g" to 7))

        groupedBy(first, Aggregates.maxOf(secondAsInt)).eval(oneGMemberRetracted) shouldBe
            mapOf("grouped" to ModelState.MapState(mapOf("g" to 3, "h" to 1)))
    }

    /**
     * **The equal-selected-value retraction** (`[24-OP-GROUPBY-04]`). Two *distinct* live
     * elements share the selected value `7`. Retracting one must leave the extremum at `7`,
     * and only the second retraction may lower it.
     *
     * This is the case the kernel keeps a whole support multiset for — value → multiplicity in
     * a `TreeMap`, "needed even under set semantics because distinct elements can share an
     * extracted value, and retraction of the current extremum must reshuffle without a
     * re-scan". The model gets it from recomputation: the equal-valued sibling is simply still
     * in the group. That the two mechanisms are unrelated is exactly what makes the pairing
     * worth running (design D2) — and this test is what keeps the model a recomputation.
     *
     * It doubles as `GroupByCell.global`'s case: one constant-key group, `"global"`
     * (`[24-OP-GROUPBY-01]`).
     */
    @Test
    fun `retracting one of two elements sharing the extremum's value keeps the extremum`() {
        val globalMax = ReferenceModel.terminal(
            "global",
            ModelNode.Operator(
                NodeId("global"),
                GroupByModel.global(Aggregates.maxOf(secondAsInt)),
                NodeId("elements"),
            ),
            ModelNode.Source(NodeId("elements"), source, SetSourceModel),
        )
        val adds = arrayOf<ScriptEvent>(
            ScriptEvent.Add(writer, "x" to 7),
            ScriptEvent.Add(writer, "y" to 7),
            ScriptEvent.Add(writer, "z" to 3),
        )

        val allLive = globalMax.eval(script(*adds))
        val oneSevenRetracted = globalMax.eval(script(*adds, ScriptEvent.Remove(writer, "x" to 7)))
        val bothSevensRetracted = globalMax.eval(
            script(*adds, ScriptEvent.Remove(writer, "x" to 7), ScriptEvent.Remove(writer, "y" to 7)),
        )

        allLive shouldBe mapOf("global" to ModelState.MapState(mapOf(GroupByModel.GLOBAL_KEY to 7)))
        withClue("('y', 7) still carries the selected value 7, so the extremum does not move") {
            oneSevenRetracted shouldBe mapOf("global" to ModelState.MapState(mapOf(GroupByModel.GLOBAL_KEY to 7)))
        }
        bothSevensRetracted shouldBe mapOf("global" to ModelState.MapState(mapOf(GroupByModel.GLOBAL_KEY to 3)))
    }

    /**
     * `topK` keeps duplicates, descending — the kernel walks its support multiset emitting each
     * value as many times as its multiplicity, so three elements sharing a selected value
     * yield it three times, and a group with fewer than `k` elements yields all of them.
     */
    @Test
    fun `topK keeps duplicate selected values and yields fewer than k when the group is smaller`() {
        val topThree = groupedBy(first, Aggregates.topK(3, secondAsInt))
        val duplicates = script(
            ScriptEvent.Add(writer, "g" to 7),
            ScriptEvent.Add(writer, "g" to 7),
            ScriptEvent.Add(writer, "g" to 3),
            ScriptEvent.Add(writer, "h" to 1),
        )

        withClue("('g', 7) added twice is ONE live element — the duplicate here is ('g',7) vs itself") {
            topThree.eval(duplicates) shouldBe
                mapOf("grouped" to ModelState.MapState(mapOf("g" to listOf(7, 3), "h" to listOf(1))))
        }

        val sharedValue = script(
            ScriptEvent.Add(writer, "g" to 7),
            ScriptEvent.Add(writer, "g2" to 7),
            ScriptEvent.Add(writer, "g3" to 3),
        )
        val constantKey = ReferenceModel.terminal(
            "grouped",
            ModelNode.Operator(
                NodeId("grouped"),
                GroupByModel.global(Aggregates.topK(3, secondAsInt)),
                NodeId("elements"),
            ),
            ModelNode.Source(NodeId("elements"), source, SetSourceModel),
        )

        withClue("two distinct elements sharing the value 7 yield it twice, descending") {
            constantKey.eval(sharedValue) shouldBe
                mapOf(
                    "grouped" to ModelState.MapState(mapOf(GroupByModel.GLOBAL_KEY to listOf(7, 7, 3))),
                )
        }
    }

    /**
     * The group key is the *keyFn's* value, not the element: two elements that differ
     * everywhere but the key land in one group. A model that grouped by element identity would
     * pass every assertion above and fail here.
     */
    @Test
    fun `elements are grouped by the key function, not by element identity`() {
        val grouped = groupedBy(first, Aggregates.collectToSet()).eval(
            script(ScriptEvent.Add(writer, "k" to "a"), ScriptEvent.Add(writer, "k" to "b")),
        )

        grouped shouldBe mapOf(
            "grouped" to ModelState.MapState(mapOf("k" to setOf("k" to "a", "k" to "b"))),
        )
    }
}
