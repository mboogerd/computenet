package civictech.oracle.model

import civictech.cell.Propagate
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.data.op.UntagCell
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.TerminalSpec
import civictech.oracle.gen.TopologyNode
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.RunOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * [KeyedReputModel] (`ORA2 §MODEL-08`), [MergeableGroupByModel] (`ORA2 §MODEL-09`) and
 * [PnCounterConvergenceModel] (`ORA2 §MODEL-10`) at model level.
 *
 * As in [DotModelTest], these are the *model's* halves of BS-10, BS-11 and BS-12; the kernel
 * halves — the same properties driven through real cells — land with the sweep task.
 */
class TaggedKeyedModelTest {

    private val a = SourceId("A")
    private val b = SourceId("B")
    private val c = SourceId("C")
    private val w = WriterId("w")
    private val v = WriterId("v")

    // -----------------------------------------------------------------------
    // ORA2 §MODEL-08 — KeyedSetCell per-key re-put atomicity
    // -----------------------------------------------------------------------

    /**
     * BS-10 at model level: a re-put key binds **exactly one** element at every prefix — never
     * two (the retract has not landed), never zero (the add has not). Walking every prefix is the
     * point; asserting only the final table would pass against a model that tore in the middle.
     */
    @Test
    fun `BS-10 a re-put key binds exactly one element at every script prefix`() {
        val slice = SourceScript(
            a,
            listOf(
                ScriptEvent.Put(w, "k", "e1"),
                ScriptEvent.Put(w, "k", "e2"),
                ScriptEvent.Put(v, "k", "e3"),
                ScriptEvent.Put(w, "k", "e4"),
            ),
        )

        val prefixes = KeyedReputModel.bindingsAtEachPrefix(slice)

        prefixes.size shouldBe slice.events.size + 1
        withClue("before the first put the key is simply absent") {
            prefixes.first().containsKey("k") shouldBe false
        }
        prefixes.drop(1).forEachIndexed { index, bindings ->
            withClue("prefix ${index + 1}: $bindings") {
                bindings.keys shouldBe setOf("k")
                bindings.getValue("k") shouldBe listOf("e1", "e2", "e3", "e4")[index]
            }
        }
    }

    @Test
    fun `a removed key is absent, and re-putting it binds one element again`() {
        val slice = SourceScript(
            a,
            listOf(
                ScriptEvent.Put(w, "k", "e1"),
                ScriptEvent.RemoveKey(w, "k"),
                ScriptEvent.Put(w, "k", "e2"),
            ),
        )

        val prefixes = KeyedReputModel.bindingsAtEachPrefix(slice)

        prefixes[1] shouldBe mapOf("k" to "e1")
        withClue("[24-SET-03]: the removed key is ABSENT, not present with a stale element") {
            prefixes[2] shouldBe emptyMap()
        }
        prefixes[3] shouldBe mapOf("k" to "e2")
    }

    @Test
    fun `two keys binding the same element stay two keys — the atomicity observable keeps the key`() {
        val slice = SourceScript(a, listOf(ScriptEvent.Put(w, "k1", "same"), ScriptEvent.Put(w, "k2", "same")))

        withClue("KeyedSetSourceModel's element set would collapse these to one; this model must not") {
            KeyedReputModel.evaluate(slice) shouldBe ModelState.MapState(mapOf("k1" to "same", "k2" to "same"))
        }
    }

    @Test
    fun `re-putting the identical element and removing an unheld key are both no-ops`() {
        val slice = SourceScript(
            a,
            listOf(
                ScriptEvent.Put(w, "k", "e"),
                ScriptEvent.Put(w, "k", "e"),
                ScriptEvent.RemoveKey(w, "never-put"),
            ),
        )

        KeyedReputModel.bindingsAtEachPrefix(slice).drop(1).forEach { it shouldBe mapOf("k" to "e") }
    }

    // -----------------------------------------------------------------------
    // ORA2 §MODEL-09 — MergeableGroupByCell grows and never retracts
    // -----------------------------------------------------------------------

    /** Group by string length; accumulate each element as `1L`; merge by addition. */
    private val countingGroupBy = MergeableGroupByModel(
        keyOf = { element -> element.toString().length },
        accumulate = { 1L },
        merge = { left, right -> (left as Long) + (right as Long) },
    )

    /**
     * BS-11 at model level: removals feed through and the aggregate **does not shrink**. The
     * assertion is over every prefix, so a model that retracted at any point would fail even if
     * its final answer happened to agree.
     */
    @Test
    fun `BS-11 removals do not retract — the aggregate never shrinks`() {
        val slice = SourceScript(
            a,
            listOf(
                ScriptEvent.Add(w, "xx"),
                ScriptEvent.Add(w, "yy"),
                ScriptEvent.Remove(w, "xx"),
                ScriptEvent.Remove(w, "yy"),
            ),
        )

        val prefixes = countingGroupBy.aggregatesAtEachPrefix(slice)

        prefixes[2] shouldBe mapOf(2 to 2L)
        withClue("ORA2 §MODEL-09: the absence of retraction IS the specification") {
            prefixes[3] shouldBe mapOf(2 to 2L)
            prefixes[4] shouldBe mapOf(2 to 2L)
        }
        prefixes.zipWithNext().forEach { (earlier, later) ->
            withClue("monotone: $earlier then $later") {
                earlier.forEach { (key, value) ->
                    (later.getValue(key) as Long) shouldBeGreaterThanOrEqual (value as Long)
                }
            }
        }
    }

    /**
     * The contrast that makes the previous test a *statement* rather than an omission:
     * [GroupByModel], the retracting sibling, gives a different answer for the same script — the
     * key dies with its last live element (`[24-OP-GROUPBY-02]`). ORA2 asserts the divergence
     * explicitly rather than leaving it implicit.
     */
    @Test
    fun `BS-11 the retracting group-by disagrees, and that disagreement is the specified difference`() {
        val slice = SourceScript(
            a,
            listOf(ScriptEvent.Add(w, "xx"), ScriptEvent.Add(w, "yy"), ScriptEvent.Remove(w, "xx")),
        )
        val retracting = GroupByModel(ElementKey { it.toString().length }, Aggregates.count())

        val mergeable = countingGroupBy.evaluate(slice)
        val nonMergeable = retracting.evaluate(listOf(SetSourceModel.evaluate(slice)))

        mergeable shouldBe ModelState.MapState(mapOf(2 to 2L))
        withClue("GroupByCell recomputes from live membership; MergeableGroupByCell cannot un-merge") {
            nonMergeable shouldBe ModelState.MapState(mapOf(2 to 1L))
        }
    }

    @Test
    fun `a re-add folds again, because the cell folds every arriving add`() {
        val slice = SourceScript(
            a,
            listOf(ScriptEvent.Add(w, "xx"), ScriptEvent.Remove(w, "xx"), ScriptEvent.Add(w, "xx")),
        )

        withClue("per add event, not per distinct element — a counted accumulator can tell") {
            countingGroupBy.evaluate(slice) shouldBe ModelState.MapState(mapOf(2 to 2L))
        }
    }

    @Test
    fun `an idempotent accumulator is unaffected by the re-add the counted one sees`() {
        val maxGroupBy = MergeableGroupByModel(
            keyOf = { element -> element.toString().length },
            accumulate = { element -> element.toString() },
            merge = { left, right -> maxOf(left as String, right as String) },
        )
        val slice = SourceScript(a, listOf(ScriptEvent.Add(w, "xx"), ScriptEvent.Add(w, "xx")))

        maxGroupBy.evaluate(slice) shouldBe ModelState.MapState(mapOf(2 to "xx"))
    }

    // -----------------------------------------------------------------------
    // ORA2 §MODEL-10 — PnCounterCell per-source totals, pointwise max
    // -----------------------------------------------------------------------

    /**
     * BS-12 at model level: three replicas increment concurrently, and the converged reading is
     * the per-source pointwise-max sum — every source counted exactly once.
     */
    @Test
    fun `BS-12 concurrent replicas converge on the per-source pointwise-max total`() {
        val script = Script(
            listOf(
                SourceScript(a, listOf(ScriptEvent.Increment(w, 5), ScriptEvent.Increment(v, 3))),
                SourceScript(b, listOf(ScriptEvent.Increment(w, 10), ScriptEvent.Decrement(w, 4))),
                SourceScript(c, listOf(ScriptEvent.Decrement(w, 1))),
            ),
        )

        val converged = PnCounterConvergenceModel.converged(script)

        withClue("two writers into one instance share its slot — one cumulative total, not two") {
            converged.incs shouldBe mapOf(a to 8L, b to 10L)
        }
        converged.decs shouldBe mapOf(b to 4L, c to 1L)
        converged.total() shouldBe 13L
        PnCounterConvergenceModel.evaluate(script) shouldBe ModelState.ScalarState(13L)
    }

    @Test
    fun `pointwise max is commutative, associative and idempotent`() {
        val one = PnCounterState(incs = mapOf(a to 5L), decs = mapOf(a to 1L))
        val two = PnCounterState(incs = mapOf(a to 9L, b to 2L))
        val three = PnCounterState(decs = mapOf(b to 7L))

        one.merge(two) shouldBe two.merge(one)
        one.merge(two).merge(three) shouldBe one.merge(two.merge(three))
        withClue("idempotence is what terminates a mesh echo — the duplicate changes nothing") {
            one.merge(two).merge(two) shouldBe one.merge(two)
        }
    }

    /**
     * The property that distinguishes `PnCounterCell` from `CounterCell`, and the reason the
     * convergence model exists beside [PnCounterSourceModel]: hearing one source's total twice
     * must not double it. Plain addition — `CounterDelta`'s merge — would read 10 here.
     */
    @Test
    fun `a total heard twice is absorbed, not added`() {
        val once = PnCounterState(incs = mapOf(a to 5L))

        once.merge(once).total() shouldBe 5L
        withClue("a stale, smaller total loses to the fresher one rather than overwriting it") {
            once.merge(PnCounterState(incs = mapOf(a to 3L))).total() shouldBe 5L
            PnCounterState(incs = mapOf(a to 3L)).merge(once).total() shouldBe 5L
        }
    }

    @Test
    fun `a negative amount is refused by name rather than folded into a plausible number`() {
        val slice = SourceScript(a, listOf(ScriptEvent.Increment(w, -5)))

        val failure = shouldThrow<PnCounterConvergenceModel.NonMonotonicAmountException> {
            PnCounterConvergenceModel.fold(slice)
        }

        failure.message!! shouldContain "refuses a negative increment"
    }

    @Test
    fun `an instance the script never drives contributes nothing`() {
        PnCounterConvergenceModel.fold(SourceScript(a, emptyList())) shouldBe PnCounterState.EMPTY
        PnCounterConvergenceModel.converged(Script.EMPTY).total() shouldBe 0L
    }

    // -----------------------------------------------------------------------
    // ORA2 §MODEL-07 purity, for all three
    // -----------------------------------------------------------------------

    @Test
    fun `each model evaluates one script twice to equal results and leaves it unchanged`() {
        fun keyed() = SourceScript(a, listOf(ScriptEvent.Put(w, "k", "e1"), ScriptEvent.Put(w, "k", "e2")))
        fun grouped() = SourceScript(a, listOf(ScriptEvent.Add(w, "xx"), ScriptEvent.Remove(w, "xx")))
        fun counted() = Script(
            listOf(
                SourceScript(a, listOf(ScriptEvent.Increment(w, 4))),
                SourceScript(b, listOf(ScriptEvent.Decrement(w, 1))),
            ),
        )

        val keyedSubject = keyed()
        val groupedSubject = grouped()
        val countedSubject = counted()

        KeyedReputModel.evaluate(keyedSubject) shouldBe KeyedReputModel.evaluate(keyedSubject)
        countingGroupBy.evaluate(groupedSubject) shouldBe countingGroupBy.evaluate(groupedSubject)
        PnCounterConvergenceModel.evaluate(countedSubject) shouldBe
            PnCounterConvergenceModel.evaluate(countedSubject)

        withClue("ORA2 §MODEL-07: evaluation must not mutate the script") {
            keyedSubject shouldBe keyed()
            groupedSubject shouldBe grouped()
            countedSubject shouldBe counted()
        }
    }

    // -----------------------------------------------------------------------
    // 96 §E1.5 / [KE1-18]..[KE1-21] — the untag adapter (computenet-pez3)
    //
    // Model level first, then the SAME model driven against a real
    // OrMapCell -> UntagCell chain and against the differential runner. The
    // model is written from the specification of an untag adapter (see
    // UntagModel's KDoc), never from the cell — the cell tests below are what
    // discovers a disagreement, and they can only do that because neither side
    // was copied from the other.
    // -----------------------------------------------------------------------

    private fun untagSlice(vararg events: ScriptEvent) = SourceScript(a, events.toList())

    /**
     * `[KE1-18]`: a key whose published value moves is published once — and, because presence is
     * compared before value, a key that *appears* holding `null` is a change too. A value-only
     * comparison would silently drop that appearance, which is exactly the mistake this model
     * must not make on the kernel's behalf.
     */
    @Test
    fun `KE1-18 one put per exposed-value change, and an appearing null-valued key is a change`() {
        UntagModel.diff(emptyMap(), mapOf("k" to "v1")) shouldBe
            UntagModel.EffectiveChange(mapOf("k" to "v1"), emptySet())
        UntagModel.diff(mapOf("k" to "v1"), mapOf("k" to "v2")) shouldBe
            UntagModel.EffectiveChange(mapOf("k" to "v2"), emptySet())

        withClue("presence, not value: the key appears, holding null") {
            UntagModel.diff(emptyMap(), mapOf("k" to null)) shouldBe
                UntagModel.EffectiveChange(mapOf("k" to null), emptySet())
        }
        withClue("an unchanged null-valued key is still not news") {
            UntagModel.diff(mapOf("k" to null), mapOf("k" to null)).isEmpty() shouldBe true
        }
    }

    /**
     * `[KE1-19]`: the removal is published only where the key leaves the table — i.e. only when
     * its last live dot dies. A tombstone that leaves another live dot behind leaves the key
     * present with the surviving value, which is a put.
     */
    @Test
    fun `KE1-19 a removal is published only when the key leaves the table, never while a value survives`() {
        withClue("the last live dot died: the key is gone") {
            UntagModel.diff(mapOf("k" to "v"), emptyMap()) shouldBe
                UntagModel.EffectiveChange(emptyMap(), setOf("k"))
        }
        withClue("a dot died but another survives: the surviving value, as a put") {
            UntagModel.diff(mapOf("k" to "v2"), mapOf("k" to "v1")) shouldBe
                UntagModel.EffectiveChange(mapOf("k" to "v1"), emptySet())
        }
        // over a real slice: put, then remove the only dot.
        UntagModel.emissionsOverSlice(untagSlice(ScriptEvent.Put(w, "k", "v"), ScriptEvent.RemoveKey(w, "k"))) shouldBe
            listOf(
                UntagModel.EffectiveChange(mapOf("k" to "v"), emptySet()),
                UntagModel.EffectiveChange(emptyMap(), setOf("k")),
            )
    }

    /**
     * `[KE1-20]`: an equal-value re-put publishes **nothing at all** — not an empty delta. The
     * tagged map always mints a fresh dot, so its own state moves; the published table does not,
     * and the adapter is where that stops. The `null` in the result is the model's statement of
     * "no emission", which is what the cell comparison below checks against a real delta stream.
     */
    @Test
    fun `KE1-20 an equal-value re-put and a remove of an unheld key both publish nothing`() {
        UntagModel.emissionsOverSlice(
            untagSlice(
                ScriptEvent.Put(w, "k", "v"),
                ScriptEvent.Put(w, "k", "v"),
                ScriptEvent.RemoveKey(w, "absent"),
            ),
        ) shouldBe listOf(
            UntagModel.EffectiveChange(mapOf("k" to "v"), emptySet()),
            null,
            null,
        )
    }

    /**
     * `[KE1-21]`: a re-put is one publication carrying the new value, never a removal followed by
     * a put — so no downstream fold observes the key absent in between. The property holds by
     * construction here (a step's publication is a single value), which is why the check that
     * matters is the one against the real cell below.
     */
    @Test
    fun `KE1-21 a re-put crosses as a single change carrying the new value, never remove-then-put`() {
        val emissions = UntagModel.emissionsOverSlice(
            untagSlice(ScriptEvent.Put(w, "k", "v1"), ScriptEvent.Put(w, "k", "v2")),
        )
        emissions[1] shouldBe UntagModel.EffectiveChange(mapOf("k" to "v2"), emptySet())
        withClue("no removal anywhere, so the key is never observably absent") {
            emissions.filterNotNull().flatMap { it.removals }.shouldBeEmpty()
        }
    }

    /** The batch answer is the upstream table restated; a non-map or a second input fails by name. */
    @Test
    fun `UntagModel evaluates to the upstream table, and refuses a wrong arity or shape by name`() {
        UntagModel.evaluate(listOf(ModelState.MapState(mapOf("k" to "v")))) shouldBe
            ModelState.MapState(mapOf("k" to "v"))

        shouldThrow<IllegalArgumentException> {
            UntagModel.evaluate(listOf(ModelState.EMPTY_MAP, ModelState.EMPTY_MAP))
        }.message.shouldContain("unary")

        shouldThrow<IllegalStateException> {
            UntagModel.evaluate(listOf(ModelState.SetState("x")))
        }.message.shouldContain("untag")
    }

    /** A slice carrying gossip deliveries is refused by name, not folded without the peer logs. */
    @Test
    fun `UntagModel refuses a slice carrying deliveries rather than deriving tables it cannot know`() {
        val slice = SourceScript(a, listOf(ScriptEvent.Put(w, "k", "v")), listOf(Delivery(afterEvents = 1, from = b, throughEvents = 1)))
        shouldThrow<IllegalArgumentException> { UntagModel.emissionsOverSlice(slice) }
            .message.shouldContain("gossip deliveries")
    }

    // -----------------------------------------------------------------------
    // The model against the cell — the differential the registration buys
    // -----------------------------------------------------------------------

    /**
     * The whole point of this model: the publications it derives **from the script alone**, with
     * no kernel cell executed, are exactly the `MapDelta`s a real `OrMapCell -> UntagCell` chain
     * emits, in order.
     *
     * The script mixes every case `[KE1-18]`..`[KE1-21]` name — a first put, a changing re-put,
     * an equal-value re-put (the tagged map mints a dot; nothing must cross), a remove of the
     * only live dot, a remove of a key never held, and a re-put after removal — so a model that
     * got any one of them wrong disagrees here rather than passing on an easy schedule.
     */
    @Test
    fun `the publications UntagModel derives from a script alone are the MapDeltas a real chain emits`() {
        val events = listOf(
            ScriptEvent.Put(w, "k1", "v1"),
            ScriptEvent.Put(w, "k2", "v2"),
            ScriptEvent.Put(w, "k1", "v1b"),
            ScriptEvent.Put(w, "k1", "v1b"),
            ScriptEvent.RemoveKey(w, "k2"),
            ScriptEvent.RemoveKey(w, "never-held"),
            ScriptEvent.Put(w, "k2", "v2b"),
        )

        val map = OrMapCell<Any?, Any?>()
        val untag = UntagCell<Any?, Any?>()
        @Suppress("UNCHECKED_CAST")
        map.outlet.linkTo(untag.inlet as LinkFrom<Propagate<TaggedMapDelta<Any?, Any?>>>)
        val emitted = mutableListOf<MapDelta<Any?, Any?>>()
        untag.outlet.subscribe(
            Use.fixed(Propagate<MapDelta<Any?, Any?>> { emitted += it }, PortRef.generate()),
        )

        events.forEach { event ->
            when (event) {
                is ScriptEvent.Put -> map.inlet.call.put(event.key, event.element)
                is ScriptEvent.RemoveKey -> map.inlet.call.remove(event.key)
                else -> error("unreachable")
            }
        }

        val expected = UntagModel.emissionsOverSlice(untagSlice(*events.toTypedArray())).filterNotNull()
        withClue("model=$expected kernel=$emitted") {
            emitted.map { MapDelta(it.puts, it.removals) } shouldContainExactly
                expected.map { MapDelta(it.puts, it.removals) }
        }
        withClue("non-vacuity: the script really does exercise the swallowing branch") {
            expected.size shouldBeGreaterThanOrEqual 4
            (events.size - expected.size) shouldBeGreaterThanOrEqual 2
        }
        withClue("and the two sides agree about the final table too") {
            untag.current() shouldBe mapOf("k1" to "v1b", "k2" to "v2b")
        }
    }

    /**
     * The registration itself, end to end: a two-node case — a tagged source feeding the adapter,
     * observed at the adapter's untagged terminal — run through the real
     * [DifferentialRunner]. This is what the catalog entry buys that nothing else does: the
     * kernel side is a live `OrMapCell -> UntagCell` graph folded by the runner's own terminal
     * fold, the model side is [SingleInstanceOrMapModel] composed with [UntagModel], and
     * [RunOutcome.Success] means the two agreed without either having seen the other.
     */
    @Test
    fun `a tagged source feeding the untag adapter runs green through the differential runner`() {
        OperatorCatalog.reset()
        try {
            CoreOperators.registerAll()
            TaggedOperators.registerAll()
            val catalog = { id: String -> OperatorCatalog.entry(id)!!.kernel }

            val case = GeneratedCase(
                seed = 17L,
                topology = CaseTopology(
                    nodes = listOf(
                        TopologyNode("src", TaggedOperators.Ids.OR_MAP, emptyList(), a),
                        TopologyNode("u", TaggedOperators.Ids.UNTAG, listOf("src"), null),
                    ),
                    terminals = listOf(TerminalSpec("untagged", "u")),
                    placement = mapOf("src" to 0, "u" to 0),
                ),
                spec = GraphSpec(
                    listOf(
                        SpawnStep("src", catalog(TaggedOperators.Ids.OR_MAP)),
                        SpawnStep("u", catalog(TaggedOperators.Ids.UNTAG)),
                        ConnectStep(from = "src", outlet = "outlet", to = "u", inlet = "inlet"),
                    ),
                ),
                script = CaseScript(
                    listOf(
                        CaseStep.Op(a, ScriptEvent.Put(w, "k1", "v1")),
                        CaseStep.Op(a, ScriptEvent.Put(w, "k2", "v2")),
                        CaseStep.Op(a, ScriptEvent.Put(w, "k1", "v1b")),
                        CaseStep.Op(a, ScriptEvent.Put(w, "k1", "v1b")),
                        CaseStep.Op(a, ScriptEvent.RemoveKey(w, "k2")),
                    ),
                ),
                removeAudit = emptyList(),
            )

            val outcome = DifferentialRunner.run(case)
            withClue("outcome=$outcome") { outcome shouldBe RunOutcome.Success }
        } finally {
            OperatorCatalog.reset()
        }
    }
}
