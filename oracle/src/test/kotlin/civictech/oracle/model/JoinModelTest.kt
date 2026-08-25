package civictech.oracle.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The batch denotation of the binary set family and the map-shaped join family —
 * computenet-4ru.5.2's half of `ORA1 §MODEL-02` apart from grouped aggregation, which is
 * `GroupByAggregatorTest`'s, and the antijoin retraction case, which is
 * `SemiJoinRetractionTest`'s.
 *
 * Model-level throughout: input [ModelState]s go in, a [ModelState] comes out, and no kernel
 * cell is executed (`ORA1 §MODEL-01`). Nothing here names a tag, a tag count or a wave
 * (`ORA1 §MODEL-03`, `ORA1 §MODEL-07`) — the point being that a minted-tag operator's
 * *observable* answer is a set of live combinations and needs no such notion.
 */
class JoinModelTest {

    /** The pair's first component — the canonical join/group key of a `(key, value)` stream. */
    private val first = ElementKey { element -> (element as Pair<*, *>).first }

    /** `combine(a, b)` = the two rows' values, dropping the join key. Many-to-one by construction. */
    private val values = ElementCombiner { left, right ->
        (left as Pair<*, *>).second to (right as Pair<*, *>).second
    }

    /** Named `liveSet`/`keyed`, not `setOf`/`mapOf`: shadowing the stdlib builders would make
     *  every `mapOf` in this file — including a [ReferenceModel]'s `terminals` — ambiguous. */
    private fun liveSet(vararg elements: Any?) = ModelState.SetState(elements.toSet())

    private fun keyed(vararg entries: Pair<Any?, Any?>) = ModelState.MapState(entries.toMap())

    // -- IntersectSetCell ---------------------------------------------------

    @Test
    fun `IntersectSetCell keeps the elements live on both sides`() {
        val left = liveSet("both", "left-only")
        val right = liveSet("both", "right-only")

        IntersectSetModel.evaluate(listOf(left, right)) shouldBe liveSet("both")
    }

    @Test
    fun `IntersectSetCell empties when either side loses the shared element`() {
        withClue("a side that went empty intersects to nothing") {
            IntersectSetModel.evaluate(listOf(liveSet("x"), liveSet())) shouldBe liveSet()
        }
        IntersectSetModel.evaluate(listOf(liveSet(), liveSet())) shouldBe liveSet()
    }

    @Test
    fun `a binary model refuses a non-binary input list by name`() {
        val failure = shouldThrow<IllegalArgumentException> {
            IntersectSetModel.evaluate(listOf(liveSet("x")))
        }

        failure.message!! shouldContain "IntersectSetModel is binary"
    }

    // -- JoinSetCell --------------------------------------------------------

    @Test
    fun `JoinSetCell is the set of live combinations under matching keys`() {
        val join = JoinSetModel(first, first, values)
        val left = liveSet("k1" to "a", "k2" to "b", "unmatched" to "c")
        val right = liveSet("k1" to "x", "k2" to "y", "other" to "z")

        join.evaluate(listOf(left, right)) shouldBe liveSet("a" to "x", "b" to "y")
    }

    /**
     * `[24-OP-JOINSET-02]`: many-to-one `combine` outputs survive until their **last**
     * contributing pair dies — the kernel's per-pair tag collapse, and plain set membership
     * here. Two independent pairs collide onto `("v", "w")`; retracting one leaves it live,
     * retracting both removes it. The divergent naive form the requirement warns about
     * (deleting the whole output element on one pair's exit) is what this discriminates.
     */
    @Test
    fun `JoinSetCell a many-to-one output survives until its last contributing pair dies`() {
        val join = JoinSetModel(first, first, values)
        val right = liveSet("k1" to "w", "k2" to "w")

        val bothPairs = join.evaluate(listOf(liveSet("k1" to "v", "k2" to "v"), right))
        val onePair = join.evaluate(listOf(liveSet("k2" to "v"), right))
        val noPair = join.evaluate(listOf(liveSet(), right))

        bothPairs shouldBe liveSet("v" to "w")
        withClue("one of the two colliding pairs retracted: the output element is still live") {
            onePair shouldBe liveSet("v" to "w")
        }
        noPair shouldBe liveSet()
    }

    @Test
    fun `JoinSetCell many-to-many keys yield every pair`() {
        val join = JoinSetModel(first, first, values)
        val left = liveSet("k" to "a1", "k" to "a2")
        val right = liveSet("k" to "b1", "k" to "b2")

        join.evaluate(listOf(left, right)) shouldBe
            liveSet("a1" to "b1", "a1" to "b2", "a2" to "b1", "a2" to "b2")
    }

    /** `crossProduct` is the equi-join on the unit key (`[24-OP-JOINSET-02]`). */
    @Test
    fun `JoinSetCell on a constant key is the cross product`() {
        val unit = ElementKey { Unit }
        val cross = JoinSetModel(unit, unit, ElementCombiner { left, right -> left to right })

        cross.evaluate(listOf(liveSet("a", "b"), liveSet(1, 2))) shouldBe
            liveSet("a" to 1, "a" to 2, "b" to 1, "b" to 2)
    }

    // -- SemiJoinCell, positive polarity ------------------------------------

    @Test
    fun `SemiJoinCell keeps the live left rows whose key is among the live right keys`() {
        val semiJoin = SemiJoinModel(first, first, negated = false)
        val left = liveSet("k1" to "a", "k2" to "b")
        val right = liveSet("k1" to "anything")

        semiJoin.evaluate(listOf(left, right)) shouldBe liveSet("k1" to "a")
    }

    /**
     * The two polarities partition the live left set — no row is in both, none is in neither.
     * That is `[24-OP-SEMIJOIN-01]`'s `A ⋉ B` / `A ▷ B` pair stated as one property, and it
     * fails for any implementation that reads the negation as anything but a complement.
     */
    @Test
    fun `the two SemiJoinCell polarities partition the live left set`() {
        val left = liveSet("k1" to "a", "k2" to "b", "k3" to "c")
        val right = liveSet("k1" to "x", "k3" to "y")
        val inputs = listOf(left, right)

        val matched = (SemiJoinModel(first, first, negated = false).evaluate(inputs) as ModelState.SetState)
        val unmatched = (SemiJoinModel(first, first, negated = true).evaluate(inputs) as ModelState.SetState)

        matched.elements shouldBe liveSet("k1" to "a", "k3" to "c").elements
        unmatched.elements shouldBe liveSet("k2" to "b").elements
        withClue("every live left row is in exactly one polarity") {
            (matched.elements + unmatched.elements) shouldBe left.elements
            matched.elements.intersect(unmatched.elements) shouldBe emptySet()
        }
    }

    /** `differenceSet` is the antijoin on identity keys (`[24-OP-SEMIJOIN-01]`). */
    @Test
    fun `SemiJoinCell negated on identity keys is set difference`() {
        val identity = ElementKey { element -> element }
        val difference = SemiJoinModel(identity, identity, negated = true)

        difference.evaluate(listOf(liveSet("a", "b", "c"), liveSet("b"))) shouldBe liveSet("a", "c")
    }

    // -- JoinCell -----------------------------------------------------------

    @Test
    fun `JoinCell pairs the latest values of every key both sides hold`() {
        val left = keyed("k1" to "v1", "k2" to "v2")
        val right = keyed("k1" to "w1", "k3" to "w3")

        JoinModel.evaluate(listOf(left, right)) shouldBe keyed("k1" to ("v1" to "w1"))
    }

    @Test
    fun `JoinCell drops a key either side no longer holds`() {
        JoinModel.evaluate(listOf(keyed("k" to "v"), keyed())) shouldBe keyed()
        JoinModel.evaluate(listOf(keyed(), keyed("k" to "w"))) shouldBe keyed()
    }

    // -- CombineLatestCell --------------------------------------------------

    /** The outer semantics: a key held by only one side still produces output, null-extended. */
    @Test
    fun `CombineLatestCell null-extends a key held by only one side`() {
        val combine = CombineLatestModel(KeyedCombiner { _, left, right -> "$left|$right" })

        combine.evaluate(listOf(keyed("both" to "v", "leftOnly" to "l"), keyed("both" to "w", "rightOnly" to "r"))) shouldBe
            keyed("both" to "v|w", "leftOnly" to "l|null", "rightOnly" to "null|r")
    }

    /**
     * `combine` returning null drops the key — the cell's documented group-death/filtering
     * behaviour, here as "only emit keys both sides hold", which turns the outer combine into
     * an inner one.
     */
    @Test
    fun `CombineLatestCell a combine returning null drops the key`() {
        val innerOnly = CombineLatestModel(
            KeyedCombiner { _, left, right -> if (left == null || right == null) null else "$left+$right" },
        )

        innerOnly.evaluate(listOf(keyed("both" to "v", "leftOnly" to "l"), keyed("both" to "w"))) shouldBe
            keyed("both" to "v+w")
    }

    /**
     * No ghost keys: a key absent from both sides is never handed to `combine` at all, so a
     * combiner that would happily produce a value for it still yields nothing. The kernel
     * guards this explicitly (`if (k in leftMap || k in rightMap)`); here it is structural, and
     * this test is what keeps it structural.
     */
    @Test
    fun `CombineLatestCell asks its combiner about no key either side has dropped`() {
        val everythingCombiner = CombineLatestModel(KeyedCombiner { key, _, _ -> "value-for-$key" })

        everythingCombiner.evaluate(listOf(keyed(), keyed())) shouldBe keyed()
    }

    // -- LookupJoinCell -----------------------------------------------------

    @Test
    fun `LookupJoinCell enriches each fact with its dimension row, keyed by the fact key`() {
        val lookup = LookupJoinModel(
            foreignKey = ElementKey { key -> key.toString().take(1) },
            combine = KeyedCombiner { _, fact, dimension -> "$fact@$dimension" },
        )
        val facts = keyed("a1" to "first", "a2" to "second", "b1" to "third")
        val dimensions = keyed("a" to "A-row", "b" to "B-row")

        withClue("one dimension row serves every fact projecting to it") {
            lookup.evaluate(listOf(facts, dimensions)) shouldBe
                keyed("a1" to "first@A-row", "a2" to "second@A-row", "b1" to "third@B-row")
        }
    }

    /** Left-outer: a fact whose dimension row is absent still emits, with a null dimension. */
    @Test
    fun `LookupJoinCell emits a fact whose dimension row is absent, with a null dimension`() {
        val lookup = LookupJoinModel(
            foreignKey = ElementKey { key -> key.toString().take(1) },
            combine = KeyedCombiner { _, fact, dimension -> "$fact@$dimension" },
        )

        lookup.evaluate(listOf(keyed("z9" to "orphan"), keyed("a" to "A-row"))) shouldBe
            keyed("z9" to "orphan@null")
    }

    /** Returning null filters the fact out — the cell's `need > 0`-style guard. */
    @Test
    fun `LookupJoinCell a combine returning null filters the fact out`() {
        val matchedOnly = LookupJoinModel(
            foreignKey = ElementKey { key -> key.toString().take(1) },
            combine = KeyedCombiner { _, fact, dimension -> dimension?.let { "$fact@$it" } },
        )

        matchedOnly.evaluate(listOf(keyed("a1" to "kept", "z9" to "dropped"), keyed("a" to "A-row"))) shouldBe
            keyed("a1" to "kept@A-row")
    }

    /** The dimension table is a lookup, never a key of the result: an unreferenced row emits nothing. */
    @Test
    fun `LookupJoinCell an unreferenced dimension row contributes no output key`() {
        val lookup = LookupJoinModel(
            foreignKey = ElementKey { key -> key.toString().take(1) },
            combine = KeyedCombiner { _, fact, dimension -> "$fact@$dimension" },
        )

        lookup.evaluate(listOf(keyed(), keyed("a" to "A-row", "b" to "B-row"))) shouldBe keyed()
    }

    // -- composition through the evaluator ----------------------------------

    /**
     * The whole family reached through [ReferenceModel] rather than by calling `evaluate`
     * directly — `ORA1 §MODEL-01` is about evaluating a *graph* from a script alone, and a map
     * -shaped join can only be reached that way today: no map-shaped SOURCE is in the
     * vocabulary yet (`MapCell` is computenet-4ru.5.3), so its inputs come from two group-by
     * nodes, which is also how a generated case will reach it.
     */
    @Test
    fun `a set source folds through group-by into a map join, evaluated from the script alone`() {
        val writer = WriterId("w")
        val sales = SourceId("sales")
        val quotas = SourceId("quotas")

        val model = ReferenceModel(
            nodes = listOf(
                ModelNode.Source(NodeId("sales"), sales, SetSourceModel),
                ModelNode.Source(NodeId("quotas"), quotas, SetSourceModel),
                ModelNode.Operator(NodeId("sold"), GroupByModel(first, Aggregates.count()), NodeId("sales")),
                ModelNode.Operator(NodeId("quota"), GroupByModel(first, Aggregates.count()), NodeId("quotas")),
                ModelNode.Operator(NodeId("joined"), JoinModel, NodeId("sold"), NodeId("quota")),
            ),
            terminals = mapOf("joined" to NodeId("joined")),
        )
        val script = Script(
            listOf(
                SourceScript(
                    sales,
                    listOf(
                        ScriptEvent.Add(writer, "north" to "deal-1"),
                        ScriptEvent.Add(writer, "north" to "deal-2"),
                        ScriptEvent.Add(writer, "south" to "deal-3"),
                    ),
                ),
                SourceScript(quotas, listOf(ScriptEvent.Add(writer, "north" to "quota-1"))),
            ),
        )

        withClue("'south' has no quota group, so the inner join drops it") {
            model.eval(script) shouldBe mapOf("joined" to keyed("north" to (2L to 1L)))
        }
    }
}
