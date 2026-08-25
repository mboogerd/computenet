package civictech.oracle.model

import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
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
 * This is the **engine-level** purity test, using hand-built model instances over a small
 * pipeline. The full-vocabulary version below — one script of exactly 220 mixed operations
 * across every operator [CoreOperators] and [TaggedOperators] register, evaluated through the
 * real [OperatorCatalog] entries rather than re-constructed model objects — is
 * computenet-4ru.5.3's, which closes the vocabulary out (widened by computenet-jdwy to cover
 * [TaggedOperators]' `orMap` id, which computenet-4ru.5.3 never registered into this sweep).
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
        result["count"] shouldBe ModelState.ScalarState(2L)
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

    // -------------------------------------------------------------------
    // computenet-4ru.5.3: the full-vocabulary purity test (Ex/purity).
    //
    // Every registered operator gets a node wired against real
    // OperatorCatalog entries (never a hand-reconstructed model instance),
    // over a single ~200-event script spanning every source shape the
    // registered operators consume. `[ORA1-MODEL-11]`'s purity is checked
    // exactly as the engine-level tests above check it: two evaluations of
    // one script agree, and the script is structurally unchanged.
    // -------------------------------------------------------------------

    private val fvWriter = WriterId("fv")

    /** Every catalog id [fullVocabularyModel] actually resolved through [OperatorCatalog], for the coverage check below. */
    private val idsExercised = mutableSetOf<String>()

    private fun catalogSource(id: String): SourceModel {
        idsExercised += id
        return OperatorCatalog.entry(id)!!.model as SourceModel
    }

    private fun catalogOperator(id: String): OperatorModel {
        idsExercised += id
        return OperatorCatalog.entry(id)!!.model as OperatorModel
    }

    /** A deterministic mix of Add/Observe/Remove over a small alphabet — repeats force retractions. */
    private fun setChurn(source: SourceId, alphabet: Int, elementAt: (Int) -> Any?, n: Int): SourceScript {
        val events = (0 until n).map { i ->
            val element = elementAt(i % alphabet)
            when (i % 4) {
                0, 1 -> ScriptEvent.Add(fvWriter, element)
                2 -> ScriptEvent.Observe(fvWriter)
                else -> ScriptEvent.Remove(fvWriter, element)
            }
        }
        return SourceScript(source, events)
    }

    /** A deterministic mix of Put/RemoveKey over a small key alphabet, one writer throughout. */
    private fun keyedChurn(source: SourceId, n: Int): SourceScript {
        val events = (0 until n).map { i ->
            val key = "k${i % 5}"
            if (i % 5 == 4) ScriptEvent.RemoveKey(fvWriter, key) else ScriptEvent.Put(fvWriter, key, "v$i")
        }
        return SourceScript(source, events)
    }

    /** A deterministic mix of Increment/Decrement. */
    private fun counterChurn(source: SourceId, n: Int): SourceScript {
        val events = (0 until n).map { i ->
            val amount = (i + 1).toLong()
            if (i % 3 == 0) ScriptEvent.Decrement(fvWriter, amount) else ScriptEvent.Increment(fvWriter, amount)
        }
        return SourceScript(source, events)
    }

    private val plainLeft = SourceId("fv-plain-left")
    private val plainRight = SourceId("fv-plain-right")
    private val keyedSrc = SourceId("fv-keyed")
    private val pairLeft = SourceId("fv-pair-left")
    private val pairRight = SourceId("fv-pair-right")
    private val mapLeft = SourceId("fv-map-left")
    private val mapRight = SourceId("fv-map-right")
    private val counterSrc = SourceId("fv-counter")
    private val pnCounterSrc = SourceId("fv-pncounter")
    private val orMapSrc = SourceId("fv-ormap")

    /**
     * Exactly 220 events across ten sources — a plain-scalar pair, a keyed-set source, a
     * pair-shaped pair (feeding the join-set/semi-join/group-by family), a map-shaped pair
     * (feeding the map-join family, each a single-writer `MapCell` slice per `[ORA1-MODEL-08]`),
     * a counter pair, and a single-writer OR-map source (`ORA2 §MODEL-11`'s
     * `SingleInstanceOrMapModel`, delivery-free so the single-instance restriction it enforces
     * does not fire — see [TaggedOperators]' KDoc). `30+30+20+25+25+20+20+15+15+20 = 220`.
     */
    private fun fullVocabularyScript(): Script = Script(
        listOf(
            setChurn(plainLeft, 7, { i -> "e$i" }, 30),
            setChurn(plainRight, 7, { i -> "e${i + 3}" }, 30),
            keyedChurn(keyedSrc, 20),
            setChurn(pairLeft, 6, { i -> "pk$i" to i.toLong() }, 25),
            setChurn(pairRight, 6, { i -> "pk$i" to (i + 100).toLong() }, 25),
            keyedChurn(mapLeft, 20),
            keyedChurn(mapRight, 20),
            counterChurn(counterSrc, 15),
            counterChurn(pnCounterSrc, 15),
            keyedChurn(orMapSrc, 20),
        ),
    )

    /**
     * One [ReferenceModel] naming every id [CoreOperators] and [TaggedOperators] register,
     * resolved through [OperatorCatalog] rather than reconstructed — so this test exercises the
     * exact models a differential run would.
     */
    private fun fullVocabularyModel(): ReferenceModel {
        val plainLeftNode = ModelNode.Source(NodeId("plainLeft"), plainLeft, catalogSource(CoreOperators.Ids.SET))
        val plainRightNode = ModelNode.Source(NodeId("plainRight"), plainRight, catalogSource(CoreOperators.Ids.SET))
        val keyedNode = ModelNode.Source(NodeId("keyed"), keyedSrc, catalogSource(CoreOperators.Ids.KEYED_SET))
        val pairLeftNode = ModelNode.Source(NodeId("pairLeft"), pairLeft, catalogSource(CoreOperators.Ids.SET))
        val pairRightNode = ModelNode.Source(NodeId("pairRight"), pairRight, catalogSource(CoreOperators.Ids.SET))
        val mapLeftNode = ModelNode.Source(NodeId("mapLeft"), mapLeft, catalogSource(CoreOperators.Ids.MAP))
        val mapRightNode = ModelNode.Source(NodeId("mapRight"), mapRight, catalogSource(CoreOperators.Ids.MAP))
        val counterNode = ModelNode.Source(NodeId("counter"), counterSrc, catalogSource(CoreOperators.Ids.COUNTER))
        val pnCounterNode =
            ModelNode.Source(NodeId("pnCounter"), pnCounterSrc, catalogSource(CoreOperators.Ids.PN_COUNTER))
        val orMapNode =
            ModelNode.Source(NodeId("orMap"), orMapSrc, catalogSource(TaggedOperators.Ids.OR_MAP))

        val filterNode = ModelNode.Operator(NodeId("filter"), catalogOperator(CoreOperators.Ids.FILTER), plainLeftNode.id)
        val flatMapNode =
            ModelNode.Operator(NodeId("flatMapSet"), catalogOperator(CoreOperators.Ids.FLAT_MAP_SET), plainLeftNode.id)
        val mapSetNode = ModelNode.Operator(NodeId("mapSet"), catalogOperator(CoreOperators.Ids.MAP_SET), plainLeftNode.id)

        /*
         * `keyBy` — the pair-shaped bootstrap (computenet-4ru.16). Wired over a plain-scalar
         * source rather than over `pairLeft`, because that is the edge it exists to create: it
         * is what takes a `SetOf(Scalar)` stream into the pair domain the join-set and
         * `groupBy*` family consume. The pair sources above stay as they are — this test's
         * subject is model purity across the whole vocabulary, not topology realism, and
         * rerouting the eleven pair-shaped nodes through `keyBy` would change what they are
         * evaluated on for no gain here. `PairShapeBootstrapTest` is where the real generated
         * wiring is checked.
         */
        val keyByNode = ModelNode.Operator(NodeId("keyBy"), catalogOperator(CoreOperators.Ids.KEY_BY), plainLeftNode.id)
        val countNode = ModelNode.Operator(NodeId("count"), catalogOperator(CoreOperators.Ids.COUNT), filterNode.id)
        val unionNode = ModelNode.Operator(
            NodeId("union"),
            catalogOperator(CoreOperators.Ids.UNION),
            listOf(plainLeftNode.id, plainRightNode.id),
        )
        val presenceNode = ModelNode.Operator(
            NodeId("presenceCount"),
            catalogOperator(CoreOperators.Ids.PRESENCE_COUNT),
            listOf(plainLeftNode.id, plainRightNode.id),
        )
        val quorumNode = ModelNode.Operator(
            NodeId("quorumSet"),
            catalogOperator(CoreOperators.Ids.QUORUM_SET),
            listOf(plainLeftNode.id, plainRightNode.id),
        )
        val intersectNode = ModelNode.Operator(
            NodeId("intersect"),
            catalogOperator(CoreOperators.Ids.INTERSECT),
            listOf(plainLeftNode.id, plainRightNode.id),
        )
        val joinSetNode = ModelNode.Operator(
            NodeId("joinSet"),
            catalogOperator(CoreOperators.Ids.JOIN_SET),
            listOf(pairLeftNode.id, pairRightNode.id),
        )
        val semiJoinNode = ModelNode.Operator(
            NodeId("semiJoin"),
            catalogOperator(CoreOperators.Ids.SEMI_JOIN),
            listOf(pairLeftNode.id, pairRightNode.id),
        )
        val antiJoinNode = ModelNode.Operator(
            NodeId("antiJoin"),
            catalogOperator(CoreOperators.Ids.ANTI_JOIN),
            listOf(pairLeftNode.id, pairRightNode.id),
        )
        val groupByNodes = CoreOperators.Ids.GROUP_BY_AGGREGATES.map { id ->
            ModelNode.Operator(NodeId(id), catalogOperator(id), pairLeftNode.id)
        }
        val groupByGlobalNode = ModelNode.Operator(
            NodeId(CoreOperators.Ids.GROUP_BY_GLOBAL),
            catalogOperator(CoreOperators.Ids.GROUP_BY_GLOBAL),
            pairLeftNode.id,
        )
        val joinNode = ModelNode.Operator(
            NodeId("join"),
            catalogOperator(CoreOperators.Ids.JOIN),
            listOf(mapLeftNode.id, mapRightNode.id),
        )
        val combineLatestNode = ModelNode.Operator(
            NodeId("combineLatest"),
            catalogOperator(CoreOperators.Ids.COMBINE_LATEST),
            listOf(mapLeftNode.id, mapRightNode.id),
        )
        val lookupJoinNode = ModelNode.Operator(
            NodeId("lookupJoin"),
            catalogOperator(CoreOperators.Ids.LOOKUP_JOIN),
            listOf(mapLeftNode.id, mapRightNode.id),
        )

        val operatorNodes = listOf(
            filterNode, flatMapNode, mapSetNode, keyByNode, countNode, unionNode, presenceNode, quorumNode,
            intersectNode, joinSetNode, semiJoinNode, antiJoinNode, joinNode, combineLatestNode, lookupJoinNode,
            groupByGlobalNode,
        ) + groupByNodes

        val sourceNodes = listOf(
            plainLeftNode, plainRightNode, keyedNode, pairLeftNode, pairRightNode,
            mapLeftNode, mapRightNode, counterNode, pnCounterNode, orMapNode,
        )

        val allNodes: List<ModelNode> = sourceNodes + operatorNodes
        val terminals = allNodes.associate { it.id.id to it.id }

        return ReferenceModel(allNodes, terminals)
    }

    /**
     * `[ORA1-MODEL-11]`'s purity, at full-vocabulary scale: every operator [CoreOperators] and
     * [TaggedOperators] register gets a node, wired against the real [OperatorCatalog] entries,
     * over one 220-event script — evaluated twice, with equal results and an unmutated script.
     *
     * `TaggedOperators.registerAll()` sits beside `CoreOperators.registerAll()` here rather than
     * only in `TaggedOperatorsTest`: this is the *full-vocabulary* sweep, and `orMap` — the one
     * id `TaggedOperators` registers — was never resolved through it (computenet-jdwy). The
     * coverage assertion below grows to the **union** of both catalogs' registered ids rather
     * than silently widening to one of them, so it still fails the moment a future registration
     * (in either file) is added without a node here.
     */
    @Test
    fun `the full registered vocabulary evaluated twice on one 220-event script yields equal results and an unmutated script`() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
        TaggedOperators.registerAll()
        idsExercised.clear()
        try {
            val model = fullVocabularyModel()
            val subject = fullVocabularyScript()
            val untouchedTwin = fullVocabularyScript()
            val registeredIds = CoreOperators.Ids.ALL.toSet() + TaggedOperators.Ids.ALL.toSet()

            withClue("sanity: the script really is exactly 220 events") {
                subject.slices.sumOf { it.events.size } shouldBe 220
            }
            withClue(
                "every id CoreOperators/TaggedOperators register was resolved through " +
                    "OperatorCatalog while building this test's graph — missing: " +
                    "${registeredIds - idsExercised}, unexpected: ${idsExercised - registeredIds}",
            ) {
                idsExercised shouldBe registeredIds
            }

            val first = model.eval(subject)
            val second = model.eval(subject)

            first shouldBe second
            withClue("[ORA1-MODEL-11]: evaluation must not mutate the script") {
                subject shouldBe untouchedTwin
            }
        } finally {
            OperatorCatalog.reset()
        }
    }

    // -------------------------------------------------------------------
    // computenet-4ru.1.1 (ORA2): `ORA2 §MODEL-07`, the same requirement in
    // the same idiom, over the tagged/keyed models — which ORA1's
    // full-vocabulary test above cannot reach, because they are not
    // `OperatorCatalog` entries yet (registration is a sibling ORA2 task)
    // and, for the dot model, because they fold a whole multi-instance
    // `Script` rather than one slice.
    //
    // Both halves in one test because they are one requirement: equal
    // results across two evaluations, and a structurally unchanged script,
    // compared against an independently constructed twin rather than
    // against the same object.
    // -------------------------------------------------------------------

    @Test
    fun `ORA2 the tagged and keyed models are pure functions of one shared script`() {
        val one = SourceId("replica-1")
        val two = SourceId("replica-2")

        fun taggedScript() = Script(
            listOf(
                SourceScript(
                    one,
                    listOf(
                        ScriptEvent.Put(writer, "k", "v1"),
                        ScriptEvent.Add(writer, "xx"),
                        ScriptEvent.Increment(writer, 7),
                        ScriptEvent.RemoveKey(writer, "k"),
                        ScriptEvent.Put(writer, "k", "v2"),
                    ),
                ),
                SourceScript(
                    two,
                    listOf(
                        ScriptEvent.Put(writer, "k", "other"),
                        ScriptEvent.Add(writer, "yyy"),
                        ScriptEvent.Decrement(writer, 2),
                    ),
                    deliveries = listOf(Delivery(afterEvents = 1, from = one, throughEvents = 5)),
                ),
            ),
        )

        val dots = DotModel(DotOrder.ranked(one, two))
        val grouped = MergeableGroupByModel(
            keyOf = { element -> element.toString().length },
            accumulate = { 1L },
            merge = { left, right -> (left as Long) + (right as Long) },
        )

        val subject = taggedScript()
        val untouchedTwin = taggedScript()

        withClue("ORA2 §MODEL-07: two evaluations of one script are equal") {
            dots.evaluate(subject) shouldBe dots.evaluate(subject)
            dots.perInstance(subject) shouldBe dots.perInstance(subject)
            KeyedReputModel.evaluate(subject.slice(one)) shouldBe KeyedReputModel.evaluate(subject.slice(one))
            grouped.evaluate(subject.slice(two)) shouldBe grouped.evaluate(subject.slice(two))
            PnCounterConvergenceModel.evaluate(subject) shouldBe PnCounterConvergenceModel.evaluate(subject)
        }
        withClue("a mixed-vocabulary script really did produce content, so equality is not vacuous") {
            dots.evaluate(subject) shouldBe ModelState.MapState(mapOf("k" to "v2"))
            PnCounterConvergenceModel.evaluate(subject) shouldBe ModelState.ScalarState(5L)
        }
        withClue("ORA2 §MODEL-07: evaluation must not mutate the script") {
            subject shouldBe untouchedTwin
        }
    }
}
